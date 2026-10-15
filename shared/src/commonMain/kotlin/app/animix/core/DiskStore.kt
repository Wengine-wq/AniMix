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

    private fun path(name: String) = Path(root, name)

    suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val file = path(name)
        runCatching {
            if (!SystemFileSystem.exists(file)) null
            else SystemFileSystem.source(file).buffered().use { it.readString() }
        }.getOrNull()
    }

    suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = Path(root)
            if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
            val target = path(name)
            val temp = path("$name.tmp")
            SystemFileSystem.sink(temp).buffered().use { it.writeString(text) }
            SystemFileSystem.atomicMove(temp, target)
        }
        Unit
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        runCatching { SystemFileSystem.delete(path(name), mustExist = false) }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            val dir = Path(root)
            if (SystemFileSystem.exists(dir)) {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            }
        }
        Unit
    }
}
