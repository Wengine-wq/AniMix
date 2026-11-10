package app.animix

import app.animix.core.airingDayLabel
import app.animix.core.dayIndexOf
import app.animix.core.nextEpisodeLine
import app.animix.data.library.dayToDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleTest {
    // 10 November 2026 is a Tuesday.
    private val today = dayIndexOf(2026, 11, 10)

    @Test
    fun dayIndexIsTheReverseOfDayToDate() {
        assertEquals(0, dayIndexOf(1970, 1, 1))
        for (day in listOf(-800L, 0L, 59L, 11_016L, 20_767L, 30_000L)) {
            val date = dayToDate(day)
            assertEquals(day, dayIndexOf(date.year, date.month, date.day))
        }
    }

    @Test
    fun nearDatesAreNamedByDay() {
        assertEquals("сегодня", airingDayLabel("2026-11-10T18:00:00+03:00", today))
        assertEquals("завтра", airingDayLabel("2026-11-11T18:00:00+03:00", today))
        assertEquals("в четверг, 12 ноября", airingDayLabel("2026-11-12T18:00:00+03:00", today))
        assertEquals("в понедельник, 16 ноября", airingDayLabel("2026-11-16T01:00:00+03:00", today))
    }

    @Test
    fun aWeekAheadAndLaterIsOnlyTheDate() {
        assertEquals("17 ноября", airingDayLabel("2026-11-17T01:00:00+03:00", today))
        assertEquals("3 января", airingDayLabel("2027-01-03T01:00:00+03:00", today))
    }

    @Test
    fun pastAndBrokenDatesGiveNothing() {
        assertNull(airingDayLabel("2026-11-09T18:00:00+03:00", today))
        assertNull(airingDayLabel(null, today))
        assertNull(airingDayLabel("скоро", today))
        assertNull(nextEpisodeLine(6, null, today))
    }

    @Test
    fun theLineNamesTheNextEpisode() {
        assertEquals("Серия 7 выйдет в четверг, 12 ноября", nextEpisodeLine(6, "2026-11-12T18:00:00+03:00", today))
    }
}
