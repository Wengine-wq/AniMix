package app.animix.android

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.animix.data.downloads.createMediaAssembler
import app.animix.data.playback.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generate fixtures before building this APK: scripts/generate-media-fixture.ps1. */
@RunWith(AndroidJUnit4::class)
class NativePlaybackTest {
    @Test fun hlsBecomesOneMp4AndPlaysOfflineWithPauseSeekAndFullscreen() = runFixture("")
    @Test fun separateAudioIsIncludedInTheOfflineMp4() = runFixture("separate")

    private fun runFixture(prefix: String) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "native-media-test").apply { mkdirs() }
        val output = File(root, "offline.mp4")
        var player: NativePlayer? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val assets = instrumentation.context.assets
            check("index.m3u8" in assets.list(prefix).orEmpty()) { "Run scripts/generate-media-fixture.ps1 before building androidTest" }
            fun copyFixtures(relative: String = "") {
                val path = listOf(prefix, relative).filter { it.isNotEmpty() }.joinToString("/")
                assets.list(path).orEmpty().forEach { name ->
                    val child = listOf(relative, name).filter { it.isNotEmpty() }.joinToString("/")
                    if (name.endsWith(".m3u8") || name.endsWith(".ts")) {
                        val file = File(root, child).apply { parentFile!!.mkdirs() }
                        assets.open(listOf(prefix, child).filter { it.isNotEmpty() }.joinToString("/")).use { input -> file.outputStream().use { input.copyTo(it) } }
                    } else if (prefix.isNotEmpty() && assets.list("$path/$name").orEmpty().isNotEmpty()) copyFixtures(child)
                }
            }
            copyFixtures()
            withTimeout(60_000) { createMediaAssembler().assemble(File(root, "index.m3u8").absolutePath, output.absolutePath, 12_000) {} }
            assertTrue(output.length() > 100_000)
            assertEquals("ftyp", output.inputStream().use { String(it.readNBytes(8).copyOfRange(4, 8), Charsets.US_ASCII) })
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                assertTrue(tracks.any { it.startsWith("video/") }); assertTrue(tracks.any { it.startsWith("audio/") })
            } finally { extractor.release() }
            // The original HLS is gone. Only the assembled MP4 is available to the player.
            root.listFiles()!!.filter { it != output }.forEach { it.deleteRecursively() }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                // A locked test device otherwise decodes into a hidden, invalid SurfaceView.
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                player = createNativePlayer().also { it.setVolume(0f) }
                activity.setContent { NativeVideoSurface(player!!, Modifier.fillMaxSize()) }
                player!!.load(android.net.Uri.fromFile(output).toString(), emptyMap())
            }
            val backend = player!!
            withTimeout(20_000) { backend.state.first { it.positionMs > 1500 || it.error != null } }
            assertNull(backend.state.value.error)
            assertTrue(backend.state.value.durationMs in 11_000..13_000)
            // Copy the actual video SurfaceView; Window's own buffer excludes this separate layer.
            val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
            val copied = CompletableDeferred<Int>()
            scenario.onActivity { activity ->
                fun surface(view: View): SurfaceView? = if (view is SurfaceView) view else
                    (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { surface(group.getChildAt(it)) } }
                PixelCopy.request(checkNotNull(surface(activity.window.decorView)), bitmap, { result -> copied.complete(result) }, Handler(Looper.getMainLooper()))
            }
            assertEquals(PixelCopy.SUCCESS, withTimeout(5000) { copied.await() })
            val colors = mutableSetOf<Int>()
            for (y in 60..140 step 5) for (x in 10..110 step 5) colors += bitmap.getPixel(x, y)
            assertTrue("Actual decoded video must reach the native surface", colors.size > 20)
            bitmap.recycle()
            instrumentation.runOnMainSync { backend.pause() }
            withTimeout(5_000) { backend.state.first { !it.playing && it.ready } }
            val paused = backend.state.value.positionMs; delay(700)
            assertTrue(kotlin.math.abs(backend.state.value.positionMs - paused) < 300)
            instrumentation.runOnMainSync { backend.seekTo(6000); backend.play() }
            withTimeout(8000) { backend.state.first { it.positionMs >= 6000 && it.playing } }
            scenario.onActivity { activity -> activity.setContent { PlayerFullscreen(onDismiss = {}) { NativeVideoSurface(backend, Modifier.fillMaxSize()) } } }
            withTimeout(12_000) { backend.state.first { it.ended || it.error != null } }
            assertNull(backend.state.value.error)
            assertTrue(backend.state.value.ended)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            scenario?.close(); root.deleteRecursively()
        }
    }
}
