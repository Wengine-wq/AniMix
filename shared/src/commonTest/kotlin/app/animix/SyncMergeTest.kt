package app.animix

import app.animix.data.account.*
import app.animix.data.achievements.AchievementState
import app.animix.data.library.*
import kotlin.test.*

class SyncMergeTest {
    @Test fun earnedAndHistoricalAwardsKeepTheirSourceAndEarliestDates() {
        val first = AchievementState(unlocked = mapOf("one" to 20), historical = mapOf("two" to 10), sets = mapOf("search" to setOf("a")))
        val second = AchievementState(unlocked = mapOf("one" to 30), historical = mapOf("two" to 5), sets = mapOf("search" to setOf("b")))
        val merged = mergeAwards(first, second)
        assertEquals(mapOf("one" to 20L), merged.unlocked)
        assertEquals(mapOf("two" to 5L), merged.historical)
        assertEquals(setOf("a", "b"), merged.sets["search"])
        assertEquals(merged, mergeAwards(merged, second))
    }
    @Test fun deviceCountersConvergeAndAggregateWithoutReplayingDeltas() {
        val initial = SyncUsage(mapOf(10L to 30), mapOf(2026 to YearUsage(hours = listOf(30) + List(23) { 0 }, firstPlayed = mapOf("s21" to 10), longestSessionSeconds = 30)))
        val later = SyncUsage(mapOf(10L to 45), mapOf(2026 to YearUsage(hours = listOf(45) + List(23) { 0 }, firstPlayed = mapOf("s21" to 10), longestSessionSeconds = 45)))
        val own = usageDelta(later, initial, initial)
        assertEquals(45, own.days[10L])
        assertEquals(45, own.years[2026]?.hours?.first())
        assertEquals(10, own.years[2026]?.firstPlayed?.get("s21"))
        assertEquals(own, usageUnion(initial, own))
        val sum = aggregateUsage(listOf(own, initial))
        assertEquals(75, sum.days[10L])
        assertEquals(75, sum.years[2026]?.hours?.first())
        assertEquals(45, sum.years[2026]?.longestSessionSeconds)
        assertEquals(10, sum.years[2026]?.firstPlayed?.get("s21"))
    }
}
