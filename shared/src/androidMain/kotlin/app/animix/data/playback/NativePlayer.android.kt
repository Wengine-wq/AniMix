package app.animix.data.playback

import app.animix.core.AppLog

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.animix.core.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

actual fun createNativePlayer(): NativePlayer = AndroidNativePlayer()

@androidx.annotation.OptIn(UnstableApi::class)
internal class AndroidNativePlayer : NativePlayer {
    private val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    val engine = ExoPlayer.Builder(AndroidPlatform.context).setMediaSourceFactory(
        DefaultMediaSourceFactory(DefaultDataSource.Factory(AndroidPlatform.context, http)),
    ).build()
    private val mutable = MutableStateFlow(PlayerSnapshot())
    override val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The SurfaceView showing this player, for frame copies (glass and ambient glow). */
    internal val surface = MutableStateFlow<SurfaceView?>(null)
    private var released = false

    init {
        engine.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) {
                AppLog.failure("Playback", "exo_error_${error.errorCode}", error)
                mutable.update { it.copy(playing = false, buffering = false, error = "Не удалось воспроизвести видео. Обновите ссылку или выберите другое качество.") }
            }
        })
        scope.launch { while (true) { publish(); delay(250) } }
    }
    private fun publish() {
        if (released) return
        mutable.update { it.copy(
            playing = engine.isPlaying,
            playRequested = engine.playWhenReady && engine.playbackState != Player.STATE_ENDED,
            buffering = engine.playbackState == Player.STATE_BUFFERING,
            ready = engine.playbackState == Player.STATE_READY,
            ended = engine.playbackState == Player.STATE_ENDED,
            seeking = false,
            positionMs = engine.currentPosition.coerceAtLeast(0),
            durationMs = engine.duration.takeUnless { value -> value == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            volume = engine.volume,
            videoWidth = engine.videoSize.let { size -> (size.width * size.pixelWidthHeightRatio).toInt() },
            videoHeight = engine.videoSize.height,
        ) }
    }
    override fun load(url: String, headers: Map<String, String>, startPositionMs: Long, playWhenReady: Boolean) {
        http.setDefaultRequestProperties(headers)
        mutable.value = PlayerSnapshot(buffering = true, playRequested = playWhenReady, positionMs = startPositionMs, volume = engine.volume)
        engine.setMediaItem(MediaItem.fromUri(url), startPositionMs.coerceAtLeast(0))
        engine.playWhenReady = playWhenReady
        engine.prepare()
    }
    override fun play() { if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0); engine.play(); publish() }
    override fun pause() { engine.pause(); publish() }
    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0, state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        mutable.update { it.copy(positionMs = target, ended = false, seeking = true) }
        engine.seekTo(target)
    }
    override fun setVolume(volume: Float) { engine.volume = volume.coerceIn(0f, 1f); publish() }
    override fun release() { if (!released) { released = true; scope.cancel(); engine.release() } }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable actual fun NativeVideoSurface(player: NativePlayer, modifier: Modifier, ambient: Boolean) {
    val backend = player as AndroidNativePlayer
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val glow = rememberFrameBackdrop(player, ambient)
    Box(modifier) {
        // The copy is already blurred; a new one fades in over the last, so the bars change smoothly.
        if (glow != null) {
            androidx.compose.animation.Crossfade(glow, animationSpec = androidx.compose.animation.core.tween(600), label = "glow") { picture ->
                if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        AndroidView(
            factory = { context -> PlayerView(context).apply {
                useController = false
                this.player = backend.engine
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                keepScreenOn = true
                // Transparent around the fitted picture, so the glow underneath can show in the bars.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                backend.surface.value = videoSurfaceView as? SurfaceView
            } },
            update = { it.player = backend.engine },
            onRelease = { it.player = null; backend.surface.value = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PixelCopy of the SurfaceView into a 48×27 bitmap, blurred on the CPU, about twice a second. */
@Composable actual fun rememberFrameBackdrop(player: NativePlayer, enabled: Boolean): ImageBitmap? {
    val backend = player as AndroidNativePlayer
    val surface by backend.surface.collectAsState()
    var backdrop by remember(player) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(enabled, surface) {
        val view = surface
        if (!enabled || view == null) { backdrop = null; return@LaunchedEffect }
        val handler = Handler(Looper.getMainLooper())
        while (isActive) {
            if (view.holder.surface?.isValid == true && view.width > 0) {
                val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
                val copied = suspendCancellableCoroutine { done ->
                    runCatching { PixelCopy.request(view, bitmap, { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) }, handler) }
                        .onFailure { if (done.isActive) done.resume(false) }
                }
                if (copied) backdrop = softened(bitmap).asImageBitmap()
            }
            delay(600)
        }
    }
    return backdrop
}

/** Two passes of a 3×3 box blur on the 48×27 copy: a soft glow once stretched, without RenderEffect. */
private fun softened(bitmap: Bitmap): Bitmap {
    val w = bitmap.width; val h = bitmap.height
    var pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    repeat(2) {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val px = (x + dx).coerceIn(0, w - 1); val py = (y + dy).coerceIn(0, h - 1)
                val c = pixels[py * w + px]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        pixels = out
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    return bitmap
}

@Composable actual fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        // Landscape and edge-to-edge while the player is big; the previous mode returns afterwards.
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity == null || previous == null) return@onDispose
            // "Unspecified" would keep a phone held sideways in landscape after leaving the player.
            val phone = activity.resources.configuration.smallestScreenWidthDp < 600
            activity.requestedOrientation = if (phone && previous == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else previous
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        content()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
