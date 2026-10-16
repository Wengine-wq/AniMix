package app.animix.data.downloads

import app.animix.data.playback.nativeMediaFactory
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

actual fun createMediaAssembler(): MediaAssembler = DesktopMediaAssembler()
internal actual fun localMediaUri(path: String): String = File(path).toPath().toUri().toASCIIString()
internal actual fun downloadActivity(active: Boolean, onTimeout: () -> Unit) = Unit
internal actual fun downloadProgress(title: String, percent: Int?) = Unit

private class DesktopMediaAssembler : MediaAssembler {
    override suspend fun assemble(playlistPath: String, outputPath: String, durationMs: Long, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val factory = nativeMediaFactory("--vout=dummy", "--aout=dummy")
        val engine = factory.mediaPlayers().newMediaPlayer()
        val finished = CompletableDeferred<Unit>()
        engine.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) { finished.complete(Unit) }
            override fun error(mediaPlayer: MediaPlayer) { finished.completeExceptionally(IllegalStateException("Не удалось собрать MP4 из сохранённых частей.")) }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { if (durationMs > 0) onProgress((newTime.toFloat() / durationMs).coerceIn(0f, 0.99f)) }
        })
        try {
            File(outputPath).delete()
            val target = outputPath.replace('\\', '/').replace("'", "\\'")
            check(engine.media().play(localMediaUri(playlistPath), ":sout=#standard{access=file,mux=mp4,dst='$target'}", ":sout-keep", ":no-sout-all")) { "Не удалось начать сборку файла." }
            withTimeout((durationMs * 2).coerceAtLeast(120_000)) { finished.await() }
            engine.controls().stop() // closes the MP4 muxer before the atomic rename
            check(File(outputPath).length() > 0) { "Сборщик создал пустой файл." }
            onProgress(1f)
        } finally { engine.controls().stop(); engine.release(); factory.release() }
    }
}
