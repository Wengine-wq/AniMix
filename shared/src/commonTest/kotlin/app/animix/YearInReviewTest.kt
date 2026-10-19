package app.animix

import app.animix.data.library.*
import app.animix.model.Anime
import kotlin.test.*

class YearInReviewTest {
    @Test fun daysBecomeCalendarDates() {
        assertEquals(LocalDay(1970, 1, 1), dayToDate(0))
        assertEquals(LocalDay(2000, 2, 29), dayToDate(11_016))
        assertEquals(LocalDay(2026, 10, 19), dayToDate(20_745))
        assertEquals(LocalDay(1969, 12, 31), dayToDate(-1))
    }

    @Test fun onlyThisYearsConfirmedViewingCounts() {
        val day = 20_745L // 2026-10-19
        val lastYear = 20_400L // 2025
        val anime = Anime.placeholder(1).copy(title = "Тайтл", genres = listOf("Драма"))
        fun watched(at: Long) = EpisodeViewing(durationMs = 1, reachedEnd = true, completedAt = at)
        val data = LibraryData(
            entries = mapOf(anime.key to LibraryEntry(anime, viewing = mapOf("1" to watched(day), "2" to watched(day), "3" to watched(lastYear)))),
            watchDays = mapOf(day to 3_600, day - 1 to 1_800, lastYear to 9_000),
        )
        val review = data.yearInReview(2026, emptyList(), offsetDay = { it })
        assertEquals(90, review.minutes)
        assertEquals(2, review.episodes)
        assertEquals(1, review.titles)
        assertEquals(2, review.longestStreak)
        assertEquals(2, review.marathon)
        assertEquals(10, review.bestMonth)
        assertEquals("Тайтл", review.topTitles.single().anime.title)
        assertEquals("Драма", review.topGenres.single().first)
    }
}
