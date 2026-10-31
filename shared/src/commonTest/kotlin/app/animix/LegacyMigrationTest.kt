package app.animix

import app.animix.core.*
import app.animix.data.account.*
import app.animix.data.achievements.*
import app.animix.data.legacy.*
import app.animix.data.library.*
import app.animix.model.Anime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
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

    @Test fun statusesKeptByAwardsFillListsWithoutCache() {
        // After sign-out 2.x drops its account cache, but its award tracker still knows every status.
        val snapshot = LegacyFormat.parsePreferences("""
            {
              "flutter.local_achievements_v1_status_16498": "completed",
              "flutter.local_achievements_v1_status_5114": "on_hold",
              "flutter.local_achievements_v1_status_x": "completed",
              "flutter.local_library_v1": "[{\"shikimori_id\":5114,\"status\":\"dropped\",\"score\":7,\"episodes_watched\":4,\"updated_at\":200}]"
            }
        """.trimIndent())
        assertEquals(setOf(16498, 5114), snapshot.entries.map { it.shikimoriId }.toSet())
        assertEquals(ListStatus.Completed, snapshot.entries.first { it.shikimoriId == 16498 }.status)
        // A real list record wins over the tracker.
        assertEquals(ListStatus.Dropped, snapshot.entries.first { it.shikimoriId == 5114 }.status)
    }

    @Test fun localMigrationBringsListsHoursAndAwardsWithXpOnce() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val migration = LegacyMigration(backgroundScope, library, rewards, account = null,
            titles = { ids -> ids.map { anime(it, if (it == 16498) 25 else 12) } }, localText = { preferences })
        repeat(2) { migration.apply(LegacyFormat.parsePreferences(preferences)) }
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

    @Test fun localDataGoesIntoTheOnlineAccountAndEarnsTheAccountAwardOnce() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/v1/auth/google/exchange" -> respond("""{"access_token":"a","refresh_token":"r","user":{"id":"u1","display_name":"Ilya","created_at":"2025-01-01T00:00:00.000Z"}}""", headers = json)
                else -> respond("{}", headers = json)
            }
        }
        val account = AniMixAccount(HttpClient(engine), disk, backgroundScope, baseUrl = "https://animix.test").apply { load() }
        // A callback nobody asked for is ignored.
        account.completeSignIn("${AniMixServer.CALLBACK}?ticket=t&state=s")
        testScheduler.advanceUntilIdle()
        assertTrue(requests.isEmpty())

        // Desktop: the browser comes back to a loopback listener; another address is not accepted.
        val loopback = "http://127.0.0.1:53111/oauth/callback"
        assertTrue("return_uri=http%3A%2F%2F127.0.0.1%3A53111%2Foauth%2Fcallback" in account.signInUrl(loopback))
        account.completeSignIn("${AniMixServer.CALLBACK}?ticket=t&state=s")
        testScheduler.advanceUntilIdle()
        assertTrue(requests.isEmpty())
        account.completeSignIn("$loopback?ticket=t&state=s")
        settle { account.state.value.user != null && !account.state.value.busy }
        assertEquals("Ilya", account.state.value.user?.name)
        assertEquals("signin", account.state.value.path)

        val migration = LegacyMigration(backgroundScope, library, rewards, account,
            titles = { ids -> ids.map { anime(it, if (it == 16498) 25 else 12) } }, localText = { preferences })
        migration.apply(LegacyFormat.parsePreferences(preferences))
        assertTrue("PUT /v1/library/16498" in requests && "PUT /v1/library/5114" in requests, "$requests")
        assertFalse("PUT /v1/library/38000" in requests, "a title without a list status is history only")
        assertTrue("PUT /v1/me/achievements" in requests)
        val award = rewards.progress.value.first { it.achievement.id == "legacy_lists" }
        assertTrue(award.earned)
        assertEquals("signin", award.variant)
        assertEquals("Ilya", library.state.value.legacyMigration?.accountName)

        // Migrating again cannot earn it twice or switch its path.
        migration.apply(LegacyFormat.parsePreferences(preferences))
        assertEquals(award.unlockedAt, rewards.progress.value.first { it.achievement.id == "legacy_lists" }.unlockedAt)

        // The session survives a restart and ends with sign-out.
        val restored = AniMixAccount(HttpClient(engine), disk, backgroundScope, baseUrl = "https://animix.test").apply { load() }
        assertEquals("u1", restored.state.value.user?.id)
        restored.signOut()
        settle { restored.state.value.user == null }
        assertNull(AniMixAccount(HttpClient(engine), disk, backgroundScope, baseUrl = "https://animix.test").apply { load() }.state.value.user)
    }

    /** Disk writes run on real threads; wait for the state itself, not only the test scheduler. */
    private suspend fun kotlinx.coroutines.test.TestScope.settle(done: () -> Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) {
                while (true) {
                    testScheduler.advanceUntilIdle()
                    if (done()) break
                    kotlinx.coroutines.delay(20)
                }
            }
        }
    }
}
