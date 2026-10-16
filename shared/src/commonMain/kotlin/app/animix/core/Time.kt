package app.animix.core

import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Days since 1970-01-01 in the device's time zone; used for daily watch statistics. */
expect fun localDayIndex(millis: Long = nowMillis()): Long
