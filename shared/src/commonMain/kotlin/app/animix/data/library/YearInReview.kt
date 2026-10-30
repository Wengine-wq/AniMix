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
    /** Seconds watched on every day of the year, for the year heatmap. */
    val days: Map<Long, Int> = emptyMap(),
    val usage: YearUsage = YearUsage(),
    /** Top titles by watched time, with seconds. */
    val titlesByTime: List<Pair<Anime, Int>> = emptyList(),
) {
    val empty: Boolean get() = minutes == 0 && episodes == 0
    val peakHour: Int? get() = usage.hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    val peakWeekday: Int? get() = usage.weekdays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
    val favoriteStudio: Pair<String, Int>? get() = usage.studios.maxByOrNull { it.value }?.toPair()
    val offlineShare: Float get() = (usage.offlineSeconds + usage.onlineSeconds).takeIf { it > 0 }?.let { usage.offlineSeconds.toFloat() / it } ?: 0f
    val newTitles: Int get() = usage.firstPlayed.size

    /** A name for when the viewer watches most: by the busiest hour, then by weekend or weekday. */
    val persona: String? get() = when (peakHour) {
        null -> null
        in 0..4 -> "Ночной зритель"
        in 5..10 -> "Утренний зритель"
        in 11..16 -> "Дневной зритель"
        in 17..20 -> "Вечерний зритель"
        else -> "Полуночник"
    }
}

/** When «Итоги года» open: at the end of December for everyone, earlier after a few titles. */
data class ReviewAccess(
    val year: Int,
    val open: Boolean,
    val early: Boolean,
    val titlesWatched: Int,
    val titlesNeeded: Int,
    val opensOn: LocalDay,
)

/** From 20 December through January the review of the ending year is open; three titles watched in AniMix open it early. */
fun LibraryData.reviewAccess(today: LocalDay, offsetDay: (Long) -> Long = { localDayIndex(it) }): ReviewAccess {
    val year = if (today.month == 1) today.year - 1 else today.year
    val season = (today.month == 12 && today.day >= REVIEW_OPENS_DAY) || today.month == 1
    val titles = entries.values.count { entry ->
        entry.viewing.values.any { viewing -> viewing.completedAt?.let { dayToDate(offsetDay(it)).year == year } == true }
    }
    return ReviewAccess(
        year = year,
        open = season || titles >= EARLY_TITLES,
        early = !season && titles >= EARLY_TITLES,
        titlesWatched = titles,
        titlesNeeded = EARLY_TITLES,
        opensOn = LocalDay(year, 12, REVIEW_OPENS_DAY),
    )
}

const val REVIEW_OPENS_DAY = 20
const val EARLY_TITLES = 3

fun LibraryData.yearInReview(year: Int, achievements: List<AchievementProgress>, offsetDay: (Long) -> Long = { localDayIndex(it) }): YearInReview {
    val days = plausibleWatchDays().filterKeys { dayToDate(it).year == year }
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
        days = days,
        usage = usage[year] ?: YearUsage(),
        titlesByTime = (usage[year]?.titleSeconds ?: emptyMap()).entries.sortedByDescending { it.value }.take(5)
            .mapNotNull { (key, seconds) -> entries[key]?.let { it.anime to seconds } },
    )
}
