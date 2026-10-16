package app.animix.data.downloads

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/** App-private storage. Every relative path is generated locally, never taken from a playlist. */
class OfflineFiles(val root: String) {
    internal fun path(relative: String): Path {
        require(relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]+")) })
        return Path(root, relative)
    }
    fun absolute(relative: String): String = path(relative).toString()
    fun exists(relative: String): Boolean = SystemFileSystem.exists(path(relative))
    fun size(relative: String): Long = SystemFileSystem.metadataOrNull(path(relative))?.size ?: 0L
    fun mkdir(relative: String) { SystemFileSystem.createDirectories(path(relative)) }
    fun read(relative: String): String? = if (!exists(relative)) null else SystemFileSystem.source(path(relative)).buffered().use { it.readString() }
    fun write(relative: String, content: String) {
        val target = path(relative)
        SystemFileSystem.createDirectories(target.parent ?: Path(root))
        val temp = path("$relative.tmp")
        SystemFileSystem.sink(temp).buffered().use { it.writeString(content) }
        SystemFileSystem.atomicMove(temp, target)
    }
    fun move(from: String, to: String) { SystemFileSystem.atomicMove(path(from), path(to)) }
    fun delete(relative: String) { if (exists(relative)) SystemFileSystem.delete(path(relative)) }
    fun deleteTree(relative: String) {
        val folder = path(relative)
        if (!SystemFileSystem.exists(folder)) return
        fun remove(item: Path) {
            if (SystemFileSystem.metadataOrNull(item)?.isDirectory == true) SystemFileSystem.list(item).forEach(::remove)
            SystemFileSystem.delete(item)
        }
        remove(folder)
    }
}

interface MediaAssembler {
    suspend fun assemble(playlistPath: String, outputPath: String, durationMs: Long, onProgress: (Float) -> Unit)
}
expect fun createMediaAssembler(): MediaAssembler
internal expect fun localMediaUri(path: String): String
internal expect fun downloadActivity(active: Boolean, onTimeout: () -> Unit)
