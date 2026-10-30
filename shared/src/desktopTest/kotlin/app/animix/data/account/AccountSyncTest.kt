package app.animix.data.account

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class AccountSyncTest {
    private val anime = Anime.placeholder(21).copy(title = "One Piece", episodes = 12)
    private class Cloud {
        val lock = Mutex()
        val accounts = mutableMapOf<String, SyncEnvelope>()
        var offline = false
        var loseAck = false
        var conflictOnce: ((SyncPayload) -> SyncPayload)? = null
        var duringPut: (() -> Unit)? = null
        var uploads = 0
        var puts = 0
        fun engine() = MockEngine { request -> lock.withLock {
            if (offline) throw java.io.IOException("offline")
            val id = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = request.url.encodedPath
            val current = accounts[id] ?: SyncEnvelope()
            when {
                path == "/v1/me/sync" && request.method == HttpMethod.Get -> respond(SyncJson.encodeToString(current), headers = json)
                path == "/v1/me/sync" -> {
                    val text = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    assertFalse("localFileName" in text)
                    val incoming = SyncJson.decodeFromString<SyncEnvelope>(text)
                    val mutation = conflictOnce; conflictOnce = null
                    val remote = if (mutation == null) current else SyncEnvelope(current.revision + 1, mutation(current.payload ?: SyncPayload())).also { accounts[id] = it }
                    if (incoming.revision != remote.revision) respond(SyncJson.encodeToString(remote), HttpStatusCode.Conflict, json)
                    else {
                        val saved = incoming.copy(revision = incoming.revision + 1)
                        accounts[id] = saved; puts++
                        duringPut?.also { duringPut = null }?.invoke()
                        if (loseAck) { loseAck = false; throw java.io.IOException("response lost") }
                        respond(SyncJson.encodeToString(saved), headers = json)
                    }
                }
                path.startsWith("/v1/me/media/") -> { uploads++; respond("""{"user":{"${path.substringAfterLast('/')}_url":"https://cdn.test/media/$uploads.png"}}""", headers = json) }
                path == "/v1/library" -> respond("""{"entries":[]}""", headers = json)
                path == "/v1/me/achievements" -> respond("""{"achievements":{}}""", headers = json)
                path == "/v1/me" -> respond("""{"user":{"display_name":"Cloud user","about":""}}""", headers = json)
                path == "/v1/auth/logout" -> respond("{}", headers = json)
                else -> error("Unexpected request: $path")
            }
        } }
    }
    private inner class Device(val cloud: Cloud, val user: String = "u1", val root: java.io.File = Files.createTempDirectory("animix-sync-").toFile()) {
        val disk = DiskStore(root.absolutePath)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(cloud.engine()) { expectSuccess = true }
        val library = Library(disk, scope)
        val settings = AppSettings(disk, scope)
        val awards = Achievements(disk, scope, library, settings)
        val account = AniMixAccount(http, disk, scope, "https://api.test")
        val sync = AccountSync(account, library, awards, settings, disk, scope, mediaBytes = { byteArrayOf(1, 2, 3) })
        suspend fun init() {
            disk.writeChecked("animix-account.json", """{"accessToken":"$user","refreshToken":"refresh-$user","user":{"id":"$user","name":"Cloud user"}}""")
            settings.load(); library.load(); awards.load(); account.load(); sync.start(automatic = false)
        }
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin(); http.close(); root.deleteRecursively() }
        fun watch(start: Long, end: Long) {
            library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(start, end)), end - start), 60_000, null, null, end >= 60_000)
        }
    }

    @Test fun twoDevicesMergeListsFavoritesPositionsProfileAndPreferences() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud); val b = Device(cloud)
        try {
            a.init(); b.init()
            a.library.setStatus(anime, ListStatus.Watching); a.library.saveEpisodePosition(anime, "1", 20_000)
            a.library.renameProfile("Name A"); a.sync.synchronize()
            assertNull(a.sync.state.value.error)
            b.sync.synchronize(); assertEquals("Name A", b.library.state.value.profile.name)
            assertEquals(20_000, b.library.state.value.entry(anime)?.episodePositions?.get("1"))
            a.library.toggleFavorite(anime); a.settings.update { it.copy(preferredQuality = "1080p", batterySaver = true) }
            b.library.setScore(anime, 9); b.library.updateProfile(b.library.state.value.profile.draft().copy(about = "Bio B"), emptySet())
            a.sync.synchronize(); b.sync.synchronize(); a.sync.synchronize()
            assertEquals(9, a.library.state.value.entry(anime)?.score)
            assertTrue(b.library.state.value.entry(anime)!!.favorite)
            assertEquals("Bio B", a.library.state.value.profile.about)
            assertEquals("1080p", b.settings.state.value.preferredQuality)
            assertFalse(b.settings.state.value.batterySaver, "Device power settings stay local")
            b.library.toggleFavorite(anime); b.library.saveEpisodePosition(anime, "1", 0); b.library.setScore(anime, null)
            b.sync.synchronize(); a.sync.synchronize()
            assertFalse(a.library.state.value.entry(anime)!!.favorite)
            assertNull(a.library.state.value.entry(anime)!!.score)
            assertTrue(a.library.state.value.entry(anime)!!.episodePositions.isEmpty())
            val count = cloud.puts; a.sync.synchronize(); b.sync.synchronize()
            assertEquals(count, cloud.puts, "No perpetual echo writes after convergence")
        } finally { a.close(); b.close() }
    }

    @Test fun offlineAndLostAcknowledgementDoNotDuplicatePlaybackOrPromoteHistoryToXp() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud); val b = Device(cloud)
        try {
            a.init(); b.init(); a.watch(0, 30_000)
            a.library.importTitles(listOf(ImportedTitle(anime.copy(id = 1535, title = "Imported"), ListStatus.Completed, 12)))
            cloud.offline = true; a.sync.synchronize(); assertNotNull(a.sync.state.value.error)
            cloud.offline = false; cloud.loseAck = true; a.sync.synchronize(); assertNotNull(a.sync.state.value.error)
            a.sync.synchronize(); b.sync.synchronize()
            assertEquals(30, b.library.state.value.watchDays.values.sum())
            assertEquals(30, b.library.state.value.confirmedWatchSeconds)
            assertTrue(b.library.state.value.entries.getValue("s1535").viewing.isEmpty())
            a.watch(30_000, 60_000); b.watch(0, 10_000)
            a.sync.synchronize(); b.sync.synchronize(); a.sync.synchronize()
            assertEquals(60, a.library.state.value.confirmedWatchSeconds, "Overlapping coverage counts once")
            assertEquals(70, a.library.state.value.watchDays.values.sum(), "Actual time on two devices is additive")
            val state = a.library.state.value
            a.sync.synchronize(); b.sync.synchronize(); a.sync.synchronize()
            assertEquals(state.watchDays, a.library.state.value.watchDays)
            assertEquals(state.entries.getValue("s21").viewing, a.library.state.value.entries.getValue("s21").viewing)
        } finally { a.close(); b.close() }
    }

    @Test fun conflictRetryPreservesRemoteFieldsAndEditsDuringTransfer() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud)
        try {
            a.init(); a.library.setStatus(anime, ListStatus.Watching); a.watch(0, 10_000); a.sync.synchronize()
            a.library.setScore(anime, 8)
            cloud.conflictOnce = { it.copy(library = it.library.copy(profile = it.library.profile.copy(about = "Other device"))) }
            cloud.duringPut = { a.library.toggleFavorite(anime); a.watch(10_000, 15_000) }
            a.sync.synchronize(); assertNull(a.sync.state.value.error)
            assertEquals("Other device", a.library.state.value.profile.about)
            assertTrue(a.library.state.value.entry(anime)!!.favorite)
            a.sync.synchronize()
            val remote = cloud.accounts.getValue("u1").payload!!
            assertEquals(8, remote.library.entry(anime)?.score)
            assertTrue(remote.library.entry(anime)!!.favorite)
            assertEquals(15, remote.library.watchDays.values.sum())
        } finally { a.close() }
    }

    @Test fun imagesUploadOnceAndNewDeviceGetsUrlsWithoutForeignLocalFiles() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud); val b = Device(cloud)
        try {
            a.init(); b.init()
            a.library.updateProfile(a.library.state.value.profile.draft().copy(avatar = ProfileMedia(localFileName = "avatar.png"), banner = ProfileMedia(localFileName = "banner.png")), emptySet())
            a.sync.synchronize(); assertNull(a.sync.state.value.error); assertEquals(2, cloud.uploads)
            a.sync.synchronize(); assertEquals(2, cloud.uploads)
            b.sync.synchronize()
            assertNotNull(b.library.state.value.profile.avatar?.url); assertNull(b.library.state.value.profile.avatar?.localFileName)
            assertEquals("avatar.png", a.library.state.value.profile.avatar?.localFileName)
            a.library.updateProfile(a.library.state.value.profile.draft().copy(avatar = null), emptySet())
            a.sync.synchronize(); b.sync.synchronize(); assertNull(b.library.state.value.profile.avatar)
        } finally { a.close(); b.close() }
    }

    @Test fun changingAccountsDoesNotUploadPreviousUsersCollection() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud)
        try {
            a.init(); a.library.setStatus(anime, ListStatus.Completed); a.sync.synchronize(); a.library.flush()
            // Simulate another account after restart, sharing this installation's storage.
            a.scope.coroutineContext[Job]!!.cancelAndJoin()
            a.disk.writeChecked("animix-account.json", """{"accessToken":"u2","refreshToken":"r2","user":{"id":"u2","name":"Second"}}""")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val lib = Library(a.disk, scope).apply { load() }; val prefs = AppSettings(a.disk, scope).apply { load() }
                val awards = Achievements(a.disk, scope, lib, prefs).apply { load() }
                val account = AniMixAccount(a.http, a.disk, scope, "https://api.test").apply { load() }
                val sync = AccountSync(account, lib, awards, prefs, a.disk, scope)
                sync.start(automatic = false); sync.synchronize()
                assertNull(sync.state.value.error)
                assertTrue(cloud.accounts.getValue("u2").payload!!.library.entries.isEmpty())
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        } finally { a.close() }
    }

    @Test fun restartAfterLostServerResponseRestoresPendingChangesWithoutAddingTimeAgain() = runBlocking<Unit> {
        val cloud = Cloud(); val a = Device(cloud); var restarted: Device? = null
        try {
            a.init(); a.watch(0, 40_000); a.library.setScore(anime, 7)
            cloud.loseAck = true; a.sync.synchronize(); assertNotNull(a.sync.state.value.error)
            a.library.flush(); a.awards.flush(); a.settings.flush()
            a.scope.coroutineContext[Job]!!.cancelAndJoin(); a.http.close()
            val b = Device(cloud, root = a.root); restarted = b
            b.init(); b.sync.synchronize(); assertNull(b.sync.state.value.error)
            assertEquals(40, b.library.state.value.watchDays.values.sum())
            assertEquals(40, b.library.state.value.confirmedWatchSeconds)
            assertEquals(7, b.library.state.value.entry(anime)?.score)
            b.sync.synchronize(); assertEquals(40, b.library.state.value.watchDays.values.sum())
        } finally { restarted?.close(); a.close() }
    }

    @Test fun interruptedApplicationRecoversBeforeAndAfterLibraryCommit() = runBlocking<Unit> {
        for (alreadyApplied in listOf(false, true)) {
            val cloud = Cloud(); val a = Device(cloud); var restarted: Device? = null
            try {
                a.init(); a.watch(0, 10_000); a.sync.synchronize()
                a.library.flush(); a.awards.flush(); a.settings.flush()
                a.scope.coroutineContext[Job]!!.cancelAndJoin(); a.http.close()
                val journalFile = a.root.listFiles()!!.single { it.name.startsWith("sync-journal-") }
                val before = a.library.state.value
                val initial = cloud.accounts.getValue("u1").payload!!
                val extra = SyncUsage(before.watchDays.mapValues { 40 })
                val payload = initial.copy(library = initial.library.copy(profile = initial.library.profile.copy(name = "Remote name"),
                    watchDays = before.watchDays.mapValues { 50 }), usage = initial.usage + ("other-device" to extra))
                cloud.accounts["u1"] = SyncEnvelope(2, payload)
                val after = payload.library
                val journal = SyncJson.parseToJsonElement(journalFile.readText()).jsonObject
                journalFile.writeText(JsonObject(journal + mapOf(
                    "base" to SyncJson.encodeToJsonElement(payload),
                    "sampledUsage" to SyncJson.encodeToJsonElement(SyncUsage(after.watchDays, after.usage)),
                    "pendingApply" to buildJsonObject {
                        put("before", SyncJson.encodeToJsonElement(before)); put("after", SyncJson.encodeToJsonElement(after))
                        put("preferences", payload.preferences); put("receipt", "crash-receipt")
                    },
                )).toString())
                if (alreadyApplied) a.disk.writeChecked("library.json", SyncJson.encodeToString(after.copy(syncReceipt = "crash-receipt")))
                val b = Device(cloud, root = a.root); restarted = b
                b.init(); b.sync.synchronize(); assertNull(b.sync.state.value.error)
                assertEquals("Remote name", b.library.state.value.profile.name)
                assertEquals(50, b.library.state.value.watchDays.values.sum())
                b.sync.synchronize(); assertEquals(50, b.library.state.value.watchDays.values.sum())
            } finally { restarted?.close(); a.close() }
        }
    }
}
