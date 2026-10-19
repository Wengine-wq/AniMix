package app.animix.data.library

import app.animix.core.localDayIndex
import app.animix.data.achievements.AchievementProgress
import app.animix.model.Anime

/** A calendar date from a day index (days since 1970-01-01), proleptic Gregorian. */
data class LocalDay(val year: Int, val month: Int, val day: Int)

fun dayToDate(day: Long): LocalDay {
    // Howard Hinnant's days-to-civil algorithm.
    val z = day + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return LocalDay(y, m, d)
}

data class TitleOfYear(val anime: Anime, val episodes: Int)

/** «Итоги года»: only what was really watched in AniMix this year (confirmed episodes, playback time). */
data class YearInReview(
    val year: Int,
    val minutes: Int,
    val activeDays: Int,
    val episodes: Int,
    val titles: Int,
    val longestStreak: Int,
    /** Most episodes confirmed on one day, and that day. */
    val marathon: Int,
    val marathonDay: LocalDay?,
    /** Month 1..12 with the most minutes, and those minutes. */
    val bestMonth: Int?,
    val bestMonthMinutes: Int,
    val minutesByMonth: List<Int>,
    val topTitles: List<TitleOfYear>,
    val topGenres: List<Pair<String, Int>>,
    val achievements: List<AchievementProgress>,
) {
    val empty: Boolean get() = minutes == 0 && episodes == 0
}

fun LibraryData.yearInReview(year: Int, achievements: List<AchievementProgress>, offsetDay: (Long) -> Long = { localDayIndex(it) }): YearInReview {
    val days = watchDays.filterKeys { dayToDate(it).year == year }.filterValues { it > 0 }
    val minutesByMonth = (1..12).map { month -> days.filterKeys { dayToDate(it).month == month }.values.sum() / 60 }
    val bestMonth = minutesByMonth.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
    var streak = 0
    var best = 0
    var previous: Long? = null
    for (day in days.filterValues { it >= 60 }.keys.sorted()) {
        streak = if (previous != null && day == previous + 1) streak + 1 else 1
        best = maxOf(best, streak)
        previous = day
    }
    // Episodes confirmed by real playback this year, per title and per day.
    val perTitle = mutableMapOf<String, Int>()
    val perDay = mutableMapOf<Long, Int>()
    for ((key, entry) in entries) {
        for (viewing in entry.viewing.values) {
            val at = viewing.completedAt ?: continue
            val day = offsetDay(at)
            if (dayToDate(day).year != year) continue
            perTitle[key] = (perTitle[key] ?: 0) + 1
            perDay[day] = (perDay[day] ?: 0) + 1
        }
    }
    val marathon = perDay.maxByOrNull { it.value }
    val genres = HashMap<String, Int>()
    perTitle.forEach { (key, count) -> entries[key]?.anime?.genres?.forEach { genres[it] = (genres[it] ?: 0) + count } }
    return YearInReview(
        year = year,
        minutes = days.values.sum() / 60,
        activeDays = days.count { it.value >= 60 },
        episodes = perTitle.values.sum(),
        titles = perTitle.size,
        longestStreak = best,
        marathon = marathon?.value ?: 0,
        marathonDay = marathon?.key?.let(::dayToDate),
        bestMonth = bestMonth?.index?.plus(1),
        bestMonthMinutes = bestMonth?.value ?: 0,
        minutesByMonth = minutesByMonth,
        topTitles = perTitle.entries.sortedByDescending { it.value }.take(3).mapNotNull { (key, count) -> entries[key]?.let { TitleOfYear(it.anime, count) } },
        topGenres = genres.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        achievements = achievements.filter { progress -> progress.unlockedAt?.let { dayToDate(offsetDay(it)).year == year } == true },
    )
}
