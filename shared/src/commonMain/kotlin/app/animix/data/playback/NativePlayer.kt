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
)

interface NativePlayer {
    val state: StateFlow<PlayerSnapshot>
    fun load(url: String, headers: Map<String, String>, startPositionMs: Long = 0, playWhenReady: Boolean = true)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun release()
}

expect fun createNativePlayer(): NativePlayer
@Composable expect fun NativeVideoSurface(player: NativePlayer, modifier: Modifier)
@Composable expect fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit)
