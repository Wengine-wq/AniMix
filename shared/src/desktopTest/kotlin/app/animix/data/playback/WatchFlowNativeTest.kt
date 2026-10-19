package app.animix.data.playback

import app.animix.core.DiskStore
import app.animix.data.downloads.localMediaUri
import app.animix.data.watchflow.*
import app.animix.model.Anime
import app.animix.model.Episode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

class WatchFlowNativeTest {
    @Test fun sessionDeadlinePausesTheRealDecoderWithoutAComposeFrame() = runBlocking<Unit> {
        assumeTrue("Set ANIMIX_MEDIA_TEST=1 and generate the synthetic fixture", System.getenv("ANIMIX_MEDIA_TEST") == "1")
        val fixture = File(System.getenv("ANIMIX_MEDIA_FIXTURE") ?: "../build/test-media", "output.mp4").canonicalFile
        check(fixture.isFile)
        val folder = Files.createTempDirectory("animix-flow-native-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong()
        val flow = WatchFlow(DiskStore(folder.absolutePath), scope, monotonicClock = clock::get)
        val player = DesktopNativePlayer()
        try {
            flow.load()
            flow.options(FlowOptions(minutes = 1))
            flow.add(listOf(FlowEpisode(Anime.placeholder(1), "local", "Local", Episode("1", null, "", 12, null, null), 12_000)))
            flow.start()
            flow.bind(player)
            player.setVolume(0f)
            player.load(localMediaUri(fixture.absolutePath), emptyMap())
            withTimeout(20_000) { player.state.first { it.playing && it.positionMs >= 500 || it.error != null } }
            assertNull(player.state.value.error)
            clock.set(60_000)
            flow.tick()
            withTimeout(5_000) { player.state.first { !it.playing && !it.playRequested && it.ready } }
            assertEquals(FlowFinish.TimeLimit, flow.state.value.finish)
            val position = player.state.value.positionMs
            delay(600)
            assertTrue(kotlin.math.abs(player.state.value.positionMs - position) < 300)
        } finally {
            player.release()
            player.awaitReleased()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            folder.deleteRecursively()
        }
    }
}
