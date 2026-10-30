package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class AchievementReliabilityTest {
    private fun disk() = DiskStore("build/test-ach-reliability-${Random.nextInt(1_000_000)}")
    private fun anime(id: Int) = Anime.placeholder(id).copy(title = "Title $id", episodes = 1)
    private fun Achievements.award(id: String) = progress.value.first { it.achievement.id == id }

    @Test fun rapidListEditsRetainIntermediateMilestonesAndTransitions() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope))
        // Happens before the achievement loader/subscriber gets a turn.
        repeat(10) { library.toggleFavorite(anime(it)) }
        repeat(10) { library.toggleFavorite(anime(it)) }
        repeat(5) { library.setStatus(anime(it), ListStatus.OnHold) }
        repeat(5) { library.setStatus(anime(it), ListStatus.Watching) }
        library.renameProfile("Viewer")
        library.renameProfile("Гость")
        rewards.load()
        rewards.flush()
        for (id in listOf("first_favorite", "ten_favorites", "five_on_hold", "return_from_hold", "custom_profile_name")) {
            assertTrue(rewards.award(id).earned, id)
        }
        assertTrue(library.state.value.favorites.isEmpty())
    }

    @Test fun startupActionsMergeWithSavedAwardsAndConcurrentLoadRunsOnce() = runTest {
        val disk = disk()
        disk.write("achievements.json", AppJson.encodeToString(AchievementState.serializer(),
            AchievementState(unlocked = mapOf("first_favorite" to 123L), counters = mapOf("reviews_published" to 4))))
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope))
        rewards.themeChosen("Dawn")
        rewards.record("reviews_published")
        coroutineScope { repeat(8) { launch { rewards.load() } } }
        rewards.flush()
        val persisted = AppJson.decodeFromString<AchievementState>(disk.read("achievements.json")!!)
        assertEquals(5L, persisted.counters["reviews_published"])
        assertEquals(123L, rewards.award("first_favorite").unlockedAt)
        assertTrue(rewards.award("light_theme_selected").earned)
    }

    @Test fun immediateFlushPersistsUnevaluatedActionsAndRestartCannotRevokeAwards() = runTest {
        val disk = disk()
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val library = Library(disk, firstScope).apply { load() }
        val rewards = Achievements(disk, firstScope, library, AppSettings(disk, firstScope)).apply { load() }
        library.toggleFavorite(anime(1))
        library.toggleFavorite(anime(1))
        rewards.settingEnabled("skipEndings")
        rewards.flush() // No runCurrent, delay or UI frame to finish evaluation first.
        library.flush()
        val date = rewards.award("first_favorite").unlockedAt
        assertNotNull(date)
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val restoredLibrary = Library(disk, backgroundScope).apply { load() }
        val restored = Achievements(disk, backgroundScope, restoredLibrary, AppSettings(disk, backgroundScope)).apply { load() }
        restored.flush()
        assertEquals(date, restored.award("first_favorite").unlockedAt)
        assertTrue(restored.award("skip_endings_enabled").earned)
    }

    @Test fun libraryReloadCannotRollbackUnsavedChanges() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        library.flush()
        library.renameProfile("Recent name")
        library.load()
        assertEquals("Recent name", library.state.value.profile.name)
    }

    @Test fun concurrentCountersAndLibraryObservationsLoseNoActions() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rewards = Achievements(disk, workerScope, library, AppSettings(disk, workerScope)).apply { load() }
            coroutineScope {
                repeat(8) { producer -> launch(Dispatchers.Default) {
                    repeat(40) { i ->
                        rewards.record("reviews_published")
                        rewards.openedFromSearch("$producer-$i")
                        library.toggleFavorite(anime(producer * 40 + i))
                    }
                } }
            }
            rewards.flush()
            val persisted = AppJson.decodeFromString<AchievementState>(disk.read("achievements.json")!!)
            assertEquals(320L, persisted.counters["reviews_published"])
            assertEquals(320, persisted.sets["search"]?.size)
            assertTrue(rewards.award("first_review").earned)
            assertTrue(rewards.award("ten_favorites").earned)
        } finally { workerScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun completedDownloadsAreRecoveredOnceAcrossRepeatedReports() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        repeat(3) { repeat(10) { rewards.downloadCompleted("s1", "${it + 1}") } }
        rewards.flush()
        assertEquals(10, rewards.award("download_hoarder").current)
        assertTrue(rewards.award("first_download").earned)
        assertTrue(rewards.award("download_hoarder").earned)
        val first = rewards.award("first_download").unlockedAt
        rewards.downloadCompleted("s1", "1")
        rewards.flush()
        assertEquals(first, rewards.award("first_download").unlockedAt)
    }
    @Test fun damagedPrimaryRestoresEarnedAndHistoricalAwardsFromBackup() = runTest {
        val disk = disk()
        val scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val library = Library(disk, scope).apply { load() }
        val rewards = Achievements(disk, scope, library, AppSettings(disk, scope)).apply { load() }
        rewards.themeChosen("Dusk")
        library.importTitles(listOf(ImportedTitle(anime(16498), ListStatus.Completed)))
        rewards.flush()
        val earnedAt = rewards.award("dark_theme_selected").unlockedAt
        val historyAt = rewards.award("attack_on_titan").historyUnlockedAt
        assertNotNull(earnedAt)
        assertNotNull(historyAt)
        scope.coroutineContext[Job]!!.cancelAndJoin()
        disk.write("achievements.json", "{truncated")
        val emptyLibrary = Library(disk, backgroundScope)
        val restored = Achievements(disk, backgroundScope, emptyLibrary, AppSettings(disk, backgroundScope)).apply { load() }
        restored.flush()
        assertEquals(earnedAt, restored.award("dark_theme_selected").unlockedAt)
        assertEquals(historyAt, restored.award("attack_on_titan").historyUnlockedAt)
        assertTrue(restored.award("attack_on_titan").fromHistory)
    }

    @Test fun unreadableAwardsAreNotOverwrittenWithEmptyState() = runTest {
        val disk = disk()
        disk.write("achievements.json", "{damaged")
        val library = Library(disk, backgroundScope)
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope))
        rewards.themeChosen("Dusk")
        assertFails { rewards.load() }
        assertFails { rewards.flush() }
        assertEquals("{damaged", disk.read("achievements.json"))
    }

}
