package app.animix.data.account

import app.animix.core.*
import app.animix.data.createHttpClient
import app.animix.data.library.*
import app.animix.data.achievements.Achievements
import app.animix.model.Anime
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*

/** Opt-in, two real production HTTP clients against isolated ephemeral VPS sessions. */
class AccountSyncLiveTest {
    @Test fun twoInstallationsConvergeThroughTheRealAccountServer() = runBlocking<Unit> {
        val raw = System.getenv("ANIMIX_SYNC_LIVE_FIXTURE")
        assumeTrue("Requires temporary server fixture (never use a real user's session)", raw != null)
        val fixture = AppJson.parseToJsonElement(raw!!).jsonObject
        val id = fixture.getValue("id").jsonPrimitive.content
        val tokens = fixture.getValue("tokens").jsonArray
        val scopes = mutableListOf<CoroutineScope>(); val clients = mutableListOf<io.ktor.client.HttpClient>(); val roots = mutableListOf<java.io.File>()
        val anime = Anime.placeholder(21).copy(title = "Sync fixture", episodes = 12)
        data class Installation(val library: Library, val sync: AccountSync)
        suspend fun installation(index: Int, before: suspend (Library) -> Unit = {}): Installation {
            val root = Files.createTempDirectory("animix-live-sync-").toFile().also { roots += it }
            val disk = DiskStore(root.absolutePath)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            val http = createHttpClient().also { clients += it }
            val token = tokens[index].jsonObject
            disk.writeChecked("animix-account.json", buildJsonObject {
                put("accessToken", token.getValue("accessToken")); put("refreshToken", token.getValue("refreshToken"))
                put("user", buildJsonObject { put("id", id); put("name", "Client smoke") })
            }.toString())
            val library = Library(disk, scope).apply { load() }
            before(library)
            val settings = AppSettings(disk, scope).apply { load() }
            val awards = Achievements(disk, scope, library, settings).apply { load() }
            val account = AniMixAccount(http, disk, scope).apply { load() }
            val sync = AccountSync(account, library, awards, settings, disk, scope)
            sync.start(automatic = false)
            return Installation(library, sync)
        }
        suspend fun sync(device: Installation) { device.sync.synchronize(); assertNull(device.sync.state.value.error, "Actual server sync must succeed") }
        try {
            val other = Anime.placeholder(5114).copy(title = "Local before sign-in", episodes = 64)
            val a = installation(0)
            // The second device already has its own list before it signs in: it must merge, not replace.
            val b = installation(1) { it.setStatus(other, ListStatus.Planned) }
            a.library.setStatus(anime, ListStatus.Watching)
            a.library.saveEpisodePosition(anime, "1", 20_000)
            a.library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(0, 20_000)), 20_000), 60_000, null, null, false)
            a.library.renameProfile("Sync fixture profile")
            sync(a); sync(b)
            assertEquals(20_000, b.library.state.value.entry(anime)?.episodePositions?.get("1"))
            assertEquals("Sync fixture profile", b.library.state.value.profile.name, "the online profile wins over an untouched local one")
            assertEquals(ListStatus.Planned, b.library.state.value.entry(other)?.status, "local titles survive the first sign-in")
            sync(a)
            assertEquals(ListStatus.Planned, a.library.state.value.entry(other)?.status, "and reach the other device")
            assertEquals(2, a.library.state.value.entries.size, "no duplicate entries")
            assertEquals(20, b.library.state.value.confirmedWatchSeconds)
            b.library.setScore(anime, 8)
            a.library.toggleFavorite(anime)
            sync(a); sync(b); sync(a)
            assertEquals(8, a.library.state.value.entry(anime)?.score)
            assertTrue(b.library.state.value.entry(anime)!!.favorite)
            assertEquals(20, a.library.state.value.watchDays.values.sum())
            repeat(3) { sync(a); sync(b) }
            assertEquals(20, a.library.state.value.watchDays.values.sum(), "repeated transfers never add hours")
            assertEquals(20, b.library.state.value.watchDays.values.sum())
            assertEquals(20, b.library.state.value.confirmedWatchSeconds)
            println("Account live smoke: two installations converged; credentials and personal data omitted")
        } finally {
            scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
            clients.forEach { it.close() }; roots.forEach { it.deleteRecursively() }
        }
    }
}
