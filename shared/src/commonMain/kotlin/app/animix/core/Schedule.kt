package app.animix.core

/** Days since 1970-01-01 of a calendar date (Howard Hinnant's days-from-civil), the reverse of `dayToDate`. */
fun dayIndexOf(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * ((month + 9) % 12) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

private val WeekdaysOn = listOf("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье")
private val MonthsOf = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

/**
 * When the next episode airs, from the catalog's ISO time: «сегодня», «завтра», «в четверг, 13 ноября»
 * within a week, «13 ноября» later. Null for a date that has passed or cannot be read: a stale date is
 * worse than none.
 */
fun airingDayLabel(iso: String?, today: Long = localDayIndex()): String? {
    if (iso == null || iso.length < 10) return null
    val year = iso.substring(0, 4).toIntOrNull() ?: return null
    val month = iso.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = iso.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val date = "$day ${MonthsOf[month - 1]}"
    return when (val ahead = dayIndexOf(year, month, day) - today) {
        0L -> "сегодня"
        1L -> "завтра"
        in 2L..6L -> "${WeekdaysOn[weekdayOf(today + ahead)]}, $date"
        else -> if (ahead < 0) null else date
    }
}

private val WeekdaysShort = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** Days from [today] to the catalog's ISO date; null when it cannot be read. */
fun daysUntil(iso: String?, today: Long = localDayIndex()): Long? {
    if (iso == null || iso.length < 10) return null
    val year = iso.substring(0, 4).toIntOrNull() ?: return null
    val month = iso.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = iso.substring(8, 10).toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    return dayIndexOf(year, month, day) - today
}

/** The day of a coming episode on the week line: «Сегодня», «Завтра», «Чт 13»; null outside the next seven days. */
fun weekDayLabel(iso: String?, today: Long = localDayIndex()): String? = when (val ahead = daysUntil(iso, today)) {
    null -> null
    0L -> "Сегодня"
    1L -> "Завтра"
    in 2L..6L -> "${WeekdaysShort[weekdayOf(today + ahead)]} ${iso!!.substring(8, 10).trimStart('0')}"
    else -> null
}

/** «Серия 7 выйдет в четверг, 13 ноября» for a title still airing; null when the catalog has no coming date. */
fun nextEpisodeLine(episodesAired: Int, nextEpisodeAt: String?, today: Long = localDayIndex()): String? =
    airingDayLabel(nextEpisodeAt, today)?.let { "Серия ${episodesAired + 1} выйдет $it" }
