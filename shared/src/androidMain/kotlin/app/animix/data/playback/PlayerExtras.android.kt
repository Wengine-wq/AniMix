package app.animix.data.playback

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import app.animix.core.AndroidPlatform
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PictureInPicture {
    private var activity: WeakReference<Activity>? = null
    private val mutable = MutableStateFlow(false)

    actual val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        AndroidPlatform.context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    actual val active: StateFlow<Boolean> = mutable
    actual var eligible: Boolean = false

    /** The activity that hosts the player; set from MainActivity.onCreate. */
    fun attach(host: Activity) { activity = WeakReference(host) }
    /** From MainActivity.onPictureInPictureModeChanged. */
    fun changed(inPicture: Boolean) { mutable.value = inPicture }

    actual fun enter(): Boolean {
        val host = activity?.get() ?: return false
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching { host.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            .getOrDefault(false)
    }
}

private class AndroidPlayerHardware(private val window: Window?, context: Context) : PlayerHardware {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val resolver = context.contentResolver
    var changed = false
        private set

    override var brightness: Float
        get() = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
            ?: runCatching { android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        set(value) {
            val target = window ?: return
            changed = true
            target.attributes = target.attributes.apply { screenBrightness = value.coerceIn(0.02f, 1f) }
        }

    override var volume: Float
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        set(value) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * max).let { kotlin.math.round(it).toInt() }, 0)
        }

    /** The app's own brightness ends with the player: the system setting takes over again. */
    fun restore() {
        val target = window ?: return
        if (changed) target.attributes = target.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
}

@Composable actual fun rememberPlayerHardware(): PlayerHardware? {
    val view = LocalView.current
    val context = LocalContext.current
    val hardware = remember(view) { AndroidPlayerHardware(view.window() ?: context.findActivity()?.window, context) }
    DisposableEffect(hardware) { onDispose { hardware.restore() } }
    return hardware
}

/** Inside the fullscreen player the window is the dialog's, not the activity's. */
private fun View.window(): Window? {
    var parent = parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
