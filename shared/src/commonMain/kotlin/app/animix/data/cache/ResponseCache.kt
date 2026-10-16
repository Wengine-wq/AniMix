package app.animix.data.cache

import app.animix.core.AppJson
import app.animix.core.DiskStore
import app.animix.core.nowMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Caches API results in memory and on disk.
 *
 * - A fresh entry is returned without touching the network.
 * - Identical requests running at the same time share one network call.
 * - When the network fails, an entry up to [staleLimit] old is returned instead of an error.
 */
class ResponseCache(
    private val disk: DiskStore,
    private val staleLimit: Duration = 7.days,
    private val memoryEntries: Int = 200,
) {
    @Serializable
    private class Entry(val savedAt: Long, val value: JsonElement)

    private val memory = LinkedHashMap<String, Entry>()
    private val inFlight = HashMap<String, CompletableDeferred<JsonElement>>()
    private val lock = Mutex()

    suspend fun <T> get(
        key: String,
        ttl: Duration,
        serializer: KSerializer<T>,
        forceRefresh: Boolean = false,
        load: suspend () -> T,
    ): T {
        val now = nowMillis()
        if (!forceRefresh) {
            val cached = lock.withLock { memory[key] } ?: readDisk(key)?.also { remember(key, it) }
            if (cached != null && now - cached.savedAt <= ttl.inWholeMilliseconds) {
                // A stored entry from an older app version may no longer fit the model: just reload it.
                runCatching { AppJson.decodeFromJsonElement(serializer, cached.value) }.onSuccess { return it }
            }
        }

        val (deferred, owner) = lock.withLock {
            inFlight[key]?.let { it to false }
                ?: CompletableDeferred<JsonElement>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return AppJson.decodeFromJsonElement(serializer, deferred.await())

        try {
            val value = load()
            val element = AppJson.encodeToJsonElement(serializer, value)
            val entry = Entry(nowMillis(), element)
            remember(key, entry)
            disk.write(fileName(key), AppJson.encodeToString(Entry.serializer(), entry))
            deferred.complete(element)
            return value
        } catch (error: Throwable) {
            val stale = lock.withLock { memory[key] } ?: readDisk(key)
            if (stale != null && nowMillis() - stale.savedAt <= staleLimit.inWholeMilliseconds) {
                val value = runCatching { AppJson.decodeFromJsonElement(serializer, stale.value) }.getOrNull()
                if (value != null) {
                    deferred.complete(stale.value)
                    return value
                }
            }
            deferred.completeExceptionally(error)
            throw error
        } finally {
            lock.withLock { inFlight.remove(key) }
        }
    }

    suspend fun clear() {
        lock.withLock { memory.clear() }
        disk.clear()
    }

    private suspend fun remember(key: String, entry: Entry) = lock.withLock {
        memory.remove(key)
        memory[key] = entry
        while (memory.size > memoryEntries) memory.remove(memory.keys.first())
    }

    private suspend fun readDisk(key: String): Entry? {
        val text = disk.read(fileName(key)) ?: return null
        return runCatching { AppJson.decodeFromString(Entry.serializer(), text) }.getOrNull()
    }

    /** A file-system safe, stable name for a cache key. */
    private fun fileName(key: String): String {
        var h1 = 0x811C9DC5.toInt()
        var h2 = 17
        for (c in key) {
            h1 = (h1 xor c.code) * 0x01000193
            h2 = h2 * 31 + c.code
        }
        return "${h1.toUInt().toString(16)}${h2.toUInt().toString(16)}.json"
    }
}
