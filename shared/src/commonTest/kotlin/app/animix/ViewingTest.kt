package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.data.playback.*
import app.animix.data.ConnectionPolicy
import app.animix.model.Anime
import app.animix.model.SkipRange
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class ViewingTest {
    private val anime = Anime.placeholder(16498).copy(title = "Тестовый сезон", episodes = 12)
    private fun store() = DiskStore("build/test-viewing-${Random.nextInt(1_000_000)}")
    private fun playing(position: Long) = PlayerSnapshot(playing = true, playRequested = true, ready = true, positionMs = position, durationMs = 100_000)

    @Test fun aSeekIntoCreditsDoesNotCreatePlaybackCoverage() {
        val tracker = PlaybackTracker()
        tracker.sample(playing(0), 0)
        tracker.discontinuity()
        tracker.sample(playing(99_000), 250)
        tracker.sample(playing(100_000).copy(playing = false, ended = true), 1250)
        val credit = tracker.drain()
        assertEquals(1_000, credit.elapsedMs)
        assertFalse(EpisodeViewing(100_000, credit.ranges, reachedEnd = true).qualifies())
    }

    @Test fun unexpectedDiscontinuitiesAndBackgroundGapsDoNotCountAsWatching() {
        val tracker = PlaybackTracker()
        tracker.sample(playing(0), 0)
        tracker.sample(playing(80_000), 250)
        tracker.sample(playing(90_000), 10_250)
        assertTrue(tracker.drain().ranges.isEmpty())
        tracker.sample(playing(91_000), 11_250)
        assertEquals(1_000, tracker.drain().elapsedMs)
    }

    @Test fun pauseBufferingAndFailedFramesNeverAccumulateIdleTime() {
        val tracker = PlaybackTracker()
        tracker.sample(playing(0), 0)
        tracker.sample(playing(1_000), 1_000)
        tracker.sample(playing(1_000).copy(playing = false), 2_000)
        tracker.sample(playing(1_000).copy(buffering = true), 3_000)
        tracker.sample(playing(1_000).copy(error = "offline"), 4_000)
        assertEquals(1_000, tracker.drain().elapsedMs)
    }

    @Test fun ordinaryForwardAndBackwardSeeksPreservePreviouslyWatchedSegments() {
        val ranges = mergeViewedRanges(listOf(ViewedRange(0, 40_000), ViewedRange(55_000, 100_000), ViewedRange(20_000, 35_000)), 100_000)
        val viewing = EpisodeViewing(100_000, ranges, reachedEnd = true)
        assertEquals(85_000, viewing.uniqueMs)
        assertTrue(viewing.qualifies())
        assertFalse(viewing.copy(reachedEnd = false).qualifies(), "coverage alone must not prematurely finish an episode")
    }

    @Test fun skippingKnownOpeningAndEndingIsAllowedButBadMetadataCannotShrinkTheEpisode() {
        assertTrue(EpisodeViewing(100_000, listOf(ViewedRange(0, 10_000), ViewedRange(20_000, 90_000)),
            opening = SkipRange(10, 10), ending = SkipRange(90, 10), reachedEnd = true).qualifies())
        assertFalse(EpisodeViewing(100_000, listOf(ViewedRange(85_000, 90_000)),
            opening = SkipRange(0, 85), ending = SkipRange(90, 10), reachedEnd = true).qualifies())
    }

    @Test fun partialCoverageSurvivesRestartAndReplaysDoNotFarmXp() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(0, 40_000)), 40_000), 100_000, null, null, false)
        library.flush()
        val reloaded = Library(disk, backgroundScope).apply { load() }
        reloaded.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(55_000, 100_000)), 45_000), 100_000, null, null, true)
        val before = reloaded.state.value
        assertEquals(1, before.entry(anime)?.verifiedEpisodes)
        assertEquals(85, before.watchDays.values.sum())
        val completedAt = before.entry(anime)?.viewing?.get("1")?.completedAt
        reloaded.recordPlayback(anime, "1.0", PlaybackCredit(listOf(ViewedRange(0, 40_000)), 40_000), 100_000, null, null, true)
        assertEquals(before.level(0), reloaded.state.value.level(0))
        assertEquals(completedAt, reloaded.state.value.entry(anime)?.viewing?.get("1")?.completedAt)
        assertEquals(1, reloaded.state.value.entry(anime)?.verifiedEpisodes)
        disk.clear()
    }

    @Test fun watchingTheLastEpisodeDoesNotGrantTheWholeSeasonOrMarkMissingEpisodes() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        library.recordPlayback(anime, "12", PlaybackCredit(listOf(ViewedRange(0, 100_000)), 100_000), 100_000, null, null, true)
        val entry = library.state.value.entry(anime)!!
        assertEquals(1, entry.verifiedEpisodes)
        assertEquals(1, entry.watchedEpisodeCount)
        assertEquals(0, entry.episodesWatched)
        assertTrue(entry.isEpisodeWatched("12"))
        assertFalse(entry.isEpisodeWatched("1"))
        assertFalse(entry.hasVerifiedCompletion)
        disk.clear()
    }

    @Test fun rewardsRequireVerifiedEpisodesAndCannotBeGrantedByManualMarks() = runTest {
        val disk = store()
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        library.setStatus(anime, ListStatus.Completed)
        library.setEpisodesWatched(anime, 12)
        testScheduler.runCurrent()
        assertFalse(achievements.progress.value.first { it.achievement.id == "attack_on_titan" }.unlocked)
        for (episode in 1..12) library.recordPlayback(anime, "$episode", PlaybackCredit(listOf(ViewedRange(0, 100_000)), 100_000), 100_000, null, null, true)
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.first { it.achievement.id == "attack_on_titan" }.unlocked)
        assertTrue(achievements.progress.value.first { it.achievement.id == "first_completed" }.unlocked)
        assertTrue(achievements.progress.value.first { it.achievement.id == "marathon" }.unlocked)
        val before = achievements.progress.value.first { it.achievement.id == "marathon" }.current
        repeat(10) { library.recordPlayback(anime, "12", PlaybackCredit(listOf(ViewedRange(0, 100_000)), 100_000), 100_000, null, null, true) }
        testScheduler.runCurrent()
        assertEquals(before, achievements.progress.value.first { it.achievement.id == "marathon" }.current)
        disk.clear()
    }

    @Test fun previouslyEarnedAwardsRemainEarnedAfterTheAccountingUpgrade() = runTest {
        val disk = store()
        disk.write("achievements.json", AppJson.encodeToString(AchievementState.serializer(), AchievementState(unlocked = mapOf("attack_on_titan" to 1L))))
        val library = Library(disk, backgroundScope).apply { load() }
        val achievements = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        testScheduler.runCurrent()
        assertTrue(achievements.progress.value.first { it.achievement.id == "attack_on_titan" }.unlocked)
        disk.clear()
    }

    @Test fun levelsHaveStableIncreasingThresholds() {
        val empty = LibraryData()
        assertEquals(ProfileLevel(1, 0, 0, 100), empty.level(0))
        assertEquals(ProfileLevel(2, 100, 0, 150), empty.level(2))
        assertEquals(ProfileLevel(3, 250, 0, 200), empty.level(5))
        assertEquals(0.5f, empty.level(1).fraction)
    }

    @Test fun powerSavingPersistsAndRestoresTheSeparateAnimationPreference() = runTest {
        val disk = store()
        val settings = AppSettings(disk, backgroundScope)
        assertTrue(settings.state.value.animationsAllowed)
        settings.update { it.copy(batterySaver = true) }
        settings.flush()
        val restored = AppSettings(disk, backgroundScope).apply { load() }
        assertFalse(restored.state.value.animationsAllowed)
        assertTrue(restored.state.value.animationsEnabled)
        assertFalse(ConnectionPolicy(restored).prefetch)
        assertEquals(1, ConnectionPolicy(restored).downloadParallelism())
        restored.update { it.copy(batterySaver = false) }
        assertTrue(restored.state.value.animationsAllowed)
        restored.update { it.copy(animationsEnabled = false, batterySaver = true) }
        restored.update { it.copy(batterySaver = false) }
        assertFalse(restored.state.value.animationsAllowed)
        disk.clear()
    }
}
