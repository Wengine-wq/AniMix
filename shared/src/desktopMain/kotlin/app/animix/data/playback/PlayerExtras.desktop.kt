package app.animix.data.playback

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PictureInPicture {
    actual val supported: Boolean = false
    actual val active: StateFlow<Boolean> = MutableStateFlow(false)
    actual var eligible: Boolean = false
    actual fun enter(): Boolean = false
}

@Composable actual fun rememberPlayerHardware(): PlayerHardware? = null
