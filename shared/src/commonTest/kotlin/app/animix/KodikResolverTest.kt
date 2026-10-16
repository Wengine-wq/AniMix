package app.animix

import app.animix.data.playback.KodikResolver
import app.animix.data.playback.StreamCandidate
import app.animix.data.playback.StreamCapture
import app.animix.data.playback.StreamResolutionException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KodikResolverTest {
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }))

    private fun capture(vararg values: String) = object : StreamCapture {
        override fun candidates(iframeUrl: String): Flow<StreamCandidate> = flow {
            values.forEach { emit(StreamCandidate(it, "TestBrowser")); delay(1_000) }
            awaitCancellation()
        }
    }

    @Test fun headersAndAdaptiveVariantsAreReturnedTogether() = runTest {
        val http = client { request ->
            assertEquals("https://kodikapi.com/", request.headers["Referer"])
            assertEquals("TestBrowser", request.headers["User-Agent"])
            assertEquals(HttpMethod.Get, request.method)
            respond("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720/index.m3u8?sig=child")
        }
        try {
            val result = KodikResolver(http, capture("https://cdn.test/master.m3u8?sig=master")).resolve("//player.test/episode/1")
            assertEquals("https://cdn.test/master.m3u8?sig=master", result.qualities["Авто"])
            assertEquals("https://cdn.test/720/index.m3u8?sig=child", result.qualities["720p"])
            assertEquals("TestBrowser", result.headers["User-Agent"])
        } finally { http.close() }
    }

    @Test fun htmlFallbackDoesNotWinOverLaterRealManifest() = runTest {
        val http = client { request ->
            if (request.url.encodedPath == "/master.m3u8") respond("<html>Not a manifest</html>")
            else respond("#EXTM3U\n#EXTINF:2,\nsegment.ts\n#EXT-X-ENDLIST")
        }
        try {
            val result = KodikResolver(http, capture("https://cdn.test/master.m3u8", "https://cdn.test/episode.m3u8")).resolve("https://player.test/1")
            assertEquals(listOf("https://cdn.test/episode.m3u8"), result.qualities.values.toList())
        } finally { http.close() }
    }

    @Test fun mp4UsesHeadAndMissingSiblingsAreNotInvented() = runTest {
        val http = client { request ->
            assertEquals(HttpMethod.Head, request.method)
            if (request.url.encodedPath == "/720.mp4") respond("", headers = headersOf("Content-Type", "video/mp4"))
            else respond("", HttpStatusCode.NotFound)
        }
        try {
            val result = KodikResolver(http, capture("https://cdn.test/720.mp4?sig=720")).resolve("https://player.test/1")
            assertEquals(mapOf("720p" to "https://cdn.test/720.mp4?sig=720"), result.qualities)
        } finally { http.close() }
    }

    @Test fun failedMasterDoesNotDiscardAlreadyBufferedLowerQuality() = runTest {
        val browser = object : StreamCapture {
            override fun candidates(iframeUrl: String) = flow {
                emit(StreamCandidate("https://cdn.test/master.m3u8", "TestBrowser"))
                delay(50)
                emit(StreamCandidate("https://cdn.test/episode.m3u8", "TestBrowser"))
                awaitCancellation()
            }
        }
        val http = client { request ->
            if (request.url.encodedPath == "/master.m3u8") respond("<html>Unavailable</html>")
            else respond("#EXTM3U\n#EXT-X-ENDLIST")
        }
        try {
            val result = KodikResolver(http, browser).resolve("https://player.test/1")
            assertEquals(listOf("https://cdn.test/episode.m3u8"), result.qualities.values.toList())
        } finally { http.close() }
    }

    @Test fun redirectedMasterUsesFinalDirectoryForRelativeVariants() = runTest {
        val http = client { request ->
            if (request.url.encodedPath == "/start.m3u8") respond("", HttpStatusCode.Found, headersOf("Location", "https://other.test/hls/master.m3u8?sig=new"))
            else respond("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720/index.m3u8?sig=child")
        }
        try {
            val result = KodikResolver(http, capture("https://cdn.test/start.m3u8?sig=old")).resolve("https://player.test/1")
            assertEquals("https://other.test/hls/master.m3u8?sig=new", result.qualities["Авто"])
            assertEquals("https://other.test/hls/720/index.m3u8?sig=child", result.qualities["720p"])
        } finally { http.close() }
    }

    @Test fun expiredCacheAndForceRefreshStartNewBrowser() = runTest {
        var captures = 0
        var expired = false
        var now = 0L
        val browser = object : StreamCapture {
            override fun candidates(iframeUrl: String) = flow {
                captures++
                emit(StreamCandidate("https://cdn.test/episode-$captures.m3u8", "TestBrowser"))
                awaitCancellation()
            }
        }
        val http = client { request ->
            if (expired && request.url.encodedPath == "/episode-1.m3u8") respond("Expired", HttpStatusCode.Forbidden)
            else respond("#EXTM3U\n#EXT-X-ENDLIST")
        }
        try {
            val resolver = KodikResolver(http, browser) { now }
            resolver.resolve("https://player.test/1")
            resolver.resolve("https://player.test/1")
            assertEquals(1, captures)
            expired = true
            resolver.resolve("https://player.test/1")
            assertEquals(2, captures)
            resolver.resolve("https://player.test/1", forceRefresh = true)
            assertEquals(3, captures)
            now += 1_200_001
            resolver.resolve("https://player.test/1")
            assertEquals(4, captures)
            resolver.resolve("https://player.test/2")
            assertEquals(5, captures)
            resolver.clear()
            resolver.resolve("https://player.test/2")
            assertEquals(6, captures)
        } finally { http.close() }
    }

    @Test fun cancellationDisposesBrowserAndDoesNotBecomeUserError() = runTest {
        var disposed = false
        val browser = object : StreamCapture {
            override fun candidates(iframeUrl: String) = flow<StreamCandidate> {
                try { awaitCancellation() } finally { disposed = true }
            }
        }
        val http = client { respond("") }
        try {
            assertFailsWith<CancellationException> { withTimeout(1_000) { KodikResolver(http, browser).resolve("https://player.test/1") } }
            assertTrue(disposed)
        } finally { http.close() }
    }

    @Test fun ownTimeoutIsActionableAndDisposesBrowser() = runTest {
        var disposed = false
        val browser = object : StreamCapture {
            override fun candidates(iframeUrl: String) = flow<StreamCandidate> {
                try { awaitCancellation() } finally { disposed = true }
            }
        }
        val http = client { respond("") }
        try {
            val error = assertFailsWith<StreamResolutionException> { KodikResolver(http, browser).resolve("https://player.test/1?private=secret") }
            assertTrue(error.message.orEmpty().contains("28"))
            assertFalse(error.message.orEmpty().contains("secret"))
            assertTrue(disposed)
        } finally { http.close() }
    }
}
