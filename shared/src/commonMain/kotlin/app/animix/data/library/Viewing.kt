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
        if (durationMs <= 0 || !reachedEnd) return false
        val end = ending?.start?.times(1000L)?.takeIf { it > durationMs / 2 && it < durationMs }
            ?: (durationMs * 92 / 100)
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

val LibraryEntry.verifiedEpisodes: Int get() = viewing.values.count { it.completedAt != null }
val LibraryEntry.hasVerifiedCompletion: Boolean get() = anime.episodes > 0 && (1..anime.episodes).all { viewing[it.toString()]?.completedAt != null }
fun LibraryEntry.isEpisodeWatched(number: String): Boolean =
    viewing[viewingKey(number)]?.completedAt != null || (number.toDoubleOrNull()?.let { it <= episodesWatched } == true)
val LibraryEntry.watchedEpisodeCount: Int get() = episodesWatched + viewing.count { (number, value) ->
    value.completedAt != null && (number.toDoubleOrNull()?.let { it > episodesWatched } != false)
}

data class ProfileLevel(val level: Int, val xp: Long, val progressXp: Long, val nextLevelXp: Long) {
    val fraction: Float get() = (progressXp.toFloat() / nextLevelXp).coerceIn(0f, 1f)
}

/** Unique minutes + unique episode completions + permanent awards; seeking/replays add no XP. */
fun LibraryData.level(unlockedAchievements: Int): ProfileLevel {
    val xp = entries.values.sumOf { entry -> entry.viewing.values.sumOf { it.uniqueMs } } / 60_000 +
        entries.values.sumOf { it.verifiedEpisodes.toLong() } * 20 + unlockedAchievements.coerceAtLeast(0).toLong() * 50
    fun threshold(level: Int): Long { val n = level.toLong() - 1; return n * 100 + n * (n - 1) * 25 }
    var low = 1
    var high = 2
    while (threshold(high) <= xp && high < 1_000_000) high *= 2
    while (low + 1 < high) { val mid = low + (high - low) / 2; if (threshold(mid) <= xp) low = mid else high = mid }
    return ProfileLevel(low, xp, xp - threshold(low), threshold(low + 1) - threshold(low))
}
