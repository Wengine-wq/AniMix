package app.animix

import app.animix.core.AppJson
import app.animix.core.AppSettings
import app.animix.core.DiskStore
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class HistoricalAchievementsTest {
    private fun store() = DiskStore("build/test-history-${Random.nextInt(1_000_000)}")
    private fun anime(id: Int = 16498, episodes: Int = 12) = Anime.placeholder(id).copy(title = "Тайтл $id", episodes = episodes)
    private fun List<AchievementProgress>.award(id: String) = first { it.achievement.id == id }
    private fun Library.complete(title: Anime, episodes: IntRange = 1..title.episodes) {
        for (episode in episodes) recordPlayback(title, "$episode",
            PlaybackCredit(listOf(ViewedRange(0, 100_000)), 100_000), 100_000, null, null, true)
    }

    @Test fun importingCompletedTitlesRecognizesAwardsWithoutPlaybackXpOrDatedHabits() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        library.importTitles((1..10).map { ImportedTitle(anime(if (it == 1) 16498 else it), ListStatus.Completed) })
        testScheduler.runCurrent()

        val awards = achievements.progress.value
        for (id in listOf("attack_on_titan", "first_completed", "ten_completed")) {
            assertTrue(awards.award(id).unlocked, id)
            assertTrue(awards.award(id).fromHistory, id)
            assertFalse(awards.award(id).earned, id)
        }
        for (id in listOf("ten_hours", "marathon", "seven_day_streak", "monthly_three_completed", "planned_to_completed")) {
            assertEquals(0, awards.award(id).current, id)
            assertFalse(awards.award(id).unlocked, id)
        }
        assertTrue(library.state.value.watchDays.isEmpty())
        assertTrue(library.state.value.entries.values.all { it.viewing.isEmpty() })
        assertEquals(0, library.state.value.level(awards.count { it.earned }).xp)
        disk.clear()
    }

    @Test fun partialHistoryAndNewPlaybackCombineWithoutCountingEpisodesTwice() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val title = anime(52991, 28)
        library.importTitles(listOf(ImportedTitle(title, ListStatus.Watching, episodesWatched = 8)))
        library.complete(title, 8..9)
        testScheduler.runCurrent()
        assertEquals(9, achievements.progress.value.award("frieren").current)
        assertFalse(achievements.progress.value.award("frieren").unlocked)
        library.complete(title, 10..10)
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.award("frieren").fromHistory)
        assertEquals(3, library.state.value.entry(title)?.verifiedEpisodes)
        disk.clear()
    }

    @Test fun finishingAnImportedPartialSeasonRecognizesCompletionWithoutInventingPlayback() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val title = anime()
        library.importTitles(listOf(ImportedTitle(title, ListStatus.Watching, episodesWatched = 10)))
        library.complete(title, 12..12)
        testScheduler.runCurrent()
        assertFalse(achievements.progress.value.award("attack_on_titan").unlocked)
        library.complete(title, 11..11)
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.award("attack_on_titan").fromHistory)
        assertTrue(achievements.progress.value.award("first_completed").fromHistory)
        assertFalse(library.state.value.entry(title)!!.hasVerifiedCompletion)
        disk.clear()
    }

    @Test fun nativePlaybackUpgradesRecognitionOnceAndDoesNotDuplicateCompletedTitles() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val title = anime()
        library.importTitles(listOf(ImportedTitle(title, ListStatus.Completed)))
        testScheduler.runCurrent()
        library.complete(title)
        testScheduler.runCurrent()
        val earned = achievements.progress.value
        assertTrue(earned.award("attack_on_titan").earned)
        assertFalse(earned.award("attack_on_titan").fromHistory)
        assertTrue(earned.award("first_completed").earned)
        assertEquals(1, earned.award("ten_completed").current)
        val before = library.state.value.level(earned.count { it.earned })
        library.importTitles(listOf(ImportedTitle(title, ListStatus.Completed)))
        library.complete(title)
        testScheduler.runCurrent()
        assertEquals(before, library.state.value.level(achievements.progress.value.count { it.earned }))
        assertEquals(earned.award("attack_on_titan").unlockedAt, achievements.progress.value.award("attack_on_titan").unlockedAt)
        disk.clear()
    }

    @Test fun repeatingImportAndReloadPreserveRecognitionAndLocalChoices() = runTest {
        val disk = store()
        val firstRun = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val library = Library(disk, firstRun).apply { load() }
        val achievements = Achievements(disk, firstRun, library, AppSettings(disk, firstRun)).apply { load() }
        val title = anime()
        library.setStatus(title, ListStatus.OnHold)
        library.setScore(title, 9)
        val imported = listOf(ImportedTitle(title, ListStatus.Completed, score = 6))
        library.importTitles(imported)
        testScheduler.runCurrent()
        val timestamp = achievements.progress.value.award("attack_on_titan").historyUnlockedAt
        assertEquals(ImportResult(0, 0), library.importTitles(imported))
        library.flush()
        achievements.flush()
        firstRun.coroutineContext[Job]!!.cancelAndJoin()
        val restoredLibrary = Library(disk, backgroundScope).apply { load() }
        val restored = Achievements(disk, backgroundScope, restoredLibrary, AppSettings(disk, backgroundScope)).apply { load() }
        testScheduler.runCurrent()
        assertEquals(ListStatus.OnHold, restoredLibrary.state.value.entry(title)?.status)
        assertEquals(9, restoredLibrary.state.value.entry(title)?.score)
        assertEquals(12, restoredLibrary.state.value.entry(title)?.episodesWatched)
        assertEquals(ImportedViewing(true, 12), restoredLibrary.state.value.entry(title)?.importedViewing)
        assertEquals(timestamp, restored.progress.value.award("attack_on_titan").historyUnlockedAt)
        assertTrue(restored.progress.value.award("attack_on_titan").fromHistory)
        assertEquals(0, restoredLibrary.state.value.level(restored.progress.value.count { it.earned }).xp)
        disk.clear()
    }

    @Test fun manualMarksAndUnrelatedImportsCannotBecomeExternalViewingHistory() = runTest {
        val disk = store()
        val firstRun = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val library = Library(disk, firstRun).apply { load() }
        val title = anime()
        library.setEpisodesWatched(title, 12)
        library.flush()
        firstRun.coroutineContext[Job]!!.cancelAndJoin()
        val restoredLibrary = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, restoredLibrary, AppSettings(disk, backgroundScope)).apply { load() }
        restoredLibrary.importTitles(listOf(ImportedTitle(title, ListStatus.Planned)))
        testScheduler.runCurrent()
        assertNull(restoredLibrary.state.value.entry(title)?.importedViewing)
        assertFalse(achievements.progress.value.award("attack_on_titan").unlocked)
        assertFalse(achievements.progress.value.award("first_completed").unlocked)
        disk.clear()
    }

    @Test fun externalHistoryCannotLaunderXpThroughMetaAchievements() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val title = anime()
        val catalog = listOf(
            Achievement("title", 1, AchievementCategory.Anime, "Тайтл", "", Trigger("anime_season_completed", 1, title.id)),
            Achievement("meta", 2, AchievementCategory.Meta, "Все", "", Trigger("all_required_achievements_unlocked", 1, required = listOf("title"))),
        )
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope), catalog).apply { load() }
        library.importTitles(listOf(ImportedTitle(title, ListStatus.Completed)))
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.all { it.fromHistory })
        assertEquals(0, library.state.value.level(achievements.progress.value.count { it.earned }).xp)
        library.complete(title)
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.all { it.earned })
        assertEquals(480, library.state.value.level(achievements.progress.value.count { it.earned }).xp)
        disk.clear()
    }

    @Test fun olderEarnedAwardsKeepTheirXpWhenTheSameTitleIsImported() = runTest {
        val disk = store()
        disk.write("achievements.json", AppJson.encodeToString(AchievementState.serializer(),
            AchievementState(unlocked = mapOf("attack_on_titan" to 1L))))
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        library.importTitles(listOf(ImportedTitle(anime(), ListStatus.Completed)))
        testScheduler.runCurrent()
        assertEquals(1L, achievements.progress.value.award("attack_on_titan").unlockedAt)
        assertTrue(achievements.progress.value.award("attack_on_titan").earned)
        assertEquals(50, library.state.value.level(achievements.progress.value.count { it.earned }).xp)
        disk.clear()
    }
}
