package app.animix.data.playback

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * «Картинка в картинке» of «Плеер+»: the video keeps playing in a small window over other apps.
 * Android only; elsewhere [supported] is false and nothing changes.
 */
expect object PictureInPicture {
    val supported: Boolean
    /** True while the app is shown as the small window: screens show only the video. */
    val active: StateFlow<Boolean>
    /** Set by a playing «Плеер+» on the watch screen: leaving the app then continues in a small window. */
    var eligible: Boolean
    fun enter(): Boolean
}

/** Window brightness and media volume for the vertical swipes of «Плеер+» in fullscreen. */
interface PlayerHardware {
    /** 0..1; the screen's own brightness until the first swipe. */
    var brightness: Float
    /** 0..1 of the device's media volume. */
    var volume: Float
}

/** The current window's controls on a touch device, null on desktop (there the wheel and keys set the volume). */
@Composable expect fun rememberPlayerHardware(): PlayerHardware?
