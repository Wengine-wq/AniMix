package app.animix.data.account

import app.animix.core.AppJson
import app.animix.data.achievements.AchievementState
import app.animix.data.library.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val SyncJson = Json(AppJson) { encodeDefaults = true; explicitNulls = false }

@Serializable internal data class SyncPayload(
    val version: Int = 1,
    val library: LibraryData = LibraryData(),
    val achievements: AchievementState = AchievementState(),
    val preferences: JsonObject = JsonObject(emptyMap()),
    /** Grow-only counters per installation, so retried transfers never count playback twice. */
    val usage: Map<String, SyncUsage> = emptyMap(),
)
@Serializable internal data class SyncUsage(val days: Map<Long, Int> = emptyMap(), val years: Map<Int, YearUsage> = emptyMap())
@Serializable internal data class SyncEnvelope(val revision: Long = 0, val payload: SyncPayload? = null)

/** Three-way merge: preserve remote edits to untouched fields, local edits win same-field conflicts.
 * Missing keys are deletions, not a request to restore an old favorite/list/position. */
internal fun mergeJson(base: JsonElement?, local: JsonElement?, remote: JsonElement?): JsonElement? {
    if (local == base) return remote
    if (remote == base || local == remote) return local
    if (local is JsonObject && remote is JsonObject) {
        val previous = base as? JsonObject
        return JsonObject((local.keys + remote.keys + previous?.keys.orEmpty()).mapNotNull { key ->
            mergeJson(previous?.get(key), local[key], remote[key])?.let { key to it }
        }.toMap())
    }
    return local
}

internal inline fun <reified T> threeWay(base: T, local: T, remote: T): T =
    SyncJson.decodeFromJsonElement(mergeJson(SyncJson.encodeToJsonElement(base), SyncJson.encodeToJsonElement(local), SyncJson.encodeToJsonElement(remote))!!)

private fun <K> earliest(a: Map<K, Long>, b: Map<K, Long>) = (a.keys + b.keys).associateWith { minOf(a[it] ?: Long.MAX_VALUE, b[it] ?: Long.MAX_VALUE) }
private fun <K> maximum(a: Map<K, Long>, b: Map<K, Long>) = (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }

internal fun mergeAwards(a: AchievementState, b: AchievementState): AchievementState {
    val unlocked = earliest(a.unlocked, b.unlocked)
    return AchievementState(unlocked, maximum(a.counters, b.counters),
        (a.sets.keys + b.sets.keys).associateWith { a.sets[it].orEmpty() + b.sets[it].orEmpty() },
        earliest(a.firstCompleted, b.firstCompleted), earliest(a.historical, b.historical) - unlocked.keys)
}

internal fun mergeLibrary(base: LibraryData, local: LibraryData, remote: LibraryData): LibraryData {
    val merged = threeWay(base, local, remote)
    val entries = merged.entries.mapValues { (key, entry) ->
        val left = local.entries[key]; val right = remote.entries[key]
        // Playback evidence is a union, never a sum or a reconstruction from list marks.
        val viewing = (left?.viewing.orEmpty().keys + right?.viewing.orEmpty().keys).associateWith { number ->
            val a = left?.viewing?.get(number); val b = right?.viewing?.get(number)
            val duration = maxOf(a?.durationMs ?: 0, b?.durationMs ?: 0)
            EpisodeViewing(duration, mergeViewedRanges(a?.ranges.orEmpty() + b?.ranges.orEmpty(), duration),
                a?.opening ?: b?.opening, a?.ending ?: b?.ending, a?.reachedEnd == true || b?.reachedEnd == true,
                listOfNotNull(a?.completedAt, b?.completedAt).minOrNull())
        }
        val imported = listOfNotNull(left?.importedViewing, right?.importedViewing)
        entry.copy(viewing = viewing, importedViewing = if (imported.isEmpty()) null else ImportedViewing(
            imported.any { it.completed }, imported.maxOf { it.episodesWatched }))
    }
    return merged.copy(entries = entries, legacyWatchSeconds = maxOf(local.legacyWatchSeconds, remote.legacyWatchSeconds),
        reviewedYears = local.reviewedYears + remote.reviewedYears,
        profile = merged.profile.copy(createdAt = listOf(local.profile.createdAt, remote.profile.createdAt).filter { it > 0 }.minOrNull() ?: 0))
}

/** Counter deltas are attributed only to this installation before the next transfer. */
internal fun usageDelta(current: SyncUsage, previous: SyncUsage, owned: SyncUsage): SyncUsage {
    fun delta(a: JsonElement?, b: JsonElement?, c: JsonElement?, key: String = ""): JsonElement {
        if (a is JsonObject) return JsonObject((a.keys + (c as? JsonObject)?.keys.orEmpty()).associateWith {
            delta(a[it], (b as? JsonObject)?.get(it), (c as? JsonObject)?.get(it), if (key == "firstPlayed") key else it)
        })
        if (a is JsonArray) return JsonArray(a.mapIndexed { index, value -> delta(value, (b as? JsonArray)?.getOrNull(index), (c as? JsonArray)?.getOrNull(index), key) })
        val now = (a as? JsonPrimitive)?.longOrNull ?: 0
        val before = (b as? JsonPrimitive)?.longOrNull ?: 0
        val own = (c as? JsonPrimitive)?.longOrNull ?: 0
        return JsonPrimitive(when (key) {
            "firstPlayed" -> if (own == 0L) now else minOf(own, now)
            "lastPlaybackAt", "longestSessionSeconds" -> maxOf(now, own)
            "currentSessionSeconds" -> 0
            else -> (own + (now - before).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong())
        })
    }
    return SyncJson.decodeFromJsonElement(delta(SyncJson.encodeToJsonElement(current), SyncJson.encodeToJsonElement(previous), SyncJson.encodeToJsonElement(owned)))
}

internal fun usageUnion(a: SyncUsage, b: SyncUsage): SyncUsage {
    fun maxTree(a: JsonElement?, b: JsonElement?, key: String = ""): JsonElement {
        if (a == null) return b ?: JsonNull
        if (b == null) return a
        if (a is JsonObject && b is JsonObject) return JsonObject((a.keys + b.keys).associateWith { maxTree(a[it], b[it], if (key == "firstPlayed") key else it) })
        if (a is JsonArray && b is JsonArray) return JsonArray((0 until maxOf(a.size, b.size)).map { maxTree(a.getOrNull(it), b.getOrNull(it), key) })
        if (key == "firstPlayed") return JsonPrimitive(listOfNotNull((a as? JsonPrimitive)?.longOrNull, (b as? JsonPrimitive)?.longOrNull).filter { it > 0 }.minOrNull() ?: 0)
        return JsonPrimitive(maxOf((a as? JsonPrimitive)?.longOrNull ?: 0, (b as? JsonPrimitive)?.longOrNull ?: 0))
    }
    return SyncJson.decodeFromJsonElement(maxTree(SyncJson.encodeToJsonElement(a), SyncJson.encodeToJsonElement(b)))
}

internal fun aggregateUsage(components: Collection<SyncUsage>): SyncUsage {
    fun sum(values: List<JsonElement>, path: String = ""): JsonElement {
        val objects = values.filterIsInstance<JsonObject>()
        if (objects.isNotEmpty()) return JsonObject(objects.flatMap { it.keys }.toSet().associateWith { key ->
            sum(objects.mapNotNull { it[key] }, if (path == "firstPlayed") "firstPlayed" else key)
        })
        val arrays = values.filterIsInstance<JsonArray>()
        if (arrays.isNotEmpty()) return JsonArray((0 until arrays.maxOf { it.size }).map { index -> sum(arrays.mapNotNull { it.getOrNull(index) }, path) })
        val numbers = values.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        return JsonPrimitive(when (path) {
            "firstPlayed" -> numbers.filter { it > 0 }.minOrNull() ?: 0
            "lastPlaybackAt", "longestSessionSeconds" -> numbers.maxOrNull() ?: 0
            "currentSessionSeconds" -> 0
            else -> numbers.sum().coerceAtMost(Int.MAX_VALUE.toLong())
        })
    }
    return if (components.isEmpty()) SyncUsage() else SyncJson.decodeFromJsonElement(sum(components.map { SyncJson.encodeToJsonElement(it) }))
}

internal val SyncedPreferenceKeys = setOf("themeMode", "skipOpenings", "skipEndings", "autoNextEpisode", "autoNextAsked",
    "favoritesLast", "preferredDubbing", "preferredQuality", "downloadQuality", "dubbingByAnime")
