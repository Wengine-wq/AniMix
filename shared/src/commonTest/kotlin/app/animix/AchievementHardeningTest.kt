package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class AchievementHardeningTest {
    private fun disk() = DiskStore("build/test-hardening-${Random.nextInt(1_000_000)}")
    private fun Achievements.award(id: String) = progress.value.first { it.achievement.id == id }

    @Test fun oneEpisodeInManyQualitiesAndDubbingsIsOneDownload() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        repeat(12) { rewards.downloadCompleted("s1", "1") }
        rewards.downloadCompleted("s1", "1.0")
        rewards.flush()
        assertEquals(1, rewards.award("download_hoarder").current)
        assertFalse(rewards.award("download_hoarder").earned)
    }

    @Test fun togglingSettingsAndThemesBackAndForthEarnsEachAwardOnce() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        repeat(20) { rewards.settingEnabled("skipOpenings"); rewards.themeChosen(if (it % 2 == 0) "Dusk" else "Dawn") }
        rewards.flush()
        val earned = rewards.progress.value.filter { it.earned }.map { it.achievement.id }
        assertEquals(earned.toSet().size, earned.size)
        assertEquals(setOf("skip_openings_enabled", "dark_theme_selected", "light_theme_selected"), earned.toSet())
        val level = library.state.value.level(earned.size)
        assertEquals(150, level.xp)
    }

    @Test fun confirmationDatesFollowServerTimeNotTheDeviceClock() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val anime = Anime.placeholder(7).copy(title = "Title", episodes = 2)
        val serverTime = 1_800_000_000_000L
        try {
            TrustedClock.observeServerTime(serverTime)
            library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(0, 100_000)), 100_000), 100_000, null, null, true)
            val at = library.state.value.entries.getValue(anime.key).viewing.getValue("1").completedAt!!
            assertTrue(at - serverTime in 0..60_000, "completedAt $at must follow the server anchor")
        } finally { TrustedClock.reset() }
    }

    @Test fun seekingToTheEndAndManualMarksEarnNothing() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        val anime = Anime.placeholder(16498).copy(title = "Атака", episodes = 25)
        library.setEpisodesWatched(anime, 25)
        library.setStatus(anime, ListStatus.Completed)
        // A jump to the credits: reached the end with a few seconds of coverage.
        library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(95_000, 100_000)), 5_000), 100_000, null, null, true)
        rewards.flush()
        assertFalse(rewards.award("attack_on_titan").unlocked)
        assertFalse(rewards.award("first_completed").earned)
        assertEquals(0, library.state.value.entries.getValue(anime.key).verifiedEpisodes)
    }
}
