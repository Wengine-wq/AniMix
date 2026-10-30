package app.animix.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** Small text files under one directory, written atomically (temp file + move). */
class DiskStore(private val root: String) {

    internal fun filePath(name: String): String = path(name).toString()

    private fun path(name: String) = Path(root, name)

    suspend fun read(name: String): String? = try { readChecked(name) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { AppLog.failure("Storage", "file_read", error); null }

    /** For user progress: an I/O failure must not be mistaken for an empty profile. */
    suspend fun readChecked(name: String): String? = withContext(Dispatchers.IO) {
        val file = path(name)
        if (!SystemFileSystem.exists(file)) null
        else SystemFileSystem.source(file).buffered().use { it.readString() }
    }

    suspend fun write(name: String, text: String) {
        try { writeChecked(name, text) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { AppLog.failure("Storage", "file_write", error) }
    }

    /** Same atomic write, but callers can distinguish a failed save from success. */
    suspend fun writeChecked(name: String, text: String) = withContext(Dispatchers.IO) {
        val dir = Path(root)
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
        val target = path(name)
        val temp = path("$name.tmp")
        SystemFileSystem.sink(temp).buffered().use { it.writeString(text) }
        try {
            SystemFileSystem.atomicMove(temp, target)
        } catch (error: Exception) {
            // Windows refuses to replace a file it cannot open for replacement (another owner, an
            // odd ACL). Removing it first still works when deletion is allowed; the backup covers the gap.
            if (!SystemFileSystem.exists(target) && !SystemFileSystem.exists(temp)) throw error
            runCatching { SystemFileSystem.delete(target, mustExist = false) }
            SystemFileSystem.atomicMove(temp, target)
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        runCatching { SystemFileSystem.delete(path(name), mustExist = false) }
            .onFailure { AppLog.failure("Storage", "file_delete", it) }
        Unit
    }

    /** Session removal must be durable before the UI reports a completed sign-out. */
    suspend fun deleteChecked(name: String) = withContext(Dispatchers.IO) {
        SystemFileSystem.delete(path(name), mustExist = false)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            val dir = Path(root)
            if (SystemFileSystem.exists(dir)) {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            }
        }.onFailure { AppLog.failure("Storage", "cache_clear", it) }
        Unit
    }
}
