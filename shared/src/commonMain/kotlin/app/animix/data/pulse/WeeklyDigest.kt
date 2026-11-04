package app.animix.data.pulse

import app.animix.core.localDayIndex
import app.animix.core.weekdayOf
import app.animix.data.library.LibraryData
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.data.library.plausibleWatchDays
import app.animix.data.library.watchedEpisodeCount
import app.animix.model.Anime
import app.animix.model.AnimeStatus

/** One week of the viewer's own watching, Monday to Sunday. */
data class WeekStats(val start: Long, val minutes: Int, val episodes: Int, val activeDays: Int)

/** A title of «Смотрю» with episodes out that the viewer has not seen yet. */
data class FreshEpisodes(val anime: Anime, val watched: Int, val available: Int, val nextEpisodeAt: String? = null) {
    val unseen: Int get() = (available - watched).coerceAtLeast(0)
}

/** A title watched this week: how many of its episodes were finished, and whether the title is now done. */
data class WeekTitle(val anime: Anime, val episodes: Int, val finished: Boolean)

/** A title almost finished: a few episodes to go. */
data class AlmostDone(val entry: LibraryEntry, val left: Int)

/**
 * AniPulse — «ваша аниме-неделя», built on the device from the viewer's lists, playback and the catalog.
 * No AI and no texts written by a service: every line is a fact the app can check.
 */
data class WeeklyDigest(
    val week: WeekStats,
    val previous: WeekStats,
    /** The last eight weeks, oldest first, for the week-by-week chart. */
    val weeks: List<WeekStats>,
    val streak: Int,
    val topGenre: String?,
    val fresh: List<FreshEpisodes>,
    val almostDone: List<AlmostDone>,
    val onHold: List<LibraryEntry>,
    val ongoingForYou: List<Anime>,
    val picks: List<Anime>,
    /** The week's news and announcements, about the viewer's titles first. */
    val news: List<app.animix.model.NewsItem> = emptyList(),
    /** What the week was made of: titles with episodes finished this week, most first. */
    val watched: List<WeekTitle> = emptyList(),
) {
    /** Change of watching time against the previous week, in percent; null when there is nothing to compare. */
    val change: Int? get() = if (previous.minutes < 10) null else ((week.minutes - previous.minutes) * 100 / previous.minutes)
}

/** Monday of the week that holds [day]. */
fun weekStart(day: Long): Long = day - weekdayOf(day)

/** The counting part of the digest: weeks, streak and the titles to look at, from the library alone. */
fun digestFrom(library: LibraryData, cards: Map<String, Anime> = emptyMap(), today: Long = localDayIndex(),
    nextEpisodes: Map<String, String> = emptyMap(), ongoingForYou: List<Anime> = emptyList(), picks: List<Anime> = emptyList(),
    news: List<app.animix.model.NewsItem> = emptyList()): WeeklyDigest {
    val days = library.plausibleWatchDays(today)
    val completions = library.entries.values.flatMap { entry -> entry.viewing.values.mapNotNull { it.completedAt } }.map { localDayIndex(it) }
    fun week(start: Long) = WeekStats(start,
        minutes = (start until start + 7).sumOf { days[it] ?: 0 } / 60,
        episodes = completions.count { it in start until start + 7 },
        activeDays = (start until start + 7).count { (days[it] ?: 0) >= 60 })
    val current = weekStart(today)
    val weeks = (7 downTo 0).map { week(current - it * 7) }
    var streak = 0
    var day = if ((days[today] ?: 0) >= 60) today else today - 1
    while ((days[day] ?: 0) >= 60) { streak++; day-- }
    // The genre of the week: genres of titles with episodes finished this week, by count.
    val topGenre = library.entries.values.flatMap { entry ->
        val finished = entry.viewing.values.count { v -> v.completedAt?.let { localDayIndex(it) >= current } == true }
        entry.anime.genres.map { it to finished }
    }.filter { it.second > 0 }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.maxByOrNull { it.value }?.key
    val watching = library.entries.values.filter { it.status == ListStatus.Watching }
    // New episodes are those of an ongoing title, or of a series already begun. A film or a series not
    // started yet is something to watch, not «a new episode».
    val fresh = watching.mapNotNull { entry ->
        val anime = cards[entry.anime.key] ?: entry.anime
        val available = anime.availableEpisodes
        val watched = entry.watchedEpisodeCount
        val series = anime.kind != app.animix.model.AnimeKind.Movie && available > 1
        if (available > watched && series && (anime.status == AnimeStatus.Ongoing || watched > 0))
            FreshEpisodes(anime, watched, available, nextEpisodes[anime.key]) else null
    }.sortedWith(compareByDescending<FreshEpisodes> { it.anime.status == AnimeStatus.Ongoing }.thenByDescending { it.unseen })
    val almost = library.entries.values.filter { it.status == ListStatus.Watching || it.status == ListStatus.OnHold }.mapNotNull { entry ->
        val total = (cards[entry.anime.key] ?: entry.anime).let { if (it.status == AnimeStatus.Ongoing) 0 else it.episodes }
        val left = total - entry.watchedEpisodeCount
        if (total > 0 && left in 1..3) AlmostDone(entry, left) else null
    }.sortedBy { it.left }
    val onHold = library.entries.values.filter { it.status == ListStatus.OnHold }.sortedByDescending { it.updatedAt }.take(8)
    val mine = library.entries.keys
    // A headline that names a title from the lists links to it, whichever source wrote it.
    val known = library.entries.values.map { it.anime }.filter { it.title.length >= 4 }
    val linked = news.map { item -> if (item.anime != null) item else known.firstOrNull { item.title.contains(it.title, ignoreCase = true) }
        ?.let { item.copy(anime = it) } ?: item }
    val sortedNews = linked.sortedWith(compareByDescending<app.animix.model.NewsItem> { it.anime?.key in mine }.thenByDescending { it.createdAt })
    val watchedThisWeek = library.entries.values.mapNotNull { entry ->
        val count = entry.viewing.values.count { v -> v.completedAt?.let { localDayIndex(it) >= current } == true }
        if (count == 0) null else WeekTitle(cards[entry.anime.key] ?: entry.anime, count, entry.status == ListStatus.Completed)
    }.sortedByDescending { it.episodes }
    return WeeklyDigest(weeks.last(), weeks[weeks.lastIndex - 1], weeks, streak, topGenre, fresh, almost, onHold, ongoingForYou, picks, sortedNews,
        watchedThisWeek)
}
