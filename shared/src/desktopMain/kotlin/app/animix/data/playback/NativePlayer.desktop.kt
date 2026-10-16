package app.animix.data.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.nio.ByteBuffer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * Decoded frames handed from libVLC to Compose. libVLC renders into memory instead of
 * a native window, so AniMix controls can be drawn over the picture like on Android.
 */
internal class FrameSink {
    private val lock = Any()
    private var pixels = ByteArray(0)
    private var width = 0
    private var height = 0
    private var fresh = false
    private var image: Image? = null
    private val counter = MutableStateFlow(0L)

    /** Ticks once per decoded frame; the surface redraws on each tick. */
    val frames: StateFlow<Long> = counter.asStateFlow()

    fun format(width: Int, height: Int) = synchronized(lock) {
        this.width = width
        this.height = height
        pixels = ByteArray(width * height * 4)
        fresh = false
    }

    /** libVLC thread: copy the frame out before the native buffer is reused. */
    fun write(buffer: ByteBuffer) {
        synchronized(lock) {
            if (pixels.isEmpty() || buffer.capacity() < pixels.size) return
            buffer.rewind()
            buffer.get(pixels, 0, pixels.size)
            buffer.rewind()
            fresh = true
        }
        counter.update { it + 1 }
    }

    /** UI thread: the latest frame, kept so a paused picture survives a new surface. */
    fun current(): Image? = synchronized(lock) {
        if (fresh && width > 0 && height > 0) {
            fresh = false
            val next = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, width * 4)
            image?.close()
            image = next
        }
        image
    }

    fun clear() = synchronized(lock) {
        image?.close()
        image = null
        fresh = false
    }
}

internal class DesktopNativePlayer : NativePlayer {
    private val factory = nativeMediaFactory()
    internal val engine = factory.mediaPlayers().newEmbeddedMediaPlayer()
    internal val frames = FrameSink()
    private val mutable = MutableStateFlow(PlayerSnapshot())
    override val state = mutable.asStateFlow()
    private var initialPosition = 0L
    private var startPaused = false
    private var released = false

    init {
        engine.videoSurface().set(factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    frames.format(sourceWidth, sourceHeight)
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
            },
            object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) = Unit
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                    nativeBuffers.firstOrNull()?.let(frames::write)
                }
                override fun unlock(mediaPlayer: MediaPlayer) = Unit
            },
            true,
        ))
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
        if (released) return
        initialPosition = startPositionMs.coerceAtLeast(0)
        startPaused = !playWhenReady
        mutable.value = PlayerSnapshot(buffering = true, positionMs = initialPosition, volume = state.value.volume)
        val options = buildList {
            headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add(":http-user-agent=${it.value}") }
        }
        if (!engine.media().play(url, *options.toTypedArray())) mutable.update { it.copy(buffering = false, error = "Не удалось открыть видео.") }
    }
    override fun play() { if (state.value.ended) seekTo(0); engine.controls().play() }
    override fun pause() = engine.controls().setPause(true)
    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        initialPosition = 0
        mutable.update { it.copy(ended = false, positionMs = target) }
        engine.controls().setTime(target)
    }
    override fun setVolume(volume: Float) { val value = volume.coerceIn(0f, 1f); engine.audio().setVolume((value * 100).toInt()); mutable.update { it.copy(volume = value) } }
    override fun release() { if (!released) { released = true; engine.controls().stop(); engine.release(); factory.release(); frames.clear() } }
}

private val videoSampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

@Composable actual fun NativeVideoSurface(player: NativePlayer, modifier: Modifier) {
    val sink = (player as DesktopNativePlayer).frames
    val tick by sink.frames.collectAsState()
    Canvas(modifier.background(Color.Black)) {
        tick
        val image = sink.current() ?: return@Canvas
        val scale = minOf(size.width / image.width, size.height / image.height)
        val width = image.width * scale
        val height = image.height * scale
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height),
                videoSampling,
                null,
                true,
            )
        }
    }
}

@Composable actual fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Window(onCloseRequest = onDismiss, title = "AniMix · Плеер", undecorated = true, state = rememberWindowState(placement = WindowPlacement.Fullscreen)) { content() }
}
