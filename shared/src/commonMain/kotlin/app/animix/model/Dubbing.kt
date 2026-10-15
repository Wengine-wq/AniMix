package app.animix.model

import kotlinx.serialization.Serializable

/** A time range to skip, in seconds from the start of the episode. */
@Serializable
data class SkipRange(val start: Int, val length: Int) {
    val end: Int get() = start + length
}

@Serializable
data class Episode(
    val number: String,
    val videoId: Long?,
    val iframeUrl: String,
    val durationSeconds: Int?,
    val opening: SkipRange?,
    val ending: SkipRange?,
) {
    val sortKey: Double get() = number.toDoubleOrNull() ?: Double.MAX_VALUE
}

/** One dubbing studio served by one player, with its episodes in order. */
@Serializable
data class Dubbing(
    val studio: String,
    val player: String,
    val episodes: List<Episode>,
) {
    val isKodik: Boolean get() = player.contains("kodik", ignoreCase = true)
    val key: String get() = "${player.lowercase()}|${studio.lowercase()}"
}

/** Where an anime can be watched, resolved from YummyAnime. */
@Serializable
data class WatchSource(
    val yummyId: Int,
    val title: String,
    val dubbings: List<Dubbing>,
)
