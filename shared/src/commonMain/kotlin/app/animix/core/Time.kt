package app.animix.core

import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
