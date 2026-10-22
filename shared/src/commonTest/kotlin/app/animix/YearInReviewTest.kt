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

    @Test fun reviewOpensAtTheEndOfDecemberOrAfterThreeTitles() {
        fun entry(id: Int, at: Long) = Anime.placeholder(id).let { it.key to LibraryEntry(it, viewing = mapOf("1" to EpisodeViewing(durationMs = 1, reachedEnd = true, completedAt = at))) }
        val day = 20_745L
        val two = LibraryData(entries = mapOf(entry(1, day), entry(2, day)))
        val three = LibraryData(entries = mapOf(entry(1, day), entry(2, day), entry(3, day)))
        assertFalse(two.reviewAccess(LocalDay(2026, 10, 22), offsetDay = { it }).open)
        assertTrue(three.reviewAccess(LocalDay(2026, 10, 22), offsetDay = { it }).early)
        assertTrue(two.reviewAccess(LocalDay(2026, 12, 20), offsetDay = { it }).open)
        // In January the finished year stays open.
        val january = two.reviewAccess(LocalDay(2027, 1, 10), offsetDay = { it })
        assertEquals(2026, january.year)
        assertTrue(january.open)
    }

    @Test fun usageCountsHoursSessionsAndSources() {
        val minute = 60_000L
        val usage = YearUsage()
            .add(600, 100 * minute, hour = 23, weekday = 5, day = 1, animeKey = "s1", context = PlaybackContext("AniLibria", offline = false, platform = "Android"))
            .add(600, 110 * minute, hour = 23, weekday = 5, day = 1, animeKey = "s1", context = PlaybackContext("AniLibria", offline = true, platform = "Windows"))
            .add(300, 220 * minute, hour = 1, weekday = 6, day = 2, animeKey = "s2", context = null)
        assertEquals(2, usage.sessions)
        assertEquals(1200, usage.longestSessionSeconds)
        assertEquals(1200, usage.hours[23])
        assertEquals(600, usage.offlineSeconds)
        assertEquals(900, usage.onlineSeconds)
        assertEquals(1200, usage.studios["AniLibria"])
        assertEquals(setOf("s1", "s2"), usage.firstPlayed.keys)
        assertEquals(20_454L, app.animix.ui.screens.wrapped.firstDayOf(2026))
        assertEquals(0L, app.animix.ui.screens.wrapped.firstDayOf(1970))
    }
}
