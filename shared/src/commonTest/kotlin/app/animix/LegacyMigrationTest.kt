package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.legacy.*
import app.animix.data.library.*
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class LegacyMigrationTest {
    private fun disk() = DiskStore("build/test-legacy-${Random.nextInt(1_000_000)}")
    private fun anime(id: Int, episodes: Int = 12) = Anime.placeholder(id).copy(title = "Тайтл $id", episodes = episodes)

    // The shape of AniMix 2.x shared_preferences.json on Windows (values are JSON strings or primitives).
    private val preferences = """
        {
          "flutter.animix_library_cache_v2": "[{\"shikimori_id\":16498,\"status\":\"completed\",\"score\":9,\"episodes_watched\":25,\"updated_at\":100},{\"shikimori_id\":5114,\"status\":\"watching\",\"score\":0,\"episodes_watched\":3,\"updated_at\":100}]",
          "flutter.local_library_v1": "[{\"shikimori_id\":5114,\"status\":\"dropped\",\"score\":0,\"episodes_watched\":4,\"updated_at\":200}]",
          "flutter.local_achievements_v1_download": 1790000000000,
          "flutter.local_achievements_v1_titan_wall": 1790000000001,
          "flutter.local_achievements_v1_insane": 1790000000003,
          "flutter.local_achievements_v1_friend": 1790000000002,
          "flutter.local_achievements_v1_status_16498": "completed",
          "flutter.local_achievements_v1_library_ids": ["16498"],
          "flutter.usage_seconds_v1_2026-09-01": 3600,
          "flutter.usage_seconds_v1_2026-09-02": 999999,
          "flutter.watched_eps_v2_38000": ["1", "2", "4"],
          "flutter.watched_episodes_38000": ["1"],
          "flutter.local_profile_v1": "{\"id\":\"local-1\",\"display_name\":\"Илья\"}",
          "flutter.something_else": 5
        }
    """.trimIndent()

    /** Disk writes run on real threads; wait for the migration itself, not only the test scheduler. */
    private suspend fun kotlinx.coroutines.test.TestScope.finished(migration: LegacyMigration) {
        testScheduler.advanceUntilIdle()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) { migration.state.first { !it.busy } }
        }
        testScheduler.advanceUntilIdle()
    }

    @Test fun preferencesAreReadDefensively() {
        val snapshot = LegacyFormat.parsePreferences(preferences)
        assertEquals(setOf(16498, 5114), snapshot.entries.map { it.shikimoriId }.toSet())
        // The newer device-only record wins over the account cache.
        assertEquals(ListStatus.Dropped, snapshot.entries.first { it.shikimoriId == 5114 }.status)
        assertEquals(setOf("download", "titan_wall", "insane", "friend"), snapshot.achievements.keys)
        assertEquals(3600L + 86_400L, snapshot.watchSeconds)
        assertEquals(mapOf(38000 to 2), snapshot.watchedEpisodes)
        assertEquals("Илья", snapshot.profileName)
        assertEquals(mapOf("first_download" to 1790000000000, "attack_on_titan" to 1790000000001, "ten_hours" to 1790000000003),
            LegacyFormat.mapAchievements(snapshot.achievements))
        assertTrue(LegacyFormat.parsePreferences("not json").isEmpty)
    }

    @Test fun localMigrationBringsListsHoursAndAwardsWithXpOnce() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val migration = LegacyMigration(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }), backgroundScope, library, rewards,
            titles = { ids -> ids.map { anime(it, if (it == 16498) 25 else 12) } }, localText = { preferences })
        repeat(2) { migration.apply(LegacyFormat.parsePreferences(preferences), "device", null, null) }
        val data = library.state.value
        assertEquals(3, data.entries.size)
        assertEquals(ListStatus.Completed, data.entries.getValue("s16498").status)
        assertEquals(2, data.entries.getValue("s38000").importedViewing?.episodesWatched)
        assertEquals("Илья", data.profile.name)
        assertEquals(3600L + 86_400L, data.legacyWatchSeconds)
        assertEquals(25, data.stats().totalMinutes / 60)
        val earned = rewards.progress.value.filter { it.earned }.map { it.achievement.id }.toSet()
        assertTrue("first_download" in earned && "attack_on_titan" in earned && "ten_hours" in earned, "$earned")
        assertFalse("legacy_lists" in earned, "the account award needs a sign-in or registration")
        assertEquals(1790000000000, rewards.progress.value.first { it.achievement.id == "first_download" }.unlockedAt)
        // Migrated awards carry the usual 50 XP each; migrating again adds nothing.
        assertEquals(earned.size * 50L, data.level(earned.size).xp)
        assertEquals(3600L + 86_400L, data.legacyMigration?.watchSeconds)
    }

    @Test fun accountSignInMigratesAndGrantsTheAccountAwardOnce() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/v1/auth/google/exchange" -> respond("""{"access_token":"a","refresh_token":"r","user":{"display_name":"Ilya","created_at":"2025-01-01T00:00:00.000Z"}}""", headers = json)
                "/v1/library" -> respond("""{"entries":[{"shikimori_id":38000,"status":"completed","score":8,"episodes_watched":26,"updated_at":1}]}""", headers = json)
                "/v1/usage" -> respond("""{"stats":{"watch_seconds":7200,"activity":[{"created_at":1790000000,"action":"watch_seconds","anime_id":1,"metadata":{"seconds":3600}},{"created_at":1790000100,"action":"watch_seconds","anime_id":1,"metadata":{"seconds":3600}}]}}""", headers = json)
                "/v1/me/achievements" -> respond("""{"achievements":{"demon_blade":1790000000000,"owl":1}}""", headers = json)
                else -> respond("{}", headers = json)
            }
        }
        val migration = LegacyMigration(HttpClient(engine), backgroundScope, library, rewards,
            titles = { ids -> ids.map { anime(it, 26) } }, localText = { null })
        // A callback nobody asked for is ignored.
        migration.completeSignIn("${LegacyMigration.CALLBACK}?ticket=t&state=s")
        testScheduler.advanceUntilIdle()
        assertTrue(requests.isEmpty())

        migration.signInUrl()
        migration.completeSignIn("${LegacyMigration.CALLBACK}?ticket=t&state=s")
        finished(migration)
        rewards.flush()
        assertNull(migration.state.value.error, migration.state.value.error)
        assertTrue("POST /v1/auth/logout" in requests, "the 2.x session is closed after the migration")
        assertEquals(ListStatus.Completed, library.state.value.entries.getValue("s38000").status)
        assertEquals(7200, library.state.value.legacyWatchSeconds)
        val account = rewards.progress.value.first { it.achievement.id == "legacy_lists" }
        assertTrue(account.earned)
        assertEquals("signin", account.variant)
        assertTrue(rewards.progress.value.first { it.achievement.id == "demon_slayer" }.earned)

        // Signing in again cannot earn the award a second time or switch its path.
        val first = account.unlockedAt
        migration.signInUrl()
        migration.completeSignIn("${LegacyMigration.CALLBACK}?ticket=t2&state=s2")
        finished(migration)
        rewards.flush()
        val again = rewards.progress.value.first { it.achievement.id == "legacy_lists" }
        assertEquals(first, again.unlockedAt)
        assertEquals("signin", again.variant)
    }
}
