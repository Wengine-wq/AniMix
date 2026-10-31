package app.animix.core

import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Days since 1970-01-01 in the device's time zone; used for daily watch statistics. */
expect fun localDayIndex(millis: Long = nowMillis()): Long

/** The device time zone offset at [millis], for local hours of the day. */
expect fun utcOffsetMillis(millis: Long): Long

/** Hour of the day 0..23 on this device. */
fun localHour(millis: Long = nowMillis()): Int = (Math_floorMod(millis + utcOffsetMillis(millis), 86_400_000L) / 3_600_000L).toInt()

/** "21:05", "02:42" in the device's time zone. */
fun localTimeLabel(millis: Long): String {
    val minutes = Math_floorMod(millis + utcOffsetMillis(millis), 86_400_000L) / 60_000L
    return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** Day of the week for a day index: 0 is Monday (1970-01-01 was a Thursday). */
fun weekdayOf(day: Long): Int = Math_floorMod(day + 3, 7L).toInt()

private fun Math_floorMod(value: Long, modulus: Long): Long = ((value % modulus) + modulus) % modulus
