package app.animix.data.library

import app.animix.core.localDayIndex

/** A title to resume: the episode to open and how far the whole title is watched. */
data class ContinueItem(val entry: LibraryEntry, val episode: String, val episodeStarted: Boolean, val titleProgress: Float)

/**
 * «Продолжить просмотр»: titles with an episode in progress or unwatched episodes left.
 * Anything watched past 90 % (or completed/dropped) is left out instead of nagging.
 */
fun LibraryData.continueWatching(limit: Int = 12): List<ContinueItem> = entries.values
    .filter { it.status != ListStatus.Completed && it.status != ListStatus.Dropped }
    .mapNotNull { entry ->
        val total = entry.anime.availableEpisodes.takeIf { it > 0 } ?: entry.anime.episodes
        val inProgress = entry.episodePositions.keys.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }
        val progress = if (total > 0) (entry.watchedEpisodeCount.toFloat() / total).coerceIn(0f, 1f) else 0f
        when {
            inProgress != null -> ContinueItem(entry, inProgress, true, progress)
            entry.episodesWatched > 0 && total > 0 && progress < 0.9f && entry.episodesWatched < total ->
                ContinueItem(entry, (entry.episodesWatched + 1).toString(), false, progress)
            else -> null
        }
    }
    .sortedByDescending { it.entry.updatedAt }
    .take(limit)

/** Watching statistics for the profile. */
data class WatchStats(
    val totalMinutes: Int,
    val episodes: Int,
    val completed: Int,
    val streak: Int,
    val bestStreak: Int,
    /** Minutes for each of the last [dayCount] days, oldest first. */
    val lastDays: List<Int>,
    val topGenres: List<Pair<String, Int>>,
)

fun LibraryData.stats(dayCount: Int = 14, today: Long = localDayIndex()): WatchStats {
    val days = plausibleWatchDays(today)
    val active = days.filterValues { it >= 60 }.keys
    var streak = 0
    // A streak survives until the end of today: yesterday's run still counts before today's first episode.
    var day = if (today in active) today else today - 1
    while (day in active) { streak++; day-- }
    var best = 0
    var run = 0
    var previous: Long? = null
    for (d in active.sorted()) {
        run = if (previous != null && d == previous + 1) run + 1 else 1
        best = maxOf(best, run)
        previous = d
    }
    return WatchStats(
        totalMinutes = (confirmedWatchSeconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        episodes = entries.values.sumOf { it.watchedEpisodeCount },
        completed = entries.values.count { it.status == ListStatus.Completed },
        streak = streak,
        bestStreak = maxOf(best, streak),
        lastDays = (dayCount - 1 downTo 0).map { (days[today - it] ?: 0) / 60 },
        topGenres = tasteGenres().take(5),
    )
}

/**
 * Genre weights from what the viewer actually does: episodes watched, completed titles,
 * favourites and personal scores count for a genre; dropped titles count against it.
 */
fun LibraryData.tasteGenres(): List<Pair<String, Int>> {
    val weights = HashMap<String, Int>()
    for (entry in entries.values) {
        var weight = entry.episodesWatched.coerceAtMost(24) + entry.episodePositions.size * 2
        if (entry.status == ListStatus.Completed) weight += 10
        if (entry.status == ListStatus.Watching) weight += 4
        if (entry.status == ListStatus.Dropped) weight -= 12
        if (entry.favorite) weight += 8
        entry.score?.let { weight += (it - 5) * 3 }
        if (weight == 0) continue
        entry.anime.genres.forEach { genre -> weights[genre] = (weights[genre] ?: 0) + weight }
    }
    return weights.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
}
