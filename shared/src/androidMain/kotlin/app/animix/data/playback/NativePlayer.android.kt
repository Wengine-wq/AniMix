package app.animix.data.playback

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
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
    private var released = false

    init {
        engine.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) {
                mutable.update { it.copy(playing = false, buffering = false, error = "Не удалось воспроизвести видео. Обновите ссылку или выберите другое качество.") }
            }
        })
        scope.launch { while (true) { publish(); delay(250) } }
    }
    private fun publish() {
        if (released) return
        mutable.update { it.copy(
            playing = engine.isPlaying,
            buffering = engine.playbackState == Player.STATE_BUFFERING,
            ready = engine.playbackState == Player.STATE_READY,
            ended = engine.playbackState == Player.STATE_ENDED,
            positionMs = engine.currentPosition.coerceAtLeast(0),
            durationMs = engine.duration.takeUnless { value -> value == C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            volume = engine.volume,
        ) }
    }
    override fun load(url: String, headers: Map<String, String>, startPositionMs: Long, playWhenReady: Boolean) {
        http.setDefaultRequestProperties(headers)
        mutable.value = PlayerSnapshot(buffering = true, positionMs = startPositionMs, volume = engine.volume)
        engine.setMediaItem(MediaItem.fromUri(url), startPositionMs.coerceAtLeast(0))
        engine.playWhenReady = playWhenReady
        engine.prepare()
    }
    override fun play() { if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0); engine.play() }
    override fun pause() = engine.pause()
    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))
    override fun setVolume(volume: Float) { engine.volume = volume.coerceIn(0f, 1f) }
    override fun release() { if (!released) { released = true; scope.cancel(); engine.release() } }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable actual fun NativeVideoSurface(player: NativePlayer, modifier: Modifier) {
    val backend = player as AndroidNativePlayer
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context -> PlayerView(context).apply {
            useController = false
            this.player = backend.engine
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            keepScreenOn = true
        } },
        update = { it.player = backend.engine },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable actual fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { content() }
}
