package app.animix.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.animix.App
import app.animix.AppGraph
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the app off-screen into a PNG, for design reviews and docs:
 * `AniMix.exe --snapshot=out.png --size=1280x820 --wait=8 animix://home`.
 * Nothing is shown on screen and no window takes focus.
 */
object Snapshot {
    fun runIfRequested(args: Array<String>, graph: AppGraph): Boolean {
        val out = args.firstOrNull { it.startsWith("--snapshot=") }?.substringAfter('=') ?: return false
        val (width, height) = (args.firstOrNull { it.startsWith("--size=") }?.substringAfter('=') ?: "1280x820")
            .split('x').map { it.toInt() }
        val seconds = args.firstOrNull { it.startsWith("--wait=") }?.substringAfter('=')?.toLong() ?: 8
        val link = args.firstOrNull { it.startsWith("animix://") }

        runBlocking {
            graph.settings.load()
            graph.library.load()
        }
        val scene = ImageComposeScene(width, height, Density(1f)) { App(graph, startLink = link) }
        try {
            val end = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < end) {
                scene.render(System.nanoTime())
                Thread.sleep(40)
            }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)
                ?: error("Could not encode the snapshot")
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png.bytes)
        } finally {
            scene.close()
        }
        return true
    }
}
