package app.animix.android

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.animix.data.downloads.*
import app.animix.data.playback.*
import app.animix.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadServiceTest {
    @Test fun foregroundQueueSurvivesActivityStopAndStopsItsServiceWhenPaused() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "download-service-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(MockEngine { error("This test must not issue HTTP requests") })
        val capture = object : StreamCapture { override fun candidates(iframeUrl: String): Flow<StreamCandidate> = flow { awaitCancellation() } }
        val manager = DownloadManager(scope, KodikResolver(http, capture), http, OfflineFiles(folder.absolutePath))
        val activity = ActivityScenario.launch(MainActivity::class.java)
        @Suppress("DEPRECATION")
        fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == "app.animix.data.downloads.OfflineDownloadService" && it.foreground }
        try {
            manager.load()
            val episode = Episode("1", null, "https://player.test/episode", 12, null, null)
            manager.enqueueSeason(Anime.placeholder(987), Dubbing("Test", "Kodik", listOf(episode)))
            withTimeout(5000) { while (!serviceRunning()) delay(100) }
            activity.moveToState(Lifecycle.State.CREATED)
            delay(500)
            assertTrue(serviceRunning())
            assertEquals(DownloadStatus.Resolving, manager.state.value.single().status)
            manager.pauseAll()
            withTimeout(5000) { while (serviceRunning()) delay(100) }
            assertEquals(DownloadStatus.Paused, manager.state.value.single().status)
        } finally {
            manager.pauseAll(); scope.coroutineContext[Job]!!.cancelAndJoin()
            http.close(); activity.close(); folder.deleteRecursively()
        }
    }
}
