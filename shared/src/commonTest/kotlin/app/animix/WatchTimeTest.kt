package app.animix

import app.animix.core.*
import app.animix.data.achievements.*
import app.animix.data.library.*
import app.animix.model.Anime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class WatchTimeTest {
    private fun disk() = DiskStore("build/test-watch-time-${Random.nextInt(1_000_000)}")
    private val anime = Anime.placeholder(16498).copy(title = "Тестовый сезон", episodes = 12)

    @Test fun hoursComeFromConfirmedCoverageNotFromBrokenDailyCounters() {
        val today = localDayIndex()
        val entry = LibraryEntry(anime, viewing = mapOf("1" to EpisodeViewing(1_440_000, listOf(ViewedRange(0, 1_440_000)))))
        // An import-day spike and a day far in the future must not turn into thousands of hours.
        val data = LibraryData(entries = mapOf(anime.key to entry), watchDays = mapOf(today to 122_400_000, today + 400 to 50_000))
        val stats = data.stats(today = today)
        assertEquals(24, stats.totalMinutes)
        assertEquals(1440, stats.lastDays.last())
        assertEquals(mapOf(today to 86_400), data.plausibleWatchDays(today))
    }

    @Test fun importedHistoryAddsNoWatchTime() = runTest {
        val disk = disk()
        val library = Library(disk, backgroundScope).apply { load() }
        library.importTitles((1..300).map { ImportedTitle(Anime.placeholder(it).copy(title = "T$it", episodes = 500), ListStatus.Completed, 500) })
        assertEquals(0, library.state.value.stats().totalMinutes)
        assertEquals(0, library.state.value.confirmedWatchSeconds)
    }

    @Test fun inflatedTimeAwardsAreRevokedOnceWhileOtherAwardsStay() = runTest {
        val disk = disk()
        disk.write("library.json", AppJson.encodeToString(LibraryData.serializer(), LibraryData(watchDays = mapOf(localDayIndex() to 2_000_000_000))))
        disk.write("achievements.json", AppJson.encodeToString(AchievementState.serializer(), AchievementState(
            unlocked = mapOf("ten_hours" to 1L, "hundred_hours" to 2L, "three_sixty_five_hours" to 3L, "first_favorite" to 4L))))
        val library = Library(disk, backgroundScope).apply { load() }
        val rewards = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        rewards.flush()
        val earned = rewards.progress.value.filter { it.earned }.map { it.achievement.id }.toSet()
        assertEquals(setOf("first_favorite"), earned)
        val saved = AppJson.decodeFromString<AchievementState>(disk.read("achievements.json")!!)
        assertFalse("ten_hours" in saved.unlocked)

        // Genuine coverage after the repair earns the award again, and the repair never runs twice.
        val minutes = 10 * 60L
        library.recordPlayback(anime, "1", PlaybackCredit(listOf(ViewedRange(0, minutes * 60_000)), minutes * 60_000),
            minutes * 60_000, null, null, true)
        rewards.flush()
        assertTrue(rewards.progress.value.first { it.achievement.id == "ten_hours" }.earned)
        val again = Achievements(disk, backgroundScope, library, AppSettings(disk, backgroundScope)).apply { load() }
        again.flush()
        assertTrue(again.progress.value.first { it.achievement.id == "ten_hours" }.earned)
    }
}
