package app.animix.data.playback

import app.animix.AppGraph
import app.animix.core.Platform
import app.animix.model.Anime
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Opt-in real-browser tests: no user browser profile, live test prints no signed stream URLs. */
class ChromiumStreamCaptureTest {
    private fun enabled() = assumeTrue("Set ANIMIX_BROWSER_TEST=1 to test the installed Edge/Chrome", System.getenv("ANIMIX_BROWSER_TEST") == "1")

    private fun sessionDirectories(): Set<String> = File(Platform.cacheDir, "stream-capture").listFiles()?.map { it.name }?.toSet().orEmpty()

    private fun fixture(html: (String) -> String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val manifest = exchange.requestURI.path.endsWith(".m3u8")
            val body = if (manifest) "#EXTM3U\n#EXT-X-ENDLIST" else html(origin)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", if (manifest) "application/vnd.apple.mpegurl" else "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test fun capturesPlayerJsExpressionAndReleasesSession() = runBlocking {
        enabled()
        val before = sessionDirectories()
        val server = fixture { origin -> "<script>window.PlayerjsConfig={file:'[720p]$origin/720.mp4,[480p]$origin/480.mp4'};</script>" }
        try {
            val result = withTimeout(20_000) { ChromiumStreamCapture().candidates("http://127.0.0.1:${server.address.port}/embed").first() }
            assertTrue(result.value.startsWith("[720p]"))
            assertTrue(result.userAgent.contains("Mozilla"))
            assertEquals(before, sessionDirectories())
        } finally { server.stop(0) }
    }

    @Test fun capturesNetworkManifestBeforePlayback() = runBlocking {
        enabled()
        val server = fixture { origin -> "<script>fetch('$origin/master.m3u8?signature=private');</script>" }
        try {
            val result = withTimeout(20_000) { ChromiumStreamCapture().candidates("http://127.0.0.1:${server.address.port}/embed").first() }
            assertTrue(result.value.endsWith("master.m3u8?signature=private"))
        } finally { server.stop(0) }
    }

    @Test fun cancellationDuringStartupRemovesOwnedProfile() = runBlocking {
        enabled()
        val before = sessionDirectories()
        val server = fixture { "<html>No stream on this page</html>" }
        try {
            assertFailsWith<CancellationException> {
                withTimeout(500) { ChromiumStreamCapture().candidates("http://127.0.0.1:${server.address.port}/embed").first() }
            }
            assertEquals(before, sessionDirectories())
        } finally { server.stop(0) }
    }

    @Test fun liveKodikReturnsPlayableSources() = runBlocking {
        assumeTrue("Set ANIMIX_KODIK_LIVE_TEST=1 for the public provider smoke test", System.getenv("ANIMIX_KODIK_LIVE_TEST") == "1")
        val graph = AppGraph()
        try {
            withTimeout(75_000) {
                val anime = Anime.placeholder(52991).copy(title = "Провожающая в последний путь Фрирен")
                val source = graph.yummy.watchSource(anime)
                val episode = source.dubbings.first { it.isKodik }.episodes.first()
                val result = graph.kodik.resolve(episode.iframeUrl, forceRefresh = true)
                assertTrue(result.qualities.isNotEmpty())
                assertTrue(result.qualities.values.all(::isMediaUrl))
                assertEquals(MediaReferer, result.headers["Referer"])
                println("Kodik live smoke: resolved ${result.qualities.size} source(s); signed URLs omitted")
            }
        } finally { graph.http.close() }
    }
}
