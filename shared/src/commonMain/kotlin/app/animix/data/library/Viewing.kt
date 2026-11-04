package app.animix.data.library

import app.animix.model.SkipRange
import kotlinx.serialization.Serializable

@Serializable
data class ViewedRange(val startMs: Long, val endMs: Long)

/** Unique decoded playback coverage, independent of the user's list and seek position. */
@Serializable
data class EpisodeViewing(
    val durationMs: Long = 0,
    val ranges: List<ViewedRange> = emptyList(),
    val opening: SkipRange? = null,
    val ending: SkipRange? = null,
    val reachedEnd: Boolean = false,
    val completedAt: Long? = null,
) {
    val uniqueMs: Long get() = ranges.sumOf { it.endMs - it.startMs }

    fun qualifies(): Boolean {
        if (durationMs <= 0) return false
        val end = ending?.start?.times(1000L)?.takeIf { it > durationMs / 2 && it < durationMs }
            ?: (durationMs * 92 / 100)
        // Ending timings of a release are often a minute off (a preview, a late credit roll). Watched
        // coverage that reaches the last stretch of the story counts as reaching its end, as the
        // player's flag does; the 80% coverage below still has to be real playback.
        val reach = end - durationMs / 20
        if (!reachedEnd && ranges.none { it.endMs >= reach }) return false
        val openingStart = opening?.start?.times(1000L)?.coerceIn(0, end) ?: 0
        val openingEnd = opening?.end?.times(1000L)?.coerceIn(openingStart, end) ?: 0
        // Bad skip metadata must never reduce a whole episode to a few seconds.
        val excludeOpening = openingEnd - openingStart <= end / 4
        val required = end - if (excludeOpening) openingEnd - openingStart else 0
        if (required <= 0) return false
        val covered = ranges.sumOf { range ->
            val start = range.startMs.coerceIn(0, end)
            val finish = range.endMs.coerceIn(start, end)
            val overlap = if (excludeOpening) (minOf(finish, openingEnd) - maxOf(start, openingStart)).coerceAtLeast(0) else 0
            finish - start - overlap
        }
        return covered >= required * 80 / 100
    }
}

/** One batch of playback validated against monotonic elapsed time by PlaybackTracker. */
data class PlaybackCredit(val ranges: List<ViewedRange>, val elapsedMs: Long)

fun mergeViewedRanges(ranges: List<ViewedRange>, durationMs: Long): List<ViewedRange> {
    val result = mutableListOf<ViewedRange>()
    val limit = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
    for (range in ranges.map { ViewedRange(it.startMs.coerceIn(0, limit), it.endMs.coerceIn(0, limit)) }
        .filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
        val last = result.lastOrNull()
        if (last != null && range.startMs <= last.endMs) result[result.lastIndex] = last.copy(endMs = maxOf(last.endMs, range.endMs))
        else result += range
    }
    return result
}

fun viewingKey(number: String): String = number.toDoubleOrNull()?.let {
    if (it.isFinite() && it == it.toLong().toDouble()) it.toLong().toString() else number
} ?: number

/**
 * Confirms episodes whose saved coverage qualifies but that have no confirmation yet: coverage
 * merged from two devices, or saved before the qualification rule accepted inaccurate ending
 * timings. Uses the same rule as live playback; list marks and positions play no part.
 */
fun LibraryEntry.confirmQualifiedViewing(now: Long): LibraryEntry {
    if (viewing.values.none { it.completedAt == null && it.qualifies() }) return this
    val all = viewing.mapValues { (_, value) -> if (value.completedAt == null && value.qualifies()) value.copy(completedAt = now) else value }
    var watched = episodesWatched
    while (all[(watched + 1).toString()]?.completedAt != null) watched++
    return copy(viewing = all, episodesWatched = watched,
        status = if (anime.episodes > 0 && watched >= anime.episodes) ListStatus.Completed else status)
}

val LibraryEntry.verifiedEpisodes: Int get() = viewing.values.count { it.completedAt != null }
val LibraryEntry.hasVerifiedCompletion: Boolean get() = anime.episodes > 0 && (1..anime.episodes).all { viewing[it.toString()]?.completedAt != null }

/** The union of the external episode prefix and locally confirmed episodes, without duplicates. */
val LibraryEntry.episodesWithHistory: Int get() {
    val imported = importedViewing?.episodesWatched ?: 0
    val additional = viewing.count { (number, value) ->
        value.completedAt != null && (number.toDoubleOrNull()?.let { it > imported } != false)
    }
    return (imported.toLong() + additional).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
val LibraryEntry.completedWithHistory: Boolean get() {
    if (hasVerifiedCompletion || importedViewing?.completed == true) return true
    val imported = importedViewing?.episodesWatched ?: return false
    if (anime.episodes <= 0 || imported <= 0) return false
    return imported >= anime.episodes || (imported + 1..anime.episodes).all { viewing[it.toString()]?.completedAt != null }
}
fun LibraryEntry.isEpisodeWatched(number: String): Boolean =
    viewing[viewingKey(number)]?.completedAt != null || (number.toDoubleOrNull()?.let { it <= episodesWatched } == true)
val LibraryEntry.watchedEpisodeCount: Int get() = episodesWatched + viewing.count { (number, value) ->
    value.completedAt != null && (number.toDoubleOrNull()?.let { it > episodesWatched } != false)
}

/**
 * Playback time confirmed by unique decoded coverage: every second of an episode counts once.
 * Imports, list marks, seeking and broken daily counters cannot inflate it.
 */
val LibraryData.confirmedWatchSeconds: Long get() =
    entries.values.sumOf { entry -> entry.viewing.values.sumOf { it.uniqueMs.coerceIn(0, it.durationMs.coerceAtLeast(0)) } } / 1000

/** Confirmed AniMix playback plus the time AniMix 2.x measured, for hours in the profile and time awards. */
val LibraryData.totalWatchSeconds: Long get() = confirmedWatchSeconds + legacyWatchSeconds

/** Daily playback seconds that can be real: no future days and at most 24 hours per day. */
fun LibraryData.plausibleWatchDays(today: Long = app.animix.core.localDayIndex()): Map<Long, Int> =
    watchDays.filter { (day, seconds) -> day in EarliestWatchDay..today + 1 && seconds > 0 }
        .mapValues { it.value.coerceAtMost(SecondsPerDay) }

/** 1 January 2025: AniMix 3.0 playback statistics cannot be older. */
private const val EarliestWatchDay = 20_089L
private const val SecondsPerDay = 86_400

data class ProfileLevel(val level: Int, val xp: Long, val progressXp: Long, val nextLevelXp: Long) {
    val fraction: Float get() = (progressXp.toFloat() / nextLevelXp).coerceIn(0f, 1f)
}

/** Unique minutes + unique episode completions + permanent awards; seeking/replays add no XP. */
fun LibraryData.level(earnedAchievements: Int): ProfileLevel {
    val xp = entries.values.sumOf { entry -> entry.viewing.values.sumOf { it.uniqueMs } } / 60_000 +
        entries.values.sumOf { it.verifiedEpisodes.toLong() } * 20 + earnedAchievements.coerceAtLeast(0).toLong() * 50
    // 150 XP to level 2, then 250, 350, … (+100 per level). XP is derived, so changing the curve
    // recomputes every earlier level from the same confirmed playback and awards.
    fun threshold(level: Int): Long { val n = level.toLong() - 1; return n * 150 + n * (n - 1) * 50 }
    var low = 1
    var high = 2
    while (threshold(high) <= xp && high < 1_000_000) high *= 2
    while (low + 1 < high) { val mid = low + (high - low) / 2; if (threshold(mid) <= xp) low = mid else high = mid }
    return ProfileLevel(low, xp, xp - threshold(low), threshold(low + 1) - threshold(low))
}
