package app.animix.android

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.animix.core.DiskStore
import app.animix.core.Settings
import app.animix.core.ThemeMode
import app.animix.data.library.Library
import app.animix.data.library.verifiedEpisodes
import app.animix.data.playback.NativePlayer
import app.animix.data.playback.ResolvedStream
import app.animix.data.playback.createNativePlayer
import app.animix.model.Anime
import app.animix.model.Episode
import app.animix.ui.player.AniMixPlayer
import app.animix.ui.theme.AniMixTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real ExoPlayer clock must reach the same reward path as ordinary playback. */
@RunWith(AndroidJUnit4::class)
class PlaybackCreditTest {
    @Test fun seekingToCreditsDoesNotEarnAnEpisodeButRealPlaybackAfterwardsDoes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "playback-credit-test").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val library = Library(DiskStore(root.absolutePath), scope).apply { load() }
        val anime = Anime.placeholder(999_991).copy(title = "Проверка просмотра", episodes = 1)
        val episode = Episode("1", null, "", 12, null, null)
        val video = File(root, "fixture.mp4")
        instrumentation.context.assets.open("output.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        var native: NativePlayer? = null
        var activity: ActivityScenario<MainActivity>? = null
        try {
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { host ->
                host.setShowWhenLocked(true)
                host.setTurnScreenOn(true)
                native = createNativePlayer().also { it.setVolume(0f) }
                host.setContent {
                    AniMixTheme(ThemeMode.Dusk) {
                        AniMixPlayer(
                            ResolvedStream(mapOf("720p" to android.net.Uri.fromFile(video).toString()), emptyMap()),
                            episode, Settings(autoNextEpisode = false, autoNextAsked = true), anime.title,
                            playerFactory = { native!! },
                            onPlayback = { update -> library.recordPlayback(anime, "1", update.credit,
                                update.durationMs, update.opening, update.ending, update.reachedEnd) },
                        )
                    }
                }
            }
            val backend = native!!
            withTimeout(15_000) { backend.state.first { it.positionMs >= 1_000 || it.error != null } }
            assertNull(backend.state.value.error)
            instrumentation.runOnMainSync {
                backend.pause()
                assertFalse("Pause must respond immediately", backend.state.value.playRequested)
                backend.seekTo(11_000)
                assertEquals("Seek must immediately update the timeline", 11_000L, backend.state.value.positionMs)
                backend.play()
            }
            withTimeout(5_000) { backend.state.first { it.ended } }
            delay(250)
            assertEquals(0, library.state.value.entry(anime)?.verifiedEpisodes ?: 0)
            instrumentation.runOnMainSync { backend.seekTo(0); backend.play() }
            withTimeout(20_000) { library.state.first { it.entry(anime)?.verifiedEpisodes == 1 } }
            assertNull(backend.state.value.error)
            assertEquals(1, library.state.value.entry(anime)?.verifiedEpisodes)
        } finally {
            activity?.close()
            instrumentation.runOnMainSync { native?.release() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
