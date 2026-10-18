package app.animix.data.playback

import app.animix.data.library.PlaybackCredit
import app.animix.data.library.ViewedRange
import app.animix.data.library.mergeViewedRanges
import app.animix.model.SkipRange

data class PlaybackUpdate(val credit: PlaybackCredit, val durationMs: Long,
    val opening: SkipRange?, val ending: SkipRange?, val reachedEnd: Boolean)

/** A seek breaks only the sampling anchor. Previously watched intervals remain intact. */
class PlaybackTracker {
    private var previous: PlayerSnapshot? = null
    private var previousAt = 0L
    private var ranges = emptyList<ViewedRange>()
    private var elapsed = 0L

    fun discontinuity() { previous = null }

    fun sample(current: PlayerSnapshot, monotonicMs: Long) {
        val before = previous
        val wall = monotonicMs - previousAt
        previous = current
        previousAt = monotonicMs
        if (before == null || wall !in 1..3_000 || !before.playing || before.buffering || before.seeking ||
            !before.ready || current.buffering || current.seeking || current.error != null) return
        val media = current.positionMs - before.positionMs
        if (media <= 0 || media > wall + 500) return
        ranges = mergeViewedRanges(ranges + ViewedRange(before.positionMs, current.positionMs), current.durationMs)
        elapsed += minOf(wall, media)
    }

    fun drain(): PlaybackCredit = PlaybackCredit(ranges, elapsed).also { ranges = emptyList(); elapsed = 0 }
}
