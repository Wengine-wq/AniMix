package app.animix.core

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Wall time for rewards that the device clock cannot rewind or fast-forward: the last server
 * `Date` seen over HTTP plus monotonic time since then. Before any response (offline start)
 * it is the device clock, so offline clock changes are the only remaining gap.
 */
object TrustedClock {
    private val start = TimeSource.Monotonic.markNow()
    private class Anchor(val serverMillis: Long, val monotonicMillis: Long)
    @Volatile private var anchor: Anchor? = null

    fun observeServerTime(serverMillis: Long) {
        if (serverMillis < EARLIEST) return
        anchor = Anchor(serverMillis, start.elapsedNow().inWholeMilliseconds)
    }

    fun now(): Long = anchor?.let { it.serverMillis + (start.elapsedNow().inWholeMilliseconds - it.monotonicMillis) } ?: nowMillis()

    /** Tests and resets: forget the server anchor. */
    internal fun reset() { anchor = null }

    /** 1 January 2025. */
    private const val EARLIEST = 1_735_689_600_000L
}
