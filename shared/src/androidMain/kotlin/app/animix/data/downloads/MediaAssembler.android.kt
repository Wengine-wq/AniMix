package app.animix.data.downloads

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ProgressHolder
import app.animix.core.AndroidPlatform
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CompletableDeferred

actual fun createMediaAssembler(): MediaAssembler = AndroidMediaAssembler()
internal actual fun localMediaUri(path: String): String = Uri.fromFile(File(path)).toString()

@androidx.annotation.OptIn(UnstableApi::class)
private class AndroidMediaAssembler : MediaAssembler {
    override suspend fun assemble(playlistPath: String, outputPath: String, durationMs: Long, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(AndroidPlatform.context).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { finished.complete(Unit) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finished.completeExceptionally(IllegalStateException("Не удалось собрать MP4 из сохранённых частей."))
            }
        }).build()
        try {
            File(outputPath).delete()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(localMediaUri(playlistPath))).build()
            transformer.start(item, outputPath)
            val progress = ProgressHolder()
            while (!finished.isCompleted && isActive) {
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress / 100f)
                delay(250)
            }
            finished.await()
            onProgress(1f)
        } finally { transformer.cancel() }
    }
}
