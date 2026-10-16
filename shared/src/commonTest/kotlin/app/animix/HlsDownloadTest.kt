package app.animix

import app.animix.core.Platform
import app.animix.core.nowMillis
import app.animix.data.downloads.*
import app.animix.data.playback.ResolvedStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlin.test.*

class HlsDownloadTest {
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) })) { expectSuccess = true }
    private var serial = 0
    private fun files() = OfflineFiles("${Platform.cacheDir}/download-test-${nowMillis()}-${serial++}").also { it.mkdir("work/job") }
    private fun clean(files: OfflineFiles) = OfflineFiles(Platform.cacheDir).deleteTree(files.root.substringAfterLast('/').substringAfterLast('\\'))
    private fun assembler(files: OfflineFiles, inspect: (String) -> Unit = {}) = object : MediaAssembler {
        override suspend fun assemble(playlistPath: String, outputPath: String, durationMs: Long, onProgress: (Float) -> Unit) {
            inspect(files.read("work/job/index.m3u8")!!)
            files.write("job.assembling.mp4", "assembled"); onProgress(1f)
        }
    }
    private fun stream(path: String = "index.m3u8") = ResolvedStream(mapOf("720p" to "https://cdn.test/$path"), mapOf("Referer" to "https://player.test/", "User-Agent" to "AniMixTest"))

    @Test fun rewritePreservesByteRangesAndDeduplicatesKeysMapsAndMedia() {
        val resources = mutableListOf<HlsResource>()
        val (body, duration) = HlsManifest.rewriteMedia("""
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="../key.bin?sig=secret",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="init.mp4?sig=secret"
            #EXTINF:2.5,
            #EXT-X-BYTERANGE:100@0
            parts.mp4?sig=secret
            #EXTINF:3,
            #EXT-X-BYTERANGE:200@100
            parts.mp4?sig=secret
            #EXT-X-ENDLIST
        """.trimIndent(), "https://cdn.test/video/index.m3u8", resources)
        assertEquals(3, resources.size)
        assertEquals("https://cdn.test/key.bin?sig=secret", resources[0].url)
        assertEquals(5500, duration)
        assertTrue("#EXT-X-BYTERANGE:200@100" in body)
        assertTrue("URI=\"resource-000000.bin\"" in body)
        assertFalse("secret" in body)
        assertEquals(2, body.lines().count { it == "resource-000002.mp4" })
    }
    @Test fun rejectsLiveAndUnsupportedEncryption() {
        assertFailsWith<IllegalArgumentException> { HlsManifest.rewriteMedia("#EXTM3U\n#EXTINF:3,\na.ts", "https://cdn.test/a.m3u8", mutableListOf()) }
        assertFailsWith<IllegalArgumentException> { HlsManifest.rewriteMedia("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:3,\na.ts\n#EXT-X-ENDLIST", "https://cdn.test/a.m3u8", mutableListOf()) }
    }
    @Test fun qualitySelectionDoesNotSilentlyUpscale() {
        val variants = listOf(HlsVariant("1080", 1080, 3000, null), HlsVariant("480", 480, 1000, null), HlsVariant("720", 720, 2000, null))
        assertEquals("480", HlsManifest.choose(variants, "540p").url)
        assertEquals("480", HlsManifest.choose(variants, "240p").url)
        assertEquals("1080", HlsManifest.choose(variants, "Авто").url)
    }
    @Test fun pausedHlsReusesCompletedPartsAfterTokenRefresh() = runTest {
        val files = files(); val requested = mutableListOf<String>(); var assembled = 0
        val http = client { request ->
            assertEquals("https://player.test/", request.headers[HttpHeaders.Referrer])
            assertEquals("AniMixTest", request.headers[HttpHeaders.UserAgent])
            requested += request.url.encodedPath
            if (request.url.encodedPath.endsWith(".m3u8")) respond("#EXTM3U\n#EXTINF:3,\na.ts?sig=${request.url.parameters["sig"]}\n#EXTINF:3,\nb.ts?sig=new\n#EXT-X-ENDLIST")
            else respond("segment-${request.url.encodedPath}")
        }
        try {
            val pipeline = HlsDownload(http, files, assembler(files) { assembled++; assertFalse("https://" in it) })
            assertFailsWith<CancellationException> { pipeline.download(stream("index.m3u8?sig=old"), "job", "720p") { done, _, _, _ -> if (done == 1) throw CancellationException("pause") } }
            assertFalse(files.exists("job.mp4"))
            assertEquals("job.mp4", pipeline.download(stream("index.m3u8?sig=new"), "job", "720p") { _, _, _, _ -> })
            assertEquals(1, requested.count { it == "/a.ts" }); assertEquals(1, assembled)
            assertFalse(files.exists("work/job")); assertEquals("assembled", files.read("job.mp4"))
        } finally { http.close(); clean(files) }
    }
    @Test fun audioRenditionIsDownloadedAndReferencedLocally() = runTest {
        val files = files(); val paths = mutableListOf<String>()
        val http = client { request ->
            val path = request.url.encodedPath; paths += path
            respond(when (path) {
                "/index.m3u8" -> "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"dub\",DEFAULT=YES,URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,AUDIO=\"dub\"\nvideo.m3u8"
                "/audio.m3u8" -> "#EXTM3U\n#EXTINF:3,\na.aac\n#EXT-X-ENDLIST"
                "/video.m3u8" -> "#EXTM3U\n#EXTINF:3,\nv.ts\n#EXT-X-ENDLIST"
                else -> "data"
            })
        }
        try {
            HlsDownload(http, files, assembler(files) { assertTrue("URI=\"audio.m3u8\"" in it); assertTrue("resource-000001.aac" in files.read("work/job/audio.m3u8")!!) })
                .download(ResolvedStream(mapOf("Авто" to "https://cdn.test/index.m3u8", "720p" to "https://cdn.test/video.m3u8"), emptyMap()), "job", "720p") { _, _, _, _ -> }
            assertTrue(paths.containsAll(listOf("/a.aac", "/v.ts")))
        } finally { http.close(); clean(files) }
    }
    @Test fun interruptedMp4ContinuesAtExactByteOffset() = runTest {
        val files = files(); files.write("work/job/video.mp4.part", "abc"); files.write("work/job/video.mp4.etag", "\"v1\"")
        val http = client { request ->
            assertEquals("bytes=3-", request.headers[HttpHeaders.Range]); assertEquals("\"v1\"", request.headers[HttpHeaders.IfRange])
            respond("def", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange to listOf("bytes 3-5/6"), HttpHeaders.ContentLength to listOf("3"), HttpHeaders.ETag to listOf("\"v1\"")))
        }
        try {
            HlsDownload(http, files, assembler(files)).download(stream("video.mp4"), "job", "720p") { _, _, _, _ -> }
            assertEquals("abcdef", files.read("job.mp4"))
        } finally { http.close(); clean(files) }
    }
    @Test fun ignoredRangeReplacesPartialFileRatherThanAppending() = runTest {
        val files = files(); files.write("work/job/video.mp4.part", "old")
        val http = client { respond("new-video", HttpStatusCode.OK) }
        try {
            HlsDownload(http, files, assembler(files)).download(stream("video.mp4"), "job", "720p") { _, _, _, _ -> }
            assertEquals("new-video", files.read("job.mp4"))
        } finally { http.close(); clean(files) }
    }
    @Test fun alreadyCompleteRangeIsAcceptedEvenWithExpectSuccessEnabled() = runTest {
        val files = files(); files.write("work/job/video.mp4.part", "abc")
        val http = client { respond("", HttpStatusCode.RequestedRangeNotSatisfiable, headersOf(HttpHeaders.ContentRange, "bytes */3")) }
        try {
            HlsDownload(http, files, assembler(files)).download(stream("video.mp4"), "job", "720p") { _, _, _, _ -> }
            assertEquals("abc", files.read("job.mp4"))
        } finally { http.close(); clean(files) }
    }
    @Test fun wrongRangeAndChangedEntityCannotCorruptSavedBytes() = runTest {
        val files = files(); files.write("work/job/video.mp4.part", "abc"); files.write("work/job/video.mp4.etag", "\"old\"")
        var wrongRange = true
        val http = client { respond("def", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange to listOf(if (wrongRange) "bytes 0-2/6" else "bytes 3-5/6"), HttpHeaders.ETag to listOf("\"new\""))) }
        try {
            val pipeline = HlsDownload(http, files, assembler(files))
            repeat(2) { assertFailsWith<IllegalStateException> { pipeline.download(stream("video.mp4"), "job", "720p") { _, _, _, _ -> } }; wrongRange = false }
            assertEquals("abc", files.read("work/job/video.mp4.part")); assertFalse(files.exists("job.mp4"))
        } finally { http.close(); clean(files) }
    }
    @Test fun expiredSourceCanBeDistinguishedFromOtherHttpFailures() = runTest {
        val files = files(); val http = client { respond("forbidden", HttpStatusCode.Forbidden) }
        try { assertFailsWith<SourceExpired> { HlsDownload(http, files, assembler(files)).download(stream(), "job", "720p") { _, _, _, _ -> } } }
        finally { http.close(); clean(files) }
    }
}
