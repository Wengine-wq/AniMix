package app.animix.data.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

data class PlayerSnapshot(
    val playing: Boolean = false,
    val playRequested: Boolean = false,
    val buffering: Boolean = false,
    val ready: Boolean = false,
    val ended: Boolean = false,
    val seeking: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val error: String? = null,
    /** Decoded picture size, 0 until the first frame. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Playback rate, 1 = normal; both players offer 0.75–2×. */
    val speed: Float = 1f,
)

interface NativePlayer {
    val state: StateFlow<PlayerSnapshot>
    fun load(url: String, headers: Map<String, String>, startPositionMs: Long = 0, playWhenReady: Boolean = true)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    /** Playback rate within 0.25–2×; kept for the next file loaded into this player. */
    fun setSpeed(speed: Float) {}
    /**
     * The picture on screen right now as a small JPEG ([width] pixels wide), for «Продолжить просмотр».
     * Null when there is no picture yet or it is almost black (a fade between scenes makes a poor cover).
     */
    suspend fun captureFrame(width: Int = 640): ByteArray? = null
    fun release()
}

expect fun createNativePlayer(): NativePlayer
/**
 * The decoded picture, fitted. With [ambient] the bars around a picture of another shape glow with
 * its own colours, softly blurred and dimmed, as in YouTube's ambient mode.
 */
@Composable expect fun NativeVideoSurface(player: NativePlayer, modifier: Modifier, ambient: Boolean = false)
/**
 * A tiny copy of the current frame, refreshed a few times a second while [enabled]: what the player's
 * glass capsules blur. Stretched over the picture it reads as frosted glass at almost no cost.
 */
@Composable expect fun rememberFrameBackdrop(player: NativePlayer, enabled: Boolean): androidx.compose.ui.graphics.ImageBitmap?
@Composable expect fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit)
