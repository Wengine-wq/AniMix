package app.animix.data.account

import app.animix.data.achievements.AchievementState
import app.animix.data.library.*
import kotlinx.serialization.Serializable

/**
 * What a friend's page shows of the statistics, computed on the owner's device at sync time. The page
 * never needs resume positions or playback coverage, and the server stores no second copy of the rules.
 */
@Serializable
data class PublicSummary(
    val minutes: Int = 0,
    val episodes: Int = 0,
    val completed: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Seconds per local day index for the last 30 days; the viewer draws its own fortnight. */
    val days: Map<Long, Int> = emptyMap(),
    val genres: List<Pair<String, Int>> = emptyList(),
    /** Titles per status ([ListStatus.name]) and favourites. */
    val statuses: Map<String, Int> = emptyMap(),
    val favorites: Int = 0,
    val level: Int = 1,
    val levelFraction: Float = 0f,
    val achievements: Int = 0,
    val today: Long = 0,
)

internal fun publicSummary(library: LibraryData, awards: AchievementState, today: Long = app.animix.core.localDayIndex()): PublicSummary {
    val stats = library.stats(today = today)
    val level = library.level(awards.unlocked.size)
    return PublicSummary(
        minutes = stats.totalMinutes, episodes = stats.episodes, completed = stats.completed,
        streak = stats.streak, bestStreak = stats.bestStreak,
        days = library.plausibleWatchDays(today).filterKeys { it > today - 30 },
        genres = stats.topGenres,
        statuses = ListStatus.entries.associate { status -> status.name to library.entries.values.count { it.status == status } }.filterValues { it > 0 },
        favorites = library.entries.values.count { it.favorite },
        level = level.level, levelFraction = level.fraction,
        achievements = awards.unlocked.size + awards.historical.keys.count { it !in awards.unlocked },
        today = today,
    )
}

/** The last [count] days in minutes, oldest first, as the profile's activity chart draws them. */
fun PublicSummary.lastDays(count: Int = 14, today: Long = app.animix.core.localDayIndex()): List<Int> =
    (count - 1 downTo 0).map { (days[today - it] ?: 0) / 60 }
