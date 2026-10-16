package app.animix.data.downloads

import app.animix.core.AppJson
import app.animix.data.playback.*
import app.animix.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*

class DownloadManagerTest {
    private val anime = Anime.placeholder(123).copy(title = "Test anime")
    private val episodes = (1..3).map { Episode(it.toString(), null, "https://player.test/$it", 12, null, null) }
    private val dub = Dubbing("Studio One", "Kodik", episodes)
    private val assembler = object : MediaAssembler {
        override suspend fun assemble(playlistPath: String, outputPath: String, durationMs: Long, onProgress: (Float) -> Unit) { error("MP4 test must not invoke the HLS assembler") }
    }
    private fun capture(block: suspend (String) -> String) = object : StreamCapture {
        override fun candidates(iframeUrl: String) = flow { emit(StreamCandidate(block(iframeUrl), "TestBrowser")); awaitCancellation() }
    }
    private suspend fun awaitQueue(manager: DownloadManager, predicate: (List<DownloadItem>) -> Boolean) = withTimeout(10_000) { manager.state.first(predicate) }

    @Test fun selectedDubbingAndQueueSurviveRestartWithoutPersistingSignedUrls() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("animix-queue-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val files = OfflineFiles(folder.absolutePath)
        try {
            val resolver = KodikResolver(http, capture { awaitCancellation() })
            val manager = DownloadManager(scope, resolver, http, files, assembler)
            manager.load(); manager.enqueueSeason(anime, dub); manager.enqueueSeason(anime, dub)
            manager.pauseAll()
            assertEquals(3, manager.state.value.size)
            assertTrue(manager.state.value.all { it.status == DownloadStatus.Paused && it.dubbingKey == dub.key })
            manager.enqueueEpisode(anime, dub.copy(studio = "Studio Two"), episodes.first(), "1080p")
            manager.pauseAll()
            assertEquals(4, manager.state.value.size)
            val restored = DownloadManager(scope, resolver, http, files, assembler)
            restored.load()
            assertEquals(manager.state.value, restored.state.value)
            assertFalse(File(folder, "queue.json").readText().contains("cdn.test"))
            restored.remove(restored.state.value.last().id)
            assertEquals(3, restored.state.value.size)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); folder.deleteRecursively() }
    }

    @Test fun failedEpisodeDoesNotBlockFollowingEpisodesAndCompletedFileNeedsNoNetwork() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("animix-queue-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var captureCount = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("720.mp4")) respond(if (request.method == HttpMethod.Head) "" else "offline-video", headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
            else respond("", HttpStatusCode.NotFound)
        })
        try {
            val resolver = KodikResolver(http, capture { url ->
                captureCount++
                if (url.endsWith("/1")) throw IllegalStateException("Fixture failure")
                "https://cdn.test/${url.substringAfterLast('/')}/720.mp4?sig=secret"
            })
            val files = OfflineFiles(folder.absolutePath)
            val manager = DownloadManager(scope, resolver, http, files, assembler)
            manager.load(); manager.enqueueSeason(anime, dub)
            val done = awaitQueue(manager) { it.size == 3 && it.none { item -> item.active || item.status == DownloadStatus.Queued } }
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Completed, DownloadStatus.Completed), done.map { it.status })
            assertEquals(3, captureCount)
            assertEquals("offline-video", File(java.net.URI(manager.offlineUri(done[1])!!)).readText())
            assertFalse(File(folder, "queue.json").readText().contains("sig=secret"))
            http.close()
            val recovered = DownloadManager(scope, resolver, http, files, assembler)
            recovered.load()
            assertNotNull(recovered.offlineUri(recovered.state.value[1]))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); folder.deleteRecursively() }
    }

    @Test fun crashRecoveryFinishesExistingFileAndReportsMissingCompletedFile() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("animix-queue-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(MockEngine { error("Crash recovery must not access the network") })
        try {
            val files = OfflineFiles(folder.absolutePath)
            val queued = DownloadItem("existing", anime, dub.studio, dub.key, episodes[0], "720p", DownloadStatus.Downloading)
            val missing = queued.copy(id = "missing", episode = episodes[1], status = DownloadStatus.Completed, file = "missing.mp4")
            files.write("existing.mp4", "assembled-before-crash")
            files.write("queue.json", AppJson.encodeToString(listOf(queued, missing)))
            val manager = DownloadManager(scope, KodikResolver(http, capture { error("Must not resolve") }), http, files, assembler)
            manager.load()
            val recovered = awaitQueue(manager) { it.firstOrNull()?.status == DownloadStatus.Completed }
            assertEquals(DownloadStatus.Failed, recovered[1].status)
            assertNotNull(manager.offlineUri(recovered[0]))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); folder.deleteRecursively() }
    }

    @Test fun malformedQueueIsQuarantinedWithoutDeletingVideos() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("animix-queue-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(MockEngine { error("Unexpected request") })
        try {
            val files = OfflineFiles(folder.absolutePath)
            files.write("queue.json", "{truncated"); files.write("kept.mp4", "saved-video")
            val manager = DownloadManager(scope, KodikResolver(http), http, files, assembler)
            manager.load()
            assertTrue(manager.state.value.isEmpty()); assertNotNull(manager.notice.value)
            assertEquals("saved-video", files.read("kept.mp4"))
            assertTrue(folder.listFiles()!!.any { it.name.startsWith("queue.corrupt-") })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); folder.deleteRecursively() }
    }
}
