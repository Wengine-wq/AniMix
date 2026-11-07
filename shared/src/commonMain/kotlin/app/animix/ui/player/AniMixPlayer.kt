package app.animix.ui.player

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import app.animix.ui.theme.MotionVisibility as AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import app.animix.ui.components.LoadingIndicator as CircularProgressIndicator
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.animix.ui.components.Panel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.animix.ui.theme.AniMixMotion
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import app.animix.core.Platform
import app.animix.core.Settings
import app.animix.data.playback.*
import app.animix.data.skips.SkipPlan
import app.animix.model.Episode
import app.animix.model.SkipRange
import app.animix.ui.components.Txt
import app.animix.ui.components.pressScale
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlin.math.abs
import kotlinx.coroutines.isActive
import app.animix.ui.theme.LocalUiPerformance

internal fun skipTarget(positionMs: Long, range: SkipRange?): Long? = range?.takeIf {
    it.start >= 0 && it.length > 0 && positionMs >= it.start * 1000L && positionMs < it.end * 1000L
}?.let { it.end * 1000L }

private enum class SkipKind { Opening, Ending, Usual }

/** How long one skip offer stays on screen while the picture plays. */
internal const val SkipOfferMs = 10_000

/** Story-end boundary for resume/next-episode UI. Rewards additionally require actual coverage. */
internal fun watchedFromMs(durationMs: Long, ending: SkipRange?): Long? {
    if (durationMs <= 0) return null
    val endingStart = ending?.start?.times(1000L)?.takeIf { it > durationMs / 2 && it < durationMs }
    return endingStart ?: (durationMs * 0.92).toLong()
}

/**
 * Where to start an episode. A position saved inside the ending or the last minutes
 * means it was finished, so it starts over instead of ending at once.
 */
internal fun resumePositionMs(savedMs: Long, durationMs: Long?, ending: SkipRange?): Long {
    if (savedMs < 5_000) return 0
    val watchedFrom = durationMs?.let { watchedFromMs(it, ending) } ?: return savedMs
    return if (savedMs >= watchedFrom) 0 else savedMs
}

private fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

private val isDesktop: Boolean get() = Platform.name != "Android"

/**
 * The AniMix player: the same overlay controls on both OSes, drawn over the picture.
 * Only the decoder and the video surface are native.
 */
@Composable
fun AniMixPlayer(
    stream: ResolvedStream,
    episode: Episode,
    settings: Settings,
    title: String,
    subtitle: String? = null,
    skips: suspend (durationSeconds: Double?) -> SkipPlan = { SkipPlan(episode.opening, episode.ending) },
    initialPositionMs: Long = 0,
    onProgress: (Long) -> Unit = {},
    onCompleted: () -> Unit = {},
    nextLabel: String? = null,
    onNext: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onDownload: ((String) -> Unit)? = null,
    onQualityChange: (String) -> Unit = {},
    onAutoNextChoice: (Boolean) -> Unit = {},
    onFillScreenChange: (Boolean) -> Unit = {},
    /** The viewer switched the glow around letterboxed pictures on or off in the player's gear. */
    onAmbientGlowChange: (Boolean) -> Unit = {},
    onPlayback: (PlaybackUpdate) -> Unit = {},
    /** An opening or ending was skipped; the argument is the seconds saved. */
    onSkip: (Int) -> Unit = {},
    /** Session controls can block playback and bind the decoder to a background-safe deadline. */
    playbackAllowed: Boolean = true,
    onPlaybackRequest: () -> Unit = {},
    onPlayerBound: ((NativePlayer) -> (() -> Unit))? = null,
    horizon: (@Composable () -> Unit)? = null,
    onSnapshot: (PlayerSnapshot, SkipPlan) -> Unit = { _, _ -> },
    onEpisodeEnded: () -> Unit = {},
    /** Highest quality to start at (traffic saving on mobile data); the viewer can still pick more. */
    maxStartQuality: Int? = null,
    /** Called once, about five minutes before the end: time to get the next episode ready. */
    onApproachingEnd: () -> Unit = {},
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    /** «Плеер+» on the watch screen may continue in a small window over other apps (Android). */
    pictureInPicture: Boolean = false,
    /** The viewer picked a speed in the gear; kept for the next episode. */
    onSpeedChange: (Float) -> Unit = {},
    playerFactory: () -> NativePlayer = ::createNativePlayer,
    modifier: Modifier = Modifier,
) {
    val backend = remember { runCatching(playerFactory) }
    val player = backend.getOrNull()
    if (player == null) {
        Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).background(Color.Black), contentAlignment = Alignment.Center) {
            Txt("Не удалось запустить видеодвижок. Проверьте целостность сборки.", AniMixTheme.type.caption, color = Color.White, modifier = Modifier.padding(20.dp))
        }
        return
    }
    val state by player.state.collectAsState()
    val snapshotCallback by rememberUpdatedState(onSnapshot)
    val endedCallback by rememberUpdatedState(onEpisodeEnded)
    val bindCallback by rememberUpdatedState(onPlayerBound)
    val allowedNow by rememberUpdatedState(playbackAllowed)
    val playbackRequest by rememberUpdatedState(onPlaybackRequest)
    var resumeWhenReady by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val unbind = bindCallback?.invoke(player)
        onDispose { unbind?.invoke() }
    }
    LaunchedEffect(playbackAllowed) {
        if (!playbackAllowed) { resumeWhenReady = false; player.pause() }
        else if (bindCallback != null && !player.state.value.ended) {
            if (player.state.value.ready) player.play() else resumeWhenReady = true
        }
    }
    LaunchedEffect(state.ready, resumeWhenReady) {
        if (state.ready && resumeWhenReady && allowedNow) {
            resumeWhenReady = false
            if (!player.state.value.ended) player.play()
        }
    }
    val qualities = stream.qualities.keys.toList()
    var quality by remember { mutableStateOf(startQuality(qualities, settings.preferredQuality, maxStartQuality)) }
    val available = quality.takeIf { it in stream.qualities } ?: qualities.first()
    var reload by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf(SkipPlan(episode.opening, episode.ending)) }
    var openingSkipped by remember { mutableStateOf(false) }
    var endingSkipped by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var finishedEarly by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<Int?>(null) }
    var lastVolume by remember { mutableFloatStateOf(1f) }
    var fill by remember { mutableStateOf(settings.fillScreen) }
    // «Плеер+» adds glass, glow and the small window; the simplified player is plain and light. Speed,
    // quality and the brightness and volume swipes are basic controls, so both players have them.
    val plus = settings.playerMode == app.animix.core.PlayerMode.Plus
    var speed by remember { mutableFloatStateOf(settings.playbackSpeed.coerceIn(0.5f, 2f)) }
    LaunchedEffect(player, speed, state.ready) { if (state.ready) player.setSpeed(speed) }
    val pip by PictureInPicture.active.collectAsState()
    val pipAllowed = pictureInPicture && plus && PictureInPicture.supported
    DisposableEffect(pipAllowed, state.playing) {
        PictureInPicture.eligible = pipAllowed && state.playing
        onDispose { PictureInPicture.eligible = false }
    }
    val fullscreenNow by rememberUpdatedState(fullscreen)
    val fullscreenChange by rememberUpdatedState(onFullscreenChange)
    // The small window shows the inline player; the fullscreen dialog would stay behind it.
    LaunchedEffect(pip) { if (pip && fullscreenNow) fullscreenChange(false) }
    val hardware = rememberPlayerHardware()
    var gesture by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }
    LaunchedEffect(gesture) { if (gesture != null) { delay(900); gesture = null } }

    val watchedFrom = watchedFromMs(state.durationMs, plan.ending)
    val savedPosition: (Long) -> Long = { position -> if (watchedFrom != null && position >= watchedFrom) 0 else position }
    val progressCallback by rememberUpdatedState(onProgress)
    val completedCallback by rememberUpdatedState(onCompleted)
    val nextCallback by rememberUpdatedState(onNext)
    val savedPositionNow by rememberUpdatedState(savedPosition)
    val atEnd = state.ended || finishedEarly
    val tracker = remember(player) { PlaybackTracker() }
    val playbackClock = remember(player) { TimeSource.Monotonic.markNow() }
    val playbackCallback by rememberUpdatedState(onPlayback)
    val skipCallback by rememberUpdatedState(onSkip)
    val currentPlan by rememberUpdatedState(plan)
    var endReported by remember { mutableStateOf(false) }
    fun flushViewing(forceEnd: Boolean = false) {
        val snapshot = player.state.value
        val skipPlan = currentPlan
        val boundary = watchedFromMs(snapshot.durationMs, skipPlan.ending)
        val reached = forceEnd || snapshot.ended || (boundary != null && snapshot.positionMs >= boundary)
        val credit = tracker.drain()
        if (credit.ranges.isNotEmpty() || (reached && !endReported)) {
            playbackCallback(PlaybackUpdate(credit, snapshot.durationMs, skipPlan.opening, skipPlan.ending, reached))
        }
        endReported = reached
    }
    val flushViewingNow by rememberUpdatedState<(Boolean) -> Unit>(::flushViewing)

    LaunchedEffect(player) {
        var lastFlush = 0L
        player.state.collect { snapshot ->
            val now = playbackClock.elapsedNow().inWholeMilliseconds
            tracker.sample(snapshot, now)
            snapshotCallback(snapshot, currentPlan)
            val boundary = watchedFromMs(snapshot.durationMs, currentPlan.ending)
            if (now - lastFlush >= 5_000 || !snapshot.playing || snapshot.ended ||
                (!endReported && boundary != null && snapshot.positionMs >= boundary)) {
                flushViewingNow(false)
                lastFlush = now
            }
        }
    }

    LaunchedEffect(stream, available, reload) {
        flushViewing(false)
        tracker.discontinuity()
        val snapshot = player.state.value
        val position = when {
            snapshot.ended -> 0
            snapshot.ready || snapshot.positionMs > 0 -> snapshot.positionMs
            else -> resumePositionMs(initialPositionMs, episode.durationSeconds?.times(1000L), episode.ending)
        }
        player.load(stream.qualities.getValue(available), stream.headers, position, allowedNow && if (snapshot.ready) snapshot.playing else true)
    }
    DisposableEffect(player) {
        onDispose {
            flushViewingNow(false)
            val last = player.state.value
            // Never overwrite a saved position with the 0 of an episode that did not start.
            if (last.ended) progressCallback(0) else if (last.ready) progressCallback(savedPositionNow(last.positionMs))
            player.release()
        }
    }
    // Skip times arrive once the length is known: AniSkip matches them to the release by length.
    LaunchedEffect(state.durationMs > 0) {
        if (state.durationMs > 0) plan = skips(state.durationMs / 1000.0)
    }
    // A finished episode saved near its end starts over once its real length is known.
    var resumeChecked by remember { mutableStateOf(false) }
    LaunchedEffect(state.durationMs > 0, plan) {
        if (state.durationMs <= 0 || resumeChecked) return@LaunchedEffect
        resumeChecked = true
        if (initialPositionMs > 0 && resumePositionMs(initialPositionMs, state.durationMs, plan.ending) == 0L && state.positionMs > 0) {
            tracker.discontinuity(); player.seekTo(0)
        }
    }
    LaunchedEffect(state.positionMs / 5000, state.ready) {
        if (state.ready && !state.ended && state.positionMs > 0) progressCallback(savedPosition(state.positionMs))
    }
    LaunchedEffect(state.ended, state.ready && watchedFrom != null && state.positionMs >= watchedFrom) {
        val reached = state.ended || (state.ready && watchedFrom != null && state.positionMs >= watchedFrom)
        if (reached && !completed) { completed = true; completedCallback() }
    }

    fun show() { controlsVisible = true; activity++ }
    fun seekTo(target: Long) {
        flushViewing(false)
        tracker.discontinuity()
        finishedEarly = false
        player.seekTo(target.coerceIn(0, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }
    fun jump(target: Long) {
        // jump() serves only opening and ending skips: report the time it saves.
        skipCallback(((target - state.positionMs) / 1000).toInt().coerceAtLeast(0))
        flushViewing(false)
        tracker.discontinuity()
        // An ending that runs to the last seconds finishes the episode instead of seeking into nothing.
        if (state.durationMs > 0 && target >= state.durationMs - 3_000) {
            player.pause()
            finishedEarly = true
            flushViewing(true)
            if (!completed) { completed = true; completedCallback() }
        } else seekTo(target)
    }
    fun seekBy(seconds: Int) {
        finishedEarly = false
        val max = state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((player.state.value.positionMs + seconds * 1000L).coerceIn(0, max))
        seekFeedback = (seekFeedback?.takeIf { (it > 0) == (seconds > 0) } ?: 0) + seconds
    }
    fun togglePlay() {
        if (!allowedNow) { playbackRequest(); show(); return }
        if (state.playRequested || state.playing) player.pause() else { finishedEarly = false; player.play() }
        show()
    }
    fun toggleMute() {
        if (state.volume > 0) { lastVolume = state.volume; player.setVolume(0f) } else player.setVolume(lastVolume.takeIf { it > 0 } ?: 1f)
        show()
    }
    fun pickQuality(label: String) { quality = label; onQualityChange(label) }
    fun toggleFill() { fill = !fill; onFillScreenChange(fill); show() }

    LaunchedEffect(state.positionMs / 250, state.playing, plan, settings.skipOpenings, settings.skipEndings) {
        if (!state.playing) return@LaunchedEffect
        if (settings.skipOpenings && !openingSkipped) skipTarget(state.positionMs, plan.opening)?.let { openingSkipped = true; jump(it); return@LaunchedEffect }
        if (settings.skipEndings && !endingSkipped) skipTarget(state.positionMs, plan.ending)?.let { endingSkipped = true; jump(it) }
    }
    LaunchedEffect(atEnd, settings.autoNextEpisode, settings.autoNextAsked) {
        // Asked once; after that the next episode just starts, without a countdown to watch.
        if (atEnd && allowedNow && onNext != null && settings.autoNextAsked && settings.autoNextEpisode) {
            delay(400)
            nextCallback?.invoke()
        }
    }
    LaunchedEffect(atEnd) {
        if (atEnd) { flushViewing(true); endedCallback() }
    }
    LaunchedEffect(controlsVisible, state.playing, activity) {
        if (controlsVisible && state.playing) { delay(3_000); controlsVisible = false }
    }
    LaunchedEffect(seekFeedback) { if (seekFeedback != null) { delay(700); seekFeedback = null } }
    val approachingEndCallback by rememberUpdatedState(onApproachingEnd)
    LaunchedEffect(state.durationMs > 0 && state.durationMs - state.positionMs < 300_000) {
        if (state.durationMs > 0 && state.ready && state.durationMs - state.positionMs < 300_000) approachingEndCallback()
    }
    val tapAction by rememberUpdatedState<() -> Unit> {
        if (isDesktop) togglePlay() else if (controlsVisible) controlsVisible = false else show()
    }
    val doubleTapAction by rememberUpdatedState<(Int) -> Unit> { direction ->
        when { direction < 0 -> seekBy(-10); direction > 0 -> seekBy(10); else -> onFullscreenChange(!fullscreen) }
    }
    val showAction by rememberUpdatedState<() -> Unit>(::show)

    // The skip offered right now: the opening or ending the position is in, or, for a release without
    // timings, the usual length of an opening once at the start of a long episode. Each offer lasts
    // SkipOfferMs of playback, drained as a fill on the button, then the button goes away.
    var fallbackOffered by remember { mutableStateOf(false) }
    val skipKind = when {
        skipTarget(state.positionMs, plan.opening) != null -> SkipKind.Opening
        skipTarget(state.positionMs, plan.ending) != null -> SkipKind.Ending
        plan.opening == null && !fallbackOffered && state.ready && state.durationMs >= 600_000 && state.positionMs in 3_000 until 240_000 -> SkipKind.Usual
        else -> null
    }
    var activeSkip by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var skipLeft by remember { mutableFloatStateOf(1f) }
    val playingForSkip by rememberUpdatedState(state.playing)
    LaunchedEffect(skipKind) {
        val kind = skipKind ?: run { activeSkip = null; return@LaunchedEffect }
        // A fixed target: the old «+1:30 from here» moved with the picture and never ended.
        activeSkip = when (kind) {
            SkipKind.Opening -> "Пропустить опенинг" to (skipTarget(player.state.value.positionMs, currentPlan.opening) ?: return@LaunchedEffect)
            SkipKind.Ending -> "Пропустить эндинг" to (skipTarget(player.state.value.positionMs, currentPlan.ending) ?: return@LaunchedEffect)
            SkipKind.Usual -> { fallbackOffered = true; "Пропустить 1:30" to (player.state.value.positionMs + 90_000) }
        }
        var left = SkipOfferMs
        skipLeft = 1f
        // The time runs with the picture: on pause the offer waits.
        while (left > 0) {
            delay(50)
            if (playingForSkip) left -= 50
            skipLeft = left / SkipOfferMs.toFloat()
        }
        activeSkip = null
    }

    val frame: @Composable (Modifier) -> Unit = { frameModifier ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BoxWithConstraints(
            frameModifier
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Spacebar, Key.K -> togglePlay()
                        Key.DirectionLeft, Key.J -> { seekBy(-10); show() }
                        Key.DirectionRight, Key.L -> { seekBy(10); show() }
                        Key.DirectionUp -> { player.setVolume(state.volume + 0.1f); show() }
                        Key.DirectionDown -> { player.setVolume(state.volume - 0.1f); show() }
                        Key.M -> toggleMute()
                        Key.F -> onFullscreenChange(!fullscreen)
                        Key.Z -> toggleFill()
                        Key.Escape -> if (fullscreen) onFullscreenChange(false) else return@onPreviewKeyEvent false
                        Key.N -> nextCallback?.invoke() ?: return@onPreviewKeyEvent false
                        Key.S -> activeSkip?.let { (_, target) -> jump(target) } ?: return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Move -> if (isDesktop) showAction()
                                PointerEventType.Scroll -> {
                                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (delta != 0f) { player.setVolume(player.state.value.volume - delta * 0.05f); show() }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            runCatching { focus.requestFocus() }
                            tapAction()
                        },
                        onDoubleTap = { offset ->
                            doubleTapAction(when { offset.x < size.width / 3f -> -1; offset.x > size.width * 2f / 3f -> 1; else -> 0 })
                        },
                    )
                },
        ) {
            val overlayVisible = (controlsVisible || !state.playing || state.error != null) && !pip
            // The skip pill sits above the time pill, and above the bottom row when it is shown.
            val skipLift = when { !overlayVisible -> 20.dp; playerHasBottomRow(fullscreen, maxHeight.value) -> 132.dp; else -> 84.dp }
            // Glass needs the frame only while the controls are on screen.
            val backdrop = rememberUpdatedState(rememberFrameBackdrop(player, enabled = plus && overlayVisible && !LocalUiPerformance.current.batterySaver))
            val picture = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
            VideoArea(player, state, zoomed = fill, glow = plus && settings.ambientGlow, onPicture = { picture.value = it })
            // Fullscreen on a phone, in both players: swipe up or down on the left for brightness, on the right for volume.
            if (fullscreen && hardware != null && !pip) Box(Modifier.fillMaxSize().pointerInput(hardware) {
                var left = true
                detectVerticalDragGestures(
                    onDragStart = { offset -> left = offset.x < size.width / 2f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        val step = -amount / (size.height * 0.8f)
                        if (left) { hardware.brightness = (hardware.brightness + step).coerceIn(0f, 1f); gesture = true to hardware.brightness }
                        else { hardware.volume = (hardware.volume + step).coerceIn(0f, 1f); gesture = false to hardware.volume }
                    },
                )
            })
            CompositionLocalProvider(LocalPlayerGlass provides remember(backdrop, picture) { PlayerGlass(backdrop, picture) }) {
            PlayerOverlay(
                horizon = horizon,
                visible = overlayVisible,
                state = state,
                title = title,
                subtitle = subtitle,
                quality = available,
                qualities = qualities,
                plan = plan,
                fullscreen = fullscreen,
                hasNext = onNext != null,
                nextLabel = nextLabel,
                autoNext = settings.autoNextEpisode,
                onAutoNext = { onAutoNextChoice(it); show() },
                ambientGlow = settings.ambientGlow,
                onAmbientGlow = { onAmbientGlowChange(it); show() },
                onTogglePlay = ::togglePlay,
                onSeekBy = { seekBy(it); show() },
                onSeekTo = { seekTo(it); show() },
                onScrub = { show() },
                onQuality = { pickQuality(it); show() },
                onDownload = onDownload?.let { download -> { download(available) } },
                onFullscreen = { onFullscreenChange(!fullscreen) },
                fill = fill,
                onFill = ::toggleFill,
                onMute = ::toggleMute,
                onVolume = { player.setVolume(it); show() },
                onNext = { nextCallback?.invoke() },
                speed = speed,
                glowAvailable = plus,
                onSpeed = { value -> speed = value; onSpeedChange(value); show() },
                onPictureInPicture = if (pipAllowed) { { if (fullscreen) onFullscreenChange(false); PictureInPicture.enter() } } else null,
            )
            }
            gesture?.let { (brightness, value) -> GestureLevel(brightness, value, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }
            seekFeedback?.let { seconds ->
                SeekBubble(seconds, Modifier.align(if (seconds < 0) BiasAlignment(-0.6f, 0f) else BiasAlignment(0.6f, 0f)))
            }
            AnimatedVisibility(
                visible = activeSkip != null && !atEnd && state.error == null && !pip,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = skipLift),
            ) {
                var label by remember { mutableStateOf("") }
                activeSkip?.first?.let { label = it }
                AccentPill(label, AniMixIcons.SkipAhead, remaining = { skipLeft }) { activeSkip?.let { (_, target) -> activeSkip = null; jump(target) }; show() }
            }
            if (atEnd && state.error == null && !pip) {
                EndCard(
                    nextLabel = nextLabel,
                    hasNext = onNext != null,
                    ask = !settings.autoNextAsked,
                    auto = settings.autoNextAsked && settings.autoNextEpisode,
                    onNext = { nextCallback?.invoke() },
                    onChoice = { yes -> onAutoNextChoice(yes); if (yes) nextCallback?.invoke() },
                    onReplay = { if (allowedNow) { finishedEarly = false; openingSkipped = false; endingSkipped = false; seekTo(0); player.play() } },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            state.error?.let { message ->
                ErrorCard(
                    message = message,
                    refresh = onRefresh != null,
                    otherQualities = qualities.filter { it != available },
                    onRetry = { if (onRefresh != null) onRefresh() else reload++ },
                    onQuality = ::pickQuality,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // Over the picture a press shows in white, whatever the theme.
    val animatePress = LocalUiPerformance.current.animations
    val pressOverVideo = remember(animatePress) { app.animix.ui.components.PressIndication(Color.White, animatePress) }
    CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides pressOverVideo) {
    if (pip) {
        // The small window is the whole picture: no rounded card, no controls.
        frame(modifier.fillMaxSize())
    } else if (fullscreen) {
        PlayerFullscreen(onDismiss = { onFullscreenChange(false) }) { frame(Modifier.fillMaxSize()) }
        Box(
            modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).background(Color.Black).clickable { onFullscreenChange(false) },
            contentAlignment = Alignment.Center,
        ) {
            Txt("Плеер открыт на весь экран", AniMixTheme.type.caption, color = Color.White.copy(alpha = 0.7f))
        }
    } else {
        frame(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large))
    }
    }
}

/** The part of the episode the position is in, as YouTube names chapters. */
internal fun chapterAt(positionMs: Long, plan: SkipPlan): String? = when {
    plan.opening?.let { positionMs >= it.start * 1000L && positionMs < it.end * 1000L } == true -> "Опенинг"
    plan.ending?.let { positionMs >= it.start * 1000L && positionMs < it.end * 1000L } == true -> "Эндинг"
    else -> null
}

/** What the glass capsules show through: the latest small frame copy and where the picture sits on screen. */
private class PlayerGlass(val backdrop: State<ImageBitmap?>, val picture: MutableState<androidx.compose.ui.geometry.Rect>)
private val LocalPlayerGlass = androidx.compose.runtime.staticCompositionLocalOf<PlayerGlass?> { null }

/**
 * Frosted glass over the video, as YouTube's capsules: the part of the frame behind the capsule,
 * from a small blurred frame copy stretched over the picture, dimmed so the glass reads as dark and
 * white icons stay crisp. Without a frame copy (battery saving, before the first frame) it is a translucent dark fill.
 */
@Composable
private fun Modifier.frameGlass(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val glass = LocalPlayerGlass.current
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    return this
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .clip(shape)
        .drawBehind {
            val image = glass?.backdrop?.value
            val picture = glass?.picture?.value
            if (image != null && picture != null && picture.width > 0f) {
                drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset((picture.left - bounds.left).roundToInt(), (picture.top - bounds.top).roundToInt()),
                    dstSize = IntSize(picture.width.roundToInt(), picture.height.roundToInt()), filterQuality = FilterQuality.Low)
                // Mostly dark: the scene only tints the glass, so icons stay crisp over any frame.
                drawRect(Color.Black.copy(alpha = 0.58f))
            } else drawRect(Color.Black.copy(alpha = 0.66f))
        }
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
}

/** Room for the bottom row of the player (actions and the next episode), as in YouTube's large player. */
internal fun playerHasBottomRow(fullscreen: Boolean, heightDp: Float): Boolean = fullscreen || heightDp >= 300f

/**
 * The layout of YouTube's current player. A light shade over the picture; the title large on the top
 * left; on the top right the autoplay switch and plain white actions; three glass buttons in the
 * middle; above a thin full-width progress line, the time in a glass capsule on the left and a plain
 * fullscreen icon on the right; below it, when there is room, a glass capsule of actions on the left
 * and the next episode on the right. Every control works; nothing is drawn for a missing feature.
 */
@Composable
private fun PlayerOverlay(
    horizon: (@Composable () -> Unit)?,
    visible: Boolean,
    state: PlayerSnapshot,
    title: String,
    subtitle: String?,
    quality: String,
    qualities: List<String>,
    plan: SkipPlan,
    fullscreen: Boolean,
    hasNext: Boolean,
    nextLabel: String?,
    autoNext: Boolean,
    onAutoNext: (Boolean) -> Unit,
    ambientGlow: Boolean,
    onAmbientGlow: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onScrub: () -> Unit,
    onQuality: (String) -> Unit,
    onDownload: (() -> Unit)?,
    onFullscreen: () -> Unit,
    fill: Boolean,
    onFill: () -> Unit,
    onMute: () -> Unit,
    onVolume: (Float) -> Unit,
    onNext: () -> Unit,
    /** The current rate; both players offer the same speeds. */
    speed: Float = 1f,
    /** Only «Плеер+» draws the glow around letterboxed pictures, so only it offers the switch. */
    glowAvailable: Boolean = false,
    onSpeed: (Float) -> Unit = {},
    onPictureInPicture: (() -> Unit)? = null,
) {
    val playingNow = state.playRequested || state.playing
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(260)), modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x26000000))) {
            val compact = maxWidth < 480.dp
            val bottomRow = playerHasBottomRow(fullscreen, maxHeight.value)
            Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000)))))

            // Top: the episode in large white type with its context; autoplay and plain actions on the right.
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(start = if (fullscreen) 8.dp else 16.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (fullscreen) OverlayIcon(AniMixIcons.Back, onFullscreen, description = "Назад")
                Column(Modifier.weight(1f).padding(start = if (fullscreen) 4.dp else 0.dp)) {
                    Txt(title, if (compact) AniMixTheme.type.subheading else AniMixTheme.type.heading, color = Color.White, maxLines = 1)
                    subtitle?.let { Txt(it, AniMixTheme.type.caption, color = Color.White.copy(alpha = 0.65f), maxLines = 1) }
                }
                if (hasNext) AutoplaySwitch(autoNext, onAutoNext)
                if (horizon != null) CompositionLocalProvider(LocalCompactPlayer provides compact) { horizon() }
                // «Без рамок» works in any size of the player and is named, not hidden behind the gear.
                TopAction(if (fill) AniMixIcons.Fit else AniMixIcons.Fill, "Без рамок", onFill, active = fill, showLabel = !compact)
                // A small player has no bottom row: «Скачать» joins the top actions there.
                if (onDownload != null && !bottomRow) OverlayIcon(AniMixIcons.Download, onDownload, description = "Скачать серию")
                if (onPictureInPicture != null) OverlayIcon(AniMixIcons.PictureInPicture, onPictureInPicture, description = "Картинка в картинке")
                SettingsMenu(quality, qualities, onQuality, glow = if (glowAvailable) ambientGlow else null, onGlow = onAmbientGlow,
                    speed = speed, onSpeed = onSpeed)
            }

            // Middle: back 10 s, play or pause, forward 10 s — glass circles, the middle one larger.
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                val gap = if (compact) 24.dp else 40.dp
                RoundButton(AniMixIcons.SeekBack, { onSeekBy(-10) }, if (compact) 44 else 52, "Назад на 10 секунд", label = "10")
                Spacer(Modifier.width(gap))
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(if (compact) 60.dp else 72.dp).pressScale(interaction, pressed = 0.92f).frameGlass(CircleShape)
                        .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.buffering && state.playRequested) CircularProgressIndicator(Modifier.size(30.dp), color = Color.White)
                    else Icon(if (playingNow) AniMixIcons.Pause else AniMixIcons.Play,
                        if (playingNow) "Пауза" else "Смотреть", Modifier.size(if (compact) 26.dp else 32.dp), tint = Color.White)
                }
                Spacer(Modifier.width(gap))
                RoundButton(AniMixIcons.SeekForward, { onSeekBy(10) }, if (compact) 44 else 52, "Вперёд на 10 секунд", label = "10")
            }

            Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(horizontal = 12.dp).padding(bottom = if (bottomRow) 10.dp else 2.dp)) {
                var scrub by remember { mutableStateOf<Long?>(null) }
                // Above the line: the time (and chapter) in a capsule on the left, fullscreen on the right.
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val position = scrub ?: state.positionMs
                    Row(Modifier.frameGlass(AniMixTheme.shapes.pill).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt(timeLabel(position), AniMixTheme.type.label, color = Color.White, maxLines = 1)
                        Txt(" / ${timeLabel(state.durationMs)}", AniMixTheme.type.label, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                    }
                    chapterAt(position, plan)?.let {
                        Spacer(Modifier.width(6.dp))
                        Txt(it, AniMixTheme.type.label, color = Color.White, maxLines = 1,
                            modifier = Modifier.frameGlass(AniMixTheme.shapes.pill).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (!bottomRow && hasNext) OverlayIcon(AniMixIcons.Next, onNext, description = "Следующая серия")
                    OverlayIcon(if (fullscreen) AniMixIcons.Collapse else AniMixIcons.Expand, onFullscreen,
                        description = if (fullscreen) "Выйти из полноэкранного режима" else "Во весь экран")
                }
                SeekBar(state.positionMs, state.durationMs, plan,
                    playing = state.playing && !state.buffering && !state.seeking,
                    onScrub = { scrub = it; onScrub() }, onSeek = { scrub = null; onSeekTo(it) }, onCancel = { scrub = null })
                // Below the line, with room: a capsule of actions and the next episode, like YouTube's large player.
                if (bottomRow) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.frameGlass(AniMixTheme.shapes.pill).padding(start = 2.dp, end = if (isDesktop) 14.dp else 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OverlayIcon(if (state.volume > 0) AniMixIcons.Volume else AniMixIcons.Muted, onMute,
                            description = if (state.volume > 0) "Выключить звук" else "Включить звук")
                        if (isDesktop) VolumeBar(state.volume, onVolume)
                    }
                    if (onDownload != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.frameGlass(CircleShape)) { OverlayIcon(AniMixIcons.Download, onDownload, description = "Скачать серию") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (hasNext) {
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.pressScale(interaction).frameGlass(AniMixTheme.shapes.pill)
                            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onNext)
                            .heightIn(min = 44.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(nextLabel ?: "Следующая серия", AniMixTheme.type.label, color = Color.White, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            Icon(AniMixIcons.Next, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * YouTube's autoplay switch: a small glass track with a white knob that shows play when the next
 * episode starts by itself and pause when it doesn't. The 44 dp target is larger than the track.
 */
@Composable
private fun AutoplaySwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AniMixTheme.colors
    val knob by app.animix.ui.theme.animateMotionFloatAsState(if (on) 1f else 0f, tween(AniMixMotion.Fast))
    Box(Modifier.size(width = 52.dp, height = 44.dp)
        .clickable(role = androidx.compose.ui.semantics.Role.Switch, onClickLabel = "Автопереход") { onChange(!on) }
        .semantics { contentDescription = if (on) "Автопереход включён" else "Автопереход выключен" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 16.dp).clip(AniMixTheme.shapes.pill)
            .background(if (on) colors.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.3f)))
        Box(Modifier.offset(x = (-10 + 20 * knob).dp).size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(if (on) AniMixIcons.Play else AniMixIcons.Pause, null, Modifier.size(11.dp), tint = Color(0xFF161618))
        }
    }
}

/** A round glass button over the picture, as YouTube's middle controls. */
@Composable
private fun RoundButton(icon: ImageVector, onClick: () -> Unit, size: Int, description: String, label: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size.dp).pressScale(interaction, pressed = 0.9f).frameGlass(CircleShape)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The seek arrows carry their step, "10", in the middle, as YouTube's do.
        Icon(icon, description, Modifier.size((size * if (label != null) 0.6f else 0.46f).dp), tint = Color.White)
        if (label != null) Txt(label, AniMixTheme.type.micro, color = Color.White, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * The progress line split into chapters at the opening and ending, so a skip is visible before it
 * happens. It thickens under the pointer or finger, and the time under it floats above the line.
 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, plan: SkipPlan, playing: Boolean, onScrub: (Long) -> Unit, onSeek: (Long) -> Unit, onCancel: () -> Unit) {
    val accent = AniMixTheme.colors.accent
    var drag by remember { mutableStateOf<Float?>(null) }
    var hover by remember { mutableStateOf<Float?>(null) }
    val enabled = durationMs > 0
    val smooth = LocalUiPerformance.current.animations
    val displayed = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing, smooth) {
        displayed.longValue = positionMs
        if (playing && smooth) {
            val started = withFrameNanos { it }
            while (isActive) withFrameNanos { now ->
                displayed.longValue = (positionMs + ((now - started) / 1_000_000).coerceAtMost(600)).coerceAtMost(durationMs)
            }
        }
    }
    val active = drag != null || hover != null
    val thickness by app.animix.ui.theme.animateMotionFloatAsState(if (active) 5f else 3f, tween(120))
    val seekNow by rememberUpdatedState(onSeek)
    val scrubNow by rememberUpdatedState(onScrub)
    val cancelNow by rememberUpdatedState(onCancel)
    // Chapter edges as fractions of the episode: the line gets a small gap at each.
    val edges = if (enabled) listOfNotNull(plan.opening, plan.ending).flatMap { listOf(it.start, it.end) }
        .map { (it * 1000f / durationMs).coerceIn(0f, 1f) }.filter { it > 0.002f && it < 0.998f }.sorted() else emptyList()
    BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.type != androidx.compose.ui.input.pointer.PointerType.Mouse) continue
                            hover = when (event.type) {
                                PointerEventType.Exit -> null
                                PointerEventType.Move, PointerEventType.Enter -> (change.position.x / size.width).coerceIn(0f, 1f)
                                else -> hover
                            }
                        }
                    }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset -> seekNow(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> drag = (offset.x / size.width).coerceIn(0f, 1f); scrubNow((drag!! * durationMs).toLong()) },
                        onHorizontalDrag = { change, _ ->
                            drag = (change.position.x / size.width).coerceIn(0f, 1f)
                            change.consume()
                            scrubNow((drag!! * durationMs).toLong())
                        },
                        onDragEnd = { drag?.let { seekNow((it * durationMs).toLong()) }; drag = null },
                        onDragCancel = { drag = null; cancelNow() },
                    )
                },
        ) {
            val fraction = drag ?: if (enabled) (displayed.longValue.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val track = thickness.dp.toPx()
            val gap = 2.dp.toPx()
            val y = size.height / 2
            val radius = CornerRadius(track / 2)
            val bounds = listOf(0f) + edges + listOf(1f)
            for (i in 0 until bounds.size - 1) {
                val start = bounds[i] * size.width + if (i > 0) gap / 2 else 0f
                val end = bounds[i + 1] * size.width - if (i < bounds.size - 2) gap / 2 else 0f
                if (end <= start) continue
                // Opening and ending stand out as lighter segments of the line, so a skip is visible before it happens.
                val middle = ((bounds[i] + bounds[i + 1]) / 2 * durationMs).toLong()
                drawRoundRect(Color.White.copy(alpha = if (chapterAt(middle, plan) != null) 0.55f else 0.28f), Offset(start, y - track / 2), Size(end - start, track), radius)
                hover?.let { h ->
                    val to = minOf(end, h * size.width)
                    if (to > start) drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(start, y - track / 2), Size(to - start, track), radius)
                }
                val played = minOf(end, fraction * size.width)
                if (played > start) drawRoundRect(accent, Offset(start, y - track / 2), Size(played - start, track), radius)
            }
            // The thumb appears only when it can be grabbed, as on YouTube.
            drawCircle(accent, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(size.width * fraction, y))
        }
        val preview = drag ?: hover
        if (enabled && preview != null) {
            val ms = (preview * durationMs).toLong()
            var bubbleWidth by remember { mutableIntStateOf(0) }
            val x = (preview * widthPx - bubbleWidth / 2f).coerceIn(0f, (widthPx - bubbleWidth).coerceAtLeast(0f))
            Column(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), (-34).dp.roundToPx()) }
                    .onSizeChanged { bubbleWidth = it.width }
                    .clip(AniMixTheme.shapes.small).background(Color(0xE6141418)).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                chapterAt(ms, plan)?.let { Txt(it, AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                Txt(timeLabel(ms), AniMixTheme.type.label, color = Color.White, maxLines = 1)
            }
        }
    }
}

/**
 * Volume as a thin line with a knob, inset by the knob's radius on both ends so it sits evenly
 * inside its capsule: at 0 and 100% the knob touches the line's ends, never the capsule's edge.
 */
@Composable
private fun VolumeBar(volume: Float, onVolume: (Float) -> Unit) {
    val knob = 6.dp
    fun level(x: Float, width: Float, inset: Float) = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
    Canvas(
        Modifier.width(88.dp).height(32.dp)
            .pointerInput(Unit) { detectTapGestures { onVolume(level(it.x, size.width.toFloat(), knob.toPx())) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onVolume(level(change.position.x, size.width.toFloat(), knob.toPx())) } },
    ) {
        val inset = knob.toPx()
        val track = 3.dp.toPx()
        val y = size.height / 2
        val length = size.width - 2 * inset
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(inset, y - track / 2), Size(length, track), CornerRadius(track / 2))
        drawRoundRect(Color.White, Offset(inset, y - track / 2), Size(length * volume.coerceIn(0f, 1f), track), CornerRadius(track / 2))
        drawCircle(Color.White, inset, Offset(inset + length * volume.coerceIn(0f, 1f), y))
    }
}
/** A plain white icon button over the picture: 44 dp to tap, no backing. */
@Composable
private fun OverlayIcon(icon: ImageVector, onClick: () -> Unit, size: Int = 44, description: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size.dp).pressScale(interaction, pressed = 0.9f).clip(CircleShape)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size((size * 0.5f).dp), tint = Color.White)
    }
}

/** Horizon Link in the player's top bar: the same plain, labelled white action as «Без рамок». */
@Composable
internal fun PlayerChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    TopAction(icon, text, onClick, showLabel = !LocalCompactPlayer.current)
}

/** Whether the player is too narrow for labels next to its top icons. */
private val LocalCompactPlayer = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A plain white action in the top bar, as YouTube's top icons: no backing, a 44 dp target, and the
 * name next to the icon where there is room; [active] tints it in the accent.
 */
@Composable
internal fun TopAction(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, showLabel: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (active) AniMixTheme.colors.accent else Color.White
    Row(
        Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).pressScale(interaction, pressed = 0.94f).clip(AniMixTheme.shapes.pill)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = if (showLabel) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, if (showLabel) null else label, Modifier.size(22.dp), tint = tint)
        if (showLabel) {
            Spacer(Modifier.width(6.dp))
            Txt(label, AniMixTheme.type.label, color = tint, maxLines = 1)
        }
    }
}

@Composable
private fun GlassChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(AniMixTheme.shapes.pill).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Txt(text, AniMixTheme.type.label, color = Color.White, maxLines = 1) }
}

/**
 * The gear, the same in both players: speed, quality (with a short HD mark on the icon like YouTube's)
 * and, in «Плеер+», the glow around letterboxed pictures. It is never an empty list: a source with one
 * quality still names it and says there is nothing else to pick. «Без рамок» has its own action in the top bar.
 */
@Composable
private fun SettingsMenu(quality: String, qualities: List<String>, onQuality: (String) -> Unit, glow: Boolean?, onGlow: (Boolean) -> Unit,
    speed: Float = 1f, onSpeed: (Float) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val colors = AniMixTheme.colors
    Box {
        Box(Modifier.size(44.dp)) {
            OverlayIcon(AniMixIcons.Settings, { open = !open }, description = "Настройки плеера · качество $quality" + (speed.takeIf { it != 1f }?.let { " · скорость ${speedLabel(it)}" } ?: ""))
            // Only a short HD mark, as on YouTube; the exact quality is one tap away in the menu.
            if ((lines(quality) ?: 0) >= 720) Txt("HD", AniMixTheme.type.micro, color = colors.onAccent, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 4.dp).clip(AniMixTheme.shapes.small)
                    .background(colors.accent).padding(horizontal = 3.dp))
        }
        if (open) Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(248.dp).heightIn(max = 420.dp).clip(AniMixTheme.shapes.medium).background(Color(0xF0141418))
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                // Speed first, as the most frequent change during an episode.
                MenuCaption("Скорость")
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    PlayerSpeeds.forEach { value ->
                        val selected = value == speed
                        Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(AniMixTheme.shapes.pill)
                            .background(if (selected) colors.accent else Color.Transparent)
                            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onSpeed(value) },
                            contentAlignment = Alignment.Center) {
                            Txt(speedLabel(value), AniMixTheme.type.label, color = if (selected) colors.onAccent else Color.White, maxLines = 1)
                        }
                    }
                }
                MenuDivider()
                MenuCaption("Качество")
                if (qualities.size > 1) qualities.forEach { label ->
                    val selected = label == quality
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = false; onQuality(label) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt(label, AniMixTheme.type.bodyStrong, color = if (selected) colors.accent else Color.White, modifier = Modifier.weight(1f))
                        if (selected) Icon(AniMixIcons.Check, null, Modifier.size(16.dp), tint = colors.accent)
                    }
                } else {
                    // A downloaded file or a single-quality source: the one quality, and why there is no choice.
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(quality, AniMixTheme.type.bodyStrong, color = colors.accent, modifier = Modifier.weight(1f))
                        Icon(AniMixIcons.Check, null, Modifier.size(16.dp), tint = colors.accent)
                    }
                    Txt("Источник отдаёт только это качество", AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
                }
                // The glow around letterboxed pictures, for those who prefer plain black bars.
                // The simplified player has no glow, so nothing to switch there.
                if (glow != null) {
                    MenuDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onGlow(!glow) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt("Свечение рамок", AniMixTheme.type.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                        Txt(if (glow) "Вкл." else "Выкл.", AniMixTheme.type.label, color = if (glow) colors.accent else Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCaption(text: String) =
    Txt(text, AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

@Composable
private fun MenuDivider() = Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))

/** The playback rates offered in both players. */
private val PlayerSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** «1×», «1,25×»: a Russian decimal comma, no trailing zeros. */
internal fun speedLabel(speed: Float): String {
    val hundredths = kotlin.math.round(speed * 100).toInt()
    val whole = hundredths / 100
    val rest = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (rest.isEmpty()) "$whole×" else "$whole,$rest×"
}

/** Brightness or volume while swiping, at the top of the picture: an icon, a line and the percent. */
@Composable
private fun GestureLevel(brightness: Boolean, value: Float, modifier: Modifier) {
    Row(modifier.clip(AniMixTheme.shapes.pill).background(Color(0xE6141418)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (brightness) AniMixIcons.Sun else if (value > 0f) AniMixIcons.Volume else AniMixIcons.Muted,
            if (brightness) "Яркость" else "Громкость", Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(120.dp).height(4.dp).clip(AniMixTheme.shapes.pill).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().clip(AniMixTheme.shapes.pill).background(Color.White))
        }
        Spacer(Modifier.width(10.dp))
        Txt("${(value * 100).toInt()}%", AniMixTheme.type.label, color = Color.White, maxLines = 1, modifier = Modifier.widthIn(min = 36.dp))
    }
}

@Composable
private fun AccentPill(text: String, icon: ImageVector?, remaining: (() -> Float)? = null, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val gradient = colors.accentGradient
    @Composable fun label(color: Color, modifier: Modifier) = Row(modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, AniMixTheme.type.label, color = color, maxLines = 1)
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, Modifier.size(16.dp), tint = color)
        }
    }
    Box(Modifier.pressScale(interaction).clip(AniMixTheme.shapes.pill)
        .then(if (remaining == null) Modifier.background(gradient) else Modifier.background(Color(0xCC000000)))
        .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)) {
        if (remaining == null) label(colors.onAccent, Modifier)
        else {
            // A timed offer: the accent drains from right to left over the player's dark pill. The words are
            // white on the dark part and dark on the accent, cut at the same edge, so they read whole.
            label(Color.White, Modifier)
            label(colors.onAccent, Modifier.matchParentSize().drawWithContent {
                val width = size.width * remaining().coerceIn(0f, 1f)
                if (width > 0f) clipRect(right = width) {
                    drawRect(gradient)
                    this@drawWithContent.drawContent()
                }
            })
        }
    }
}

@Composable
private fun SeekBubble(seconds: Int, modifier: Modifier) {
    Box(modifier.size(76.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (seconds < 0) AniMixIcons.SeekBack else AniMixIcons.SeekForward, null, Modifier.size(24.dp), tint = Color.White)
            Txt("${if (seconds > 0) "+" else "−"}${kotlin.math.abs(seconds)} с", AniMixTheme.type.micro, color = Color.White)
        }
    }
}

@Composable
private fun OverlayCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(16.dp).widthIn(max = 360.dp).clip(AniMixTheme.shapes.large).background(Color(0xE6141418)).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun EndCard(
    nextLabel: String?,
    hasNext: Boolean,
    ask: Boolean,
    auto: Boolean,
    onNext: () -> Unit,
    onChoice: (Boolean) -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier,
) {
    OverlayCard(modifier) {
        when {
            hasNext && ask -> {
                Txt("Включать следующую серию сама?", AniMixTheme.type.bodyStrong, color = Color.White, align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Txt("Изменить можно в настройках", AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Да, включать", primary = true, modifier = Modifier.weight(1f)) { onChoice(true) }
                    CardButton("Нет", primary = false, modifier = Modifier.weight(1f)) { onChoice(false) }
                }
            }
            hasNext && auto -> {
                CircularProgressIndicator(Modifier.size(22.dp), color = AniMixTheme.colors.accent, strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
                Txt("Открываем ${nextLabel?.lowercase() ?: "следующую серию"}", AniMixTheme.type.caption, color = Color.White)
            }
            hasNext -> {
                Txt("Серия закончилась", AniMixTheme.type.bodyStrong, color = Color.White)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton(nextLabel ?: "Следующая", primary = true, modifier = Modifier.weight(1f), onClick = onNext)
                    CardButton("Сначала", primary = false, modifier = Modifier.weight(1f), onClick = onReplay)
                }
            }
            else -> {
                Txt("Это последняя серия", AniMixTheme.type.bodyStrong, color = Color.White)
                Spacer(Modifier.height(14.dp))
                CardButton("Посмотреть снова", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onReplay)
            }
        }
    }
}

/** Equal-sized card actions: the primary one in the accent gradient, the other as quiet glass. */
@Composable
private fun CardButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.height(42.dp).pressScale(interaction).clip(AniMixTheme.shapes.pill)
            .then(if (primary) Modifier.background(colors.accentGradient) else Modifier.background(Color.White.copy(alpha = 0.14f)))
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, AniMixTheme.type.label, color = if (primary) colors.onAccent else Color.White, maxLines = 1) }
}

@Composable
private fun ErrorCard(
    message: String,
    refresh: Boolean,
    otherQualities: List<String>,
    onRetry: () -> Unit,
    onQuality: (String) -> Unit,
    modifier: Modifier,
) {
    OverlayCard(modifier) {
        Txt(message, AniMixTheme.type.caption, color = Color.White, align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        AccentPill(if (refresh) "Обновить ссылку" else "Повторить", AniMixIcons.Refresh, onClick = onRetry)
        if (otherQualities.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                otherQualities.take(3).forEach { label -> GlassChip(label) { onQuality(label) } }
            }
        }
    }
}

/** Zoom that fills the screen, capped so no more than a sliver of the picture is cut off. */
internal fun fillZoom(containerAspect: Float, videoAspect: Float): Float {
    if (containerAspect <= 0f || videoAspect <= 0f) return 1f
    return maxOf(containerAspect / videoAspect, videoAspect / containerAspect).coerceIn(1f, MaxFillZoom)
}

private const val MaxFillZoom = 1.3f

/** The native surface, fitted, or zoomed to fill in borderless mode. */
@Composable
private fun VideoArea(player: NativePlayer, state: PlayerSnapshot, zoomed: Boolean, glow: Boolean = true, onPicture: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    var area by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { area = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
        val videoAspect = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 0f
        val containerAspect = if (maxHeight.value > 0) maxWidth.value / maxHeight.value else 0f
        val zoom = if (zoomed) fillZoom(containerAspect, videoAspect) else 1f
        // Where the picture is drawn on screen (fitted, or zoomed past the edges), for the glass capsules.
        LaunchedEffect(area, videoAspect, zoom) {
            if (area.width <= 0f || videoAspect <= 0f) return@LaunchedEffect
            val fitWidth = if (area.width / area.height > videoAspect) area.height * videoAspect else area.width
            val width = fitWidth * zoom
            val height = width / videoAspect
            onPicture(androidx.compose.ui.geometry.Rect(area.center.x - width / 2, area.center.y - height / 2, area.center.x + width / 2, area.center.y + height / 2))
        }
        if (zoom > 1.001f) {
            val (width, height) = if (containerAspect > videoAspect) maxHeight * videoAspect to maxHeight else maxWidth to maxWidth / videoAspect
            NativeVideoSurface(player, Modifier.requiredSize(width * zoom, height * zoom))
        } else {
            // Ambient glow only where there are bars to fill, and never in battery saving.
            val bars = videoAspect > 0f && containerAspect > 0f && abs(containerAspect - videoAspect) / videoAspect > 0.02f
            NativeVideoSurface(player, Modifier.fillMaxSize(), ambient = glow && bars && !LocalUiPerformance.current.batterySaver)
        }
    }
}

private fun lines(label: String): Int? = Regex("[0-9]+").find(label)?.value?.toIntOrNull()

/** The remembered quality when allowed, else the best one under the cap (lists are sorted best first). */
internal fun startQuality(qualities: List<String>, preferred: String?, cap: Int?): String {
    fun allowed(label: String) = cap == null || (lines(label)?.let { it <= cap } ?: false)
    preferred?.takeIf { it in qualities && allowed(it) }?.let { return it }
    if (cap == null) return qualities.first()
    return qualities.filter { lines(it) != null && lines(it)!! <= cap }.maxByOrNull { lines(it)!! }
        ?: qualities.filter { lines(it) != null }.minByOrNull { lines(it)!! }
        ?: qualities.first()
}
