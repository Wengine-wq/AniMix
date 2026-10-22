package app.animix.data.library

import kotlinx.serialization.Serializable

/** Where a stretch of playback came from, for the year statistics. */
data class PlaybackContext(val studio: String, val offline: Boolean, val platform: String)

/**
 * How AniMix was used during one calendar year: everything «Итоги года» shows beyond the episode
 * counts. Only real playback adds to it (the same validated time as the daily statistics).
 */
@Serializable
data class YearUsage(
    /** Seconds by local hour of the day, 0..23. */
    val hours: List<Int> = List(24) { 0 },
    /** Seconds by day of the week, Monday first. */
    val weekdays: List<Int> = List(7) { 0 },
    val offlineSeconds: Int = 0,
    val onlineSeconds: Int = 0,
    /** Seconds per dubbing studio. */
    val studios: Map<String, Int> = emptyMap(),
    /** Seconds per device kind (Android, Windows). */
    val platforms: Map<String, Int> = emptyMap(),
    /** Seconds per anime key. */
    val titleSeconds: Map<String, Int> = emptyMap(),
    /** Day the title was first played this year, per anime key. */
    val firstPlayed: Map<String, Long> = emptyMap(),
    val sessions: Int = 0,
    val longestSessionSeconds: Int = 0,
    val currentSessionSeconds: Int = 0,
    val lastPlaybackAt: Long = 0,
    val skips: Int = 0,
    val skippedSeconds: Int = 0,
) {
    /** Adds [seconds] of playback that ended at [at]; a gap over 20 minutes starts a new session. */
    fun add(seconds: Int, at: Long, hour: Int, weekday: Int, day: Long, animeKey: String, context: PlaybackContext?): YearUsage {
        if (seconds <= 0) return this
        val newSession = lastPlaybackAt == 0L || at - lastPlaybackAt > SESSION_GAP_MS
        val session = (if (newSession) 0 else currentSessionSeconds) + seconds
        fun List<Int>.plusAt(index: Int) = mapIndexed { i, value -> if (i == index) value + seconds else value }
        fun Map<String, Int>.plusFor(key: String) = this + (key to ((this[key] ?: 0) + seconds))
        return copy(
            hours = hours.plusAt(hour.coerceIn(0, 23)),
            weekdays = weekdays.plusAt(weekday.coerceIn(0, 6)),
            offlineSeconds = offlineSeconds + if (context?.offline == true) seconds else 0,
            onlineSeconds = onlineSeconds + if (context?.offline == true) 0 else seconds,
            studios = context?.studio?.takeIf { it.isNotBlank() }?.let { studios.plusFor(it) } ?: studios,
            platforms = context?.platform?.let { platforms.plusFor(it) } ?: platforms,
            titleSeconds = titleSeconds.plusFor(animeKey),
            firstPlayed = if (animeKey in firstPlayed) firstPlayed else firstPlayed + (animeKey to day),
            sessions = sessions + if (newSession) 1 else 0,
            currentSessionSeconds = session,
            longestSessionSeconds = maxOf(longestSessionSeconds, session),
            lastPlaybackAt = at,
        )
    }

    private companion object {
        const val SESSION_GAP_MS = 20 * 60_000L
    }
}
