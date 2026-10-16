package app.animix.data.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.awt.Canvas
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.JPanel
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

actual fun createNativePlayer(): NativePlayer = DesktopNativePlayer()

internal fun nativeMediaFactory(vararg options: String): MediaPlayerFactory {
    val directories = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
        System.getProperty("animix.vlc.dir")?.let(::File),
        File("desktopApp/build/native-resources/windows/vlc"),
    )
    val bundled = directories.firstOrNull { File(it, "libvlc.dll").isFile }
    val discovery = if (bundled != null) NativeDiscovery(object : NativeDiscoveryStrategy {
        override fun supported() = true
        override fun discover() = bundled.absolutePath
        override fun onFound(path: String) = true
        override fun onSetPluginPath(path: String) = WindowsNativeDiscoveryStrategy().onSetPluginPath(path)
    }) else NativeDiscovery()
    return MediaPlayerFactory(discovery, "--no-video-title-show", "--quiet", "--network-caching=1500", *options)
}

internal class DesktopNativePlayer : NativePlayer {
    private val factory = nativeMediaFactory()
    internal val engine = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val canvas = Canvas().apply { background = java.awt.Color.BLACK }
    val component = JPanel(BorderLayout()).apply { background = java.awt.Color.BLACK; add(canvas, BorderLayout.CENTER) }
    private val mutable = MutableStateFlow(PlayerSnapshot())
    override val state = mutable.asStateFlow()
    private var initialPosition = 0L
    private var startPaused = false
    private var released = false
    private var pending: (() -> Unit)? = null
    private var source: Pair<String, Map<String, String>>? = null

    init {
        canvas.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !released) {
                if (canvas.isDisplayable) { val start = pending; pending = null; start?.invoke() }
                else if (source != null) {
                    // A fullscreen window owns a different native HWND. Reopen on the new surface.
                    val snapshot = state.value
                    engine.controls().stop()
                    val (url, headers) = source!!
                    load(url, headers, snapshot.positionMs, snapshot.playing)
                }
            }
        }
        engine.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
        engine.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                mutable.update { it.copy(playing = true, ready = true, buffering = false) }
                // LibVLC callbacks must not synchronously re-enter the player controls.
                mediaPlayer.submit {
                    if (initialPosition > 0) { mediaPlayer.controls().setTime(initialPosition); initialPosition = 0 }
                    if (startPaused) { startPaused = false; mediaPlayer.controls().setPause(true) }
                }
            }
            override fun paused(mediaPlayer: MediaPlayer) { mutable.update { it.copy(playing = false, buffering = false) } }
            override fun finished(mediaPlayer: MediaPlayer) { mutable.update { it.copy(playing = false, buffering = false, ended = true, positionMs = it.durationMs) } }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { mutable.update { it.copy(buffering = newCache < 100f) } }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { mutable.update { it.copy(positionMs = newTime.coerceAtLeast(0)) } }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { mutable.update { it.copy(durationMs = newLength.coerceAtLeast(0)) } }
            override fun error(mediaPlayer: MediaPlayer) { mutable.update { it.copy(playing = false, buffering = false, error = "Не удалось воспроизвести видео. Обновите ссылку или выберите другое качество.") } }
        })
    }
    override fun load(url: String, headers: Map<String, String>, startPositionMs: Long, playWhenReady: Boolean) {
        source = url to headers
        initialPosition = startPositionMs.coerceAtLeast(0)
        startPaused = !playWhenReady
        mutable.value = PlayerSnapshot(buffering = true, positionMs = initialPosition, volume = state.value.volume)
        val options = buildList {
            headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add(":http-user-agent=${it.value}") }
        }
        val start = {
            if (!engine.media().play(url, *options.toTypedArray())) mutable.update { it.copy(buffering = false, error = "Не удалось открыть видео.") }
            Unit
        }
        if (canvas.isDisplayable) start() else pending = start
    }
    override fun play() { if (state.value.ended) seekTo(0); engine.controls().play() }
    override fun pause() = engine.controls().setPause(true)
    override fun seekTo(positionMs: Long) { mutable.update { it.copy(ended = false) }; engine.controls().setTime(positionMs.coerceAtLeast(0)) }
    override fun setVolume(volume: Float) { val value = volume.coerceIn(0f, 1f); engine.audio().setVolume((value * 100).toInt()); mutable.update { it.copy(volume = value) } }
    override fun release() { if (!released) { released = true; pending = null; engine.controls().stop(); engine.release(); factory.release() } }
}

@Composable actual fun NativeVideoSurface(player: NativePlayer, modifier: Modifier) {
    SwingPanel(factory = { (player as DesktopNativePlayer).component }, modifier = modifier)
}

@Composable actual fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Window(onCloseRequest = onDismiss, title = "AniMix · Плеер", state = rememberWindowState(placement = WindowPlacement.Fullscreen)) { content() }
}
