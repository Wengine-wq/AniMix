package app.animix.data.playback

import app.animix.core.AppLog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.*
import kotlin.math.abs

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

    /** A frame built on the libVLC thread and not yet shown; the UI swaps it in without copying. */
    private var pending: Image? = null

    /**
     * libVLC thread: copy the frame out before the native buffer is reused and turn it into an image
     * right here, so the UI thread never spends a frame copying pixels. A frame the UI had no time to
     * show is dropped in favour of the newer one.
     */
    fun write(buffer: ByteBuffer) {
        synchronized(lock) {
            if (pixels.isEmpty() || buffer.capacity() < pixels.size) return
            buffer.rewind()
            buffer.get(pixels, 0, pixels.size)
            buffer.rewind()
            val next = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, width * 4)
            pending?.close()
            pending = next
            fresh = true
        }
        counter.update { it + 1 }
    }

    /** UI thread: the latest frame, kept so a paused picture survives a new surface. */
    fun current(): Image? = synchronized(lock) {
        if (fresh) {
            fresh = false
            pending?.let { next -> image?.close(); image = next; pending = null; imageSerial++ }
        }
        image
    }
    private var imageSerial = 0L
    private var glow: Image? = null
    private var glowPrevious: Image? = null
    private var glowAt = 0L
    private var glowSerial = -1L

    /**
     * UI thread: a tiny, already blurred copy of the latest frame for the ambient glow and the
     * player's glass, refreshed about twice a second. The blur runs on 48×27 pixels, never on the
     * full frame, and drawing it stretched costs nothing per frame. A paused picture keeps its glow:
     * nothing is rebuilt or faded until a new frame is shown.
     */
    fun glow(): Image? {
        val source = image ?: return glow
        val now = System.nanoTime()
        if (glow != null && (now - glowAt < GLOW_REFRESH_NANOS || glowSerial == imageSerial)) return glow
        glowAt = now
        glowSerial = imageSerial
        val small = org.jetbrains.skia.Surface.makeRasterN32Premul(GLOW_WIDTH, GLOW_HEIGHT)
        small.canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(GLOW_WIDTH.toFloat(), GLOW_HEIGHT.toFloat()), videoSampling, null, true)
        val sharp = small.makeImageSnapshot()
        val soft = org.jetbrains.skia.Surface.makeRasterN32Premul(GLOW_WIDTH, GLOW_HEIGHT)
        soft.canvas.drawImage(sharp, 0f, 0f, glowBlur)
        sharp.close(); small.close()
        glowPrevious?.close()
        glowPrevious = glow
        glow = soft.makeImageSnapshot()
        soft.close()
        return glow
    }

    /** The glow before the last refresh and how far (0..1) the new one has faded in over it. */
    fun glowFade(): Pair<Image?, Float> =
        glowPrevious to ((System.nanoTime() - glowAt).toFloat() / GLOW_REFRESH_NANOS).coerceIn(0f, 1f)
    fun clear() = synchronized(lock) {
        image?.close()
        image = null
        pending?.close()
        pending = null
        glow?.close()
        glow = null
        glowPrevious?.close()
        glowPrevious = null
        glowSerial = -1L
        fresh = false
    }
}

internal class DesktopNativePlayer : NativePlayer {
    private val factory = nativeMediaFactory()
    internal val engine = factory.mediaPlayers().newEmbeddedMediaPlayer()
    internal val frames = FrameSink()
    private val mutable = MutableStateFlow(PlayerSnapshot())
    override val state = mutable.asStateFlow()
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val closed = CompletableDeferred<Unit>()
    @Volatile
    private var initialPosition = 0L
    @Volatile
    private var startPaused = false
    @Volatile
    private var released = false
    @Volatile private var seekTarget: Long? = null
    @Volatile private var seekStarted = 0L
    @Volatile private var requestedPlay = false
    @Volatile private var rate = 1f

    init {
        engine.videoSurface().set(factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    frames.format(sourceWidth, sourceHeight)
                    mutable.update { it.copy(videoWidth = sourceWidth, videoHeight = sourceHeight) }
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
                mutable.update { it.copy(playing = true, playRequested = requestedPlay, ready = true, buffering = false) }
                // LibVLC callbacks must not synchronously re-enter the player controls.
                mediaPlayer.submit {
                    // A new file starts at 1×: the chosen rate is applied again.
                    if (rate != 1f) mediaPlayer.controls().setRate(rate)
                    if (initialPosition > 0) { mediaPlayer.controls().setTime(initialPosition); initialPosition = 0 }
                    if (startPaused) { startPaused = false; mediaPlayer.controls().setPause(true) }
                }
            }
            override fun paused(mediaPlayer: MediaPlayer) { mutable.update { it.copy(playing = false, buffering = false) } }
            override fun finished(mediaPlayer: MediaPlayer) { requestedPlay = false; mutable.update { it.copy(playing = false, playRequested = false, buffering = false, ended = true, positionMs = it.durationMs) } }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { mutable.update { it.copy(buffering = newCache < 100f) } }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                val target = seekTarget
                if (target != null && abs(newTime - target) > 1_500 && System.nanoTime() - seekStarted < 3_000_000_000L) return
                seekTarget = null
                mutable.update { it.copy(positionMs = newTime.coerceAtLeast(0), seeking = false) }
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { mutable.update { it.copy(durationMs = newLength.coerceAtLeast(0)) } }
            override fun error(mediaPlayer: MediaPlayer) { AppLog.failure("Playback", "vlc_decoder", IllegalStateException()); mutable.update { it.copy(playing = false, buffering = false, error = "Не удалось воспроизвести видео. Обновите ссылку или выберите другое качество.") } }
        })
    }
    override fun load(url: String, headers: Map<String, String>, startPositionMs: Long, playWhenReady: Boolean) {
        if (released) return
        initialPosition = startPositionMs.coerceAtLeast(0)
        startPaused = !playWhenReady
        requestedPlay = playWhenReady
        mutable.value = PlayerSnapshot(buffering = true, playRequested = playWhenReady, positionMs = initialPosition, volume = state.value.volume, speed = rate)
        val options = buildList {
            headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add(":http-user-agent=${it.value}") }
        }
        seekTarget = null
        commands.launch {
            if (released) return@launch
            val started = runCatching { engine.media().play(url, *options.toTypedArray()) }.getOrDefault(false)
            if (!started) {
                AppLog.failure("Playback", "vlc_open", IllegalStateException())
                mutable.update { it.copy(playing = false, playRequested = false, buffering = false, error = "Не удалось открыть видео.") }
            }
        }
    }
    override fun play() {
        if (released) return
        requestedPlay = true
        if (state.value.ended) seekTo(0)
        mutable.update { it.copy(playing = true, playRequested = true, ended = false) }
        commands.launch { if (!released) engine.controls().play() }
    }
    override fun pause() {
        if (released) return
        requestedPlay = false
        mutable.update { it.copy(playing = false, playRequested = false) }
        commands.launch { if (!released) engine.controls().setPause(true) }
    }
    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        if (released) return
        initialPosition = 0
        seekTarget = target
        seekStarted = System.nanoTime()
        mutable.update { it.copy(ended = false, positionMs = target, seeking = true) }
        commands.launch { if (!released) engine.controls().setTime(target) }
    }
    override fun setVolume(volume: Float) {
        if (released) return
        val value = volume.coerceIn(0f, 1f)
        mutable.update { it.copy(volume = value) }
        commands.launch { if (!released) engine.audio().setVolume((value * 100).toInt()) }
    }
    override fun setSpeed(speed: Float) {
        if (released) return
        rate = speed.coerceIn(0.25f, 2f)
        mutable.update { it.copy(speed = rate) }
        commands.launch { if (!released) engine.controls().setRate(rate) }
    }
    override fun release() {
        if (released) return
        released = true
        commands.launch {
            try {
                try { engine.controls().stop() } finally {
                    try { engine.release() } finally { factory.release(); frames.clear() }
                }
            }
            finally { closed.complete(Unit); commands.cancel() }
        }
    }
    internal suspend fun awaitReleased() = closed.await()
}

private val videoSampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

private const val GLOW_WIDTH = 48
private const val GLOW_HEIGHT = 27
// Lazy: loading this file (e.g. for the audio player in tests) must not touch Skia's native library.
private const val GLOW_REFRESH_NANOS = 600_000_000L
// Blur on the 48×27 copy: a couple of its pixels is a wide, soft glow once stretched over the bars.
private val glowBlur by lazy {
    org.jetbrains.skia.Paint().apply { imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(2.5f, 2.5f, org.jetbrains.skia.FilterTileMode.CLAMP) }
}
private val glowFadePaint by lazy { org.jetbrains.skia.Paint() }
private val glowScrim by lazy { org.jetbrains.skia.Paint().apply { color = 0x59000000 } }

@Composable actual fun NativeVideoSurface(player: NativePlayer, modifier: Modifier, ambient: Boolean) {
    val sink = (player as DesktopNativePlayer).frames
    val tick by sink.frames.collectAsState()
    Canvas(modifier.background(Color.Black)) {
        tick
        val image = sink.current() ?: return@Canvas
        val scale = minOf(size.width / image.width, size.height / image.height)
        val width = image.width * scale
        val height = image.height * scale
        // Bars of at least a few pixels glow with the scene; the picture itself is drawn on top.
        if (ambient && (size.width - width > 8f || size.height - height > 8f)) sink.glow()?.let { glow ->
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val target = Rect.makeXYWH(-size.width * 0.1f, -size.height * 0.1f, size.width * 1.2f, size.height * 1.2f)
                // The new glow fades in over the previous one, so scene changes never flash in the bars.
                val (previous, fade) = sink.glowFade()
                if (previous != null && fade < 1f) native.drawImageRect(previous, Rect.makeWH(previous.width.toFloat(), previous.height.toFloat()), target, videoSampling, null, true)
                glowFadePaint.alpha = if (previous != null) (fade * 255).toInt() else 255
                native.drawImageRect(glow, Rect.makeWH(glow.width.toFloat(), glow.height.toFloat()), target, videoSampling, glowFadePaint, true)
                native.drawRect(Rect.makeWH(size.width, size.height), glowScrim)
            }
        }
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

@Composable actual fun rememberFrameBackdrop(player: NativePlayer, enabled: Boolean): androidx.compose.ui.graphics.ImageBitmap? {
    val sink = (player as DesktopNativePlayer).frames
    var backdrop by androidx.compose.runtime.remember(player) { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(player, enabled) {
        if (!enabled) { backdrop = null; return@LaunchedEffect }
        var shown: org.jetbrains.skia.Image? = null
        while (isActive) {
            // current() refreshes the full frame on the UI thread; glow() then scales it down.
            // The same glow (a paused picture) is not copied again, so the glass never flickers.
            sink.current()
            sink.glow()?.takeIf { it !== shown }?.let { shown = it; backdrop = org.jetbrains.skia.Bitmap.makeFromImage(it).asComposeImageBitmap() }
            delay(600)
        }
    }
    return backdrop
}

@Composable actual fun PlayerFullscreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Window(onCloseRequest = onDismiss, title = "AniMix · Плеер", undecorated = true, state = rememberWindowState(placement = WindowPlacement.Fullscreen)) { content() }
}
