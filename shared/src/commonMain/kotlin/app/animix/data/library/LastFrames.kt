package app.animix.data.library

import app.animix.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The last picture one saw of each title, kept on this device for «Продолжить просмотр»: a card shows
 * the moment one stopped at instead of repeating the poster. One small JPEG per title; a new one
 * replaces the old. Nothing is synced: on another device the card falls back to the poster.
 */
class LastFrames(directory: String, private val scope: CoroutineScope) {
    private val dir = directory.toPath()
    private val files = MutableStateFlow<Map<String, Path>>(emptyMap())

    /** Anime key to the file with its last frame. */
    val state: StateFlow<Map<String, Path>> = files.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                FileSystem.SYSTEM.createDirectories(dir)
                // "<key>_<time>.jpg": the newest file of a key wins, older ones are leftovers of an interrupted save.
                val found = FileSystem.SYSTEM.list(dir).filter { it.name.endsWith(".jpg") && '_' in it.name }
                    .groupBy { it.name.substringBeforeLast('_') }
                val latest = found.mapValues { (_, paths) -> paths.maxBy { it.name } }
                found.values.flatten().filter { it !in latest.values }.forEach { FileSystem.SYSTEM.delete(it) }
                files.update { latest + it }
            }.onFailure { AppLog.failure("LastFrames", "scan", it) }
        }
    }

    fun save(animeKey: String, jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                FileSystem.SYSTEM.createDirectories(dir)
                // A new name for every frame: the image cache never shows the previous picture for it.
                val target = dir / "${animeKey.filter { it.isLetterOrDigit() || it == '-' }}_${app.animix.core.nowMillis()}.jpg"
                FileSystem.SYSTEM.write(target) { write(jpeg) }
                val previous = files.value[animeKey]
                files.update { it + (animeKey to target) }
                if (previous != null && previous != target) FileSystem.SYSTEM.delete(previous)
            }.onFailure { AppLog.failure("LastFrames", "save", it) }
        }
    }
}
