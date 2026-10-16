package app.animix.data.playback

import app.animix.core.createHttpEngine
import app.animix.data.downloads.*
import com.sun.net.httpserver.HttpServer
import java.awt.Frame
import java.awt.BorderLayout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Real H.264/AAC HLS -> MP4 -> embedded native playback, with the HTTP server stopped. */
class NativeMediaTest {
    @Test fun savesOneMp4AndDecodesItOfflineWithPauseSeekAndSurfaceRecreation() = runFixture("")
    @Test fun separateAudioIsIncludedInTheOfflineMp4() = runFixture("separate")

    private fun runFixture(flavor: String) = runBlocking<Unit> {
        assumeTrue("Set ANIMIX_MEDIA_TEST=1 and generate the synthetic fixture", System.getenv("ANIMIX_MEDIA_TEST") == "1")
        val fixture = File(File(System.getenv("ANIMIX_MEDIA_FIXTURE") ?: "../build/test-media"), flavor).canonicalFile
        check(File(fixture, "index.m3u8").isFile) { "Run scripts/generate-media-fixture.ps1" }
        val folder = Files.createTempDirectory("animix-native-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            if (exchange.requestHeaders.getFirst("Referer") != "https://player.test/" || exchange.requestHeaders.getFirst("User-Agent") != "AniMixNativeTest") {
                exchange.sendResponseHeaders(403, -1); exchange.close()
            } else {
                val file = File(fixture, exchange.requestURI.path.removePrefix("/"))
                val body = file.readBytes()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val http = io.ktor.client.HttpClient(createHttpEngine())
        var player: DesktopNativePlayer? = null
        var frame: Frame? = null
        try {
            val files = OfflineFiles(folder.absolutePath)
            val output = HlsDownload(http, files, createMediaAssembler()).download(
                ResolvedStream(mapOf("720p" to "$origin/index.m3u8"), mapOf("Referer" to "https://player.test/", "User-Agent" to "AniMixNativeTest")),
                "native", "720p",
            ) { _, _, _, _ -> }
            server.stop(0)
            assertTrue(File(folder, output).length() > 100_000)
            assertFalse(File(folder, "work/native").exists())
            assertEquals("ftyp", File(folder, output).inputStream().use { it.readNBytes(8).copyOfRange(4, 8).toString(Charsets.US_ASCII) })
            SwingUtilities.invokeAndWait {
                player = DesktopNativePlayer()
                frame = Frame("AniMix native media test").apply {
                    layout = BorderLayout(); add(player!!.component); setSize(400, 300); isVisible = true
                }
                player!!.setVolume(0f)
                player!!.load(localMediaUri(File(folder, output).absolutePath), emptyMap())
            }
            val backend = player!!
            withTimeout(20_000) { backend.state.first { it.positionMs >= 1500 || it.error != null } }
            assertNull(backend.state.value.error)
            assertTrue(backend.state.value.durationMs in 11_000..13_000)
            val stats = backend.engine.media().info().statistics()
            assertTrue(stats.decodedVideo() > 0, "Native decoder must decode actual video frames")
            assertTrue(stats.decodedAudio() > 0, "Native decoder must decode actual audio samples")
            assertTrue(stats.picturesDisplayed() > 0, "Frames must reach the embedded video surface")
            backend.pause()
            withTimeout(5_000) { backend.state.first { !it.playing && it.ready } }
            val pausedAt = backend.state.value.positionMs
            delay(700)
            assertTrue(kotlin.math.abs(backend.state.value.positionMs - pausedAt) < 300)
            backend.seekTo(6500); backend.play()
            withTimeout(8_000) { backend.state.first { it.positionMs >= 6500 } }
            SwingUtilities.invokeAndWait {
                frame!!.remove(backend.component); frame!!.dispose()
                frame = Frame("AniMix fullscreen surface test").apply { add(backend.component); setSize(640, 400); isVisible = true }
            }
            withTimeout(12_000) { backend.state.first { it.ready && it.playing && it.positionMs >= 6500 } }
            assertNull(backend.state.value.error)
            withTimeout(12_000) { backend.state.first { it.ended } }
            Unit
        } finally {
            SwingUtilities.invokeAndWait { player?.release(); frame?.dispose() }
            server.stop(0); http.close(); folder.deleteRecursively()
        }
    }
}
