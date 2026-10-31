package app.animix.ui.player

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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

    val activeSkip = when {
        skipTarget(state.positionMs, plan.opening) != null -> "Пропустить опенинг" to skipTarget(state.positionMs, plan.opening)!!
        skipTarget(state.positionMs, plan.ending) != null -> "Пропустить эндинг" to skipTarget(state.positionMs, plan.ending)!!
        else -> null
    }

    val frame: @Composable (Modifier) -> Unit = { frameModifier ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
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
                        Key.Z -> if (fullscreen) toggleFill() else return@onPreviewKeyEvent false
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
            VideoArea(player, state, zoomed = fill && fullscreen)
            PlayerOverlay(
                horizon = horizon,
                visible = controlsVisible || !state.playing || state.error != null,
                state = state,
                title = title,
                subtitle = subtitle,
                quality = available,
                qualities = qualities,
                plan = plan,
                fullscreen = fullscreen,
                hasNext = onNext != null,
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
            )
            seekFeedback?.let { seconds ->
                SeekBubble(seconds, Modifier.align(if (seconds < 0) BiasAlignment(-0.6f, 0f) else BiasAlignment(0.6f, 0f)))
            }
            AnimatedVisibility(
                visible = activeSkip != null && !atEnd && state.error == null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = if (controlsVisible) 84.dp else 20.dp),
            ) {
                var label by remember { mutableStateOf("") }
                activeSkip?.first?.let { label = it }
                AccentPill(label, AniMixIcons.SkipAhead) { activeSkip?.let { (_, target) -> jump(target) }; show() }
            }
            if (atEnd && state.error == null) {
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

    if (fullscreen) {
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

/** The part of the episode the position is in, as YouTube names chapters. */
internal fun chapterAt(positionMs: Long, plan: SkipPlan): String? = when {
    plan.opening?.let { positionMs >= it.start * 1000L && positionMs < it.end * 1000L } == true -> "Опенинг"
    plan.ending?.let { positionMs >= it.start * 1000L && positionMs < it.end * 1000L } == true -> "Эндинг"
    else -> null
}

/**
 * Controls in the manner of today's YouTube: a soft shade only at the edges, the title on top,
 * a chaptered progress line over one row of controls, and settings behind a gear. Every control
 * here does something; nothing is shown for a feature that the screen does not have.
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
) {
    val playingNow = state.playRequested || state.playing
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(260)), modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 480.dp
            Box(Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopStart)
                .background(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(if (compact) 110.dp else 130.dp).align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
            // Top: where you are, and the actions about this episode.
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (fullscreen) {
                    // Leaves fullscreen: the same as the system back gesture or Esc, but visible.
                    OverlayIcon(AniMixIcons.Back, onFullscreen, description = "Назад")
                    Spacer(Modifier.width(4.dp))
                } else Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Txt(title, if (fullscreen && !compact) AniMixTheme.type.subheading else AniMixTheme.type.bodyStrong, color = Color.White, maxLines = 1)
                    subtitle?.let { Txt(it, AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.72f), maxLines = 1) }
                }
                if (horizon != null) {
                    Spacer(Modifier.width(6.dp))
                    horizon()
                }
                if (onDownload != null) OverlayIcon(AniMixIcons.Download, onDownload, description = "Скачать серию")
            }
            // Centre: one clear play button; on touch screens also the ±10 s seeks, without backing circles.
            if (!isDesktop || !state.playing || state.buffering) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    if (!isDesktop) {
                        OverlayIcon(AniMixIcons.SeekBack, { onSeekBy(-10) }, size = 56, description = "Назад на 10 секунд")
                        Spacer(Modifier.width(if (compact) 28.dp else 44.dp))
                    }
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier.size(if (compact) 60.dp else 68.dp).pressScale(interaction, pressed = 0.92f).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.42f))
                            .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onTogglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.buffering && state.playRequested) CircularProgressIndicator(Modifier.size(30.dp), color = Color.White)
                        else Icon(if (playingNow) AniMixIcons.Pause else AniMixIcons.Play,
                            if (playingNow) "Пауза" else "Смотреть", Modifier.size(30.dp), tint = Color.White)
                    }
                    if (!isDesktop) {
                        Spacer(Modifier.width(if (compact) 28.dp else 44.dp))
                        OverlayIcon(AniMixIcons.SeekForward, { onSeekBy(10) }, size = 56, description = "Вперёд на 10 секунд")
                    }
                }
            }
            // Bottom: the chaptered line, then one row — playback on the left, view on the right.
            Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                var scrub by remember { mutableStateOf<Long?>(null) }
                SeekBar(state.positionMs, state.durationMs, plan,
                    playing = state.playing && !state.buffering && !state.seeking,
                    onScrub = { scrub = it; onScrub() }, onSeek = { scrub = null; onSeekTo(it) }, onCancel = { scrub = null })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDesktop) OverlayIcon(if (playingNow) AniMixIcons.Pause else AniMixIcons.Play, onTogglePlay,
                        description = if (playingNow) "Пауза" else "Смотреть")
                    if (hasNext) OverlayIcon(AniMixIcons.Next, onNext, description = "Следующая серия")
                    OverlayIcon(if (state.volume > 0) AniMixIcons.Volume else AniMixIcons.Muted, onMute,
                        description = if (state.volume > 0) "Выключить звук" else "Включить звук")
                    if (isDesktop) VolumeBar(state.volume, onVolume)
                    Spacer(Modifier.width(6.dp))
                    val position = scrub ?: state.positionMs
                    Txt(timeLabel(position), AniMixTheme.type.label, color = Color.White, maxLines = 1)
                    Txt(" / ${timeLabel(state.durationMs)}", AniMixTheme.type.label, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                    chapterAt(position, plan)?.takeIf { !compact }?.let {
                        Txt("  ·  $it", AniMixTheme.type.label, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    SettingsMenu(quality, qualities, onQuality, fill = fill.takeIf { fullscreen }, onFill = onFill)
                    OverlayIcon(if (fullscreen) AniMixIcons.Collapse else AniMixIcons.Expand, onFullscreen,
                        description = if (fullscreen) "Выйти из полноэкранного режима" else "Во весь экран")
                }
            }
        }
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
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(start, y - track / 2), Size(end - start, track), radius)
                hover?.let { h ->
                    val to = minOf(end, h * size.width)
                    if (to > start) drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(start, y - track / 2), Size(to - start, track), radius)
                }
                val played = minOf(end, fraction * size.width)
                if (played > start) drawRoundRect(accent, Offset(start, y - track / 2), Size(played - start, track), radius)
            }
            // The thumb appears only when it can be grabbed, as on YouTube.
            if (active || !isDesktop) drawCircle(accent, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(size.width * fraction, y))
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

@Composable
private fun VolumeBar(volume: Float, onVolume: (Float) -> Unit) {
    Canvas(
        Modifier.width(76.dp).height(26.dp).padding(end = 4.dp)
            .pointerInput(Unit) { detectTapGestures { onVolume((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onVolume((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val track = 3.dp.toPx()
        val y = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track / 2))
        drawRoundRect(Color.White, Offset(0f, y - track / 2), Size(size.width * volume, track), CornerRadius(track / 2))
        drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * volume, y))
    }
}

/** A plain white icon button over the picture: 44 dp to tap, no backing. */
@Composable
private fun OverlayIcon(icon: ImageVector, onClick: () -> Unit, size: Int = 44, description: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size.dp).pressScale(interaction, pressed = 0.9f).clip(CircleShape)
            .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size((size * 0.5f).dp), tint = Color.White)
    }
}

/** A labelled action in the player's top bar, readable over any frame. */
@Composable
internal fun PlayerChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(AniMixTheme.shapes.pill).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Txt(text, AniMixTheme.type.label, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun GlassChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(AniMixTheme.shapes.pill).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Txt(text, AniMixTheme.type.label, color = Color.White, maxLines = 1) }
}

/**
 * The gear: quality (its short name rides on the icon, like YouTube's HD badge) and, in fullscreen,
 * «Без рамок». One small dark menu over the picture instead of separate chips.
 */
@Composable
private fun SettingsMenu(quality: String, qualities: List<String>, onQuality: (String) -> Unit, fill: Boolean?, onFill: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = AniMixTheme.colors
    Box {
        Box(Modifier.size(44.dp)) {
            OverlayIcon(AniMixIcons.Settings, { open = !open }, description = "Настройки плеера · качество $quality")
            // Only a short HD mark, as on YouTube; the exact quality is one tap away in the menu.
            if ((lines(quality) ?: 0) >= 720) Txt("HD", AniMixTheme.type.micro, color = colors.onAccent, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 4.dp).clip(AniMixTheme.shapes.small)
                    .background(colors.accent).padding(horizontal = 3.dp))
        }
        if (open) Popup(alignment = Alignment.BottomEnd, offset = androidx.compose.ui.unit.IntOffset(0, -56),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(220.dp).clip(AniMixTheme.shapes.medium).background(Color(0xF0141418)).padding(vertical = 8.dp)) {
                Txt("Качество", AniMixTheme.type.micro, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                qualities.forEach { label ->
                    val selected = label == quality
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = false; onQuality(label) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Txt(label, AniMixTheme.type.bodyStrong, color = if (selected) colors.accent else Color.White, modifier = Modifier.weight(1f))
                        if (selected) Icon(AniMixIcons.Check, null, Modifier.size(16.dp), tint = colors.accent)
                    }
                }
                if (fill != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onFill() }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (fill) AniMixIcons.Fit else AniMixIcons.Fill, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Txt("Без рамок", AniMixTheme.type.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                        Txt(if (fill) "Вкл." else "Выкл.", AniMixTheme.type.label, color = if (fill) colors.accent else Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentPill(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(interaction).clip(AniMixTheme.shapes.pill).background(colors.accentGradient)
            .clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, AniMixTheme.type.label, color = colors.onAccent, maxLines = 1)
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, Modifier.size(16.dp), tint = colors.onAccent)
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
            .clickable(interaction, indication = null, onClick = onClick).padding(horizontal = 14.dp),
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
        AccentPill(if (refresh) "Обновить ссылку" else "Повторить", AniMixIcons.Refresh, onRetry)
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
private fun VideoArea(player: NativePlayer, state: PlayerSnapshot, zoomed: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val videoAspect = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 0f
        val containerAspect = if (maxHeight.value > 0) maxWidth.value / maxHeight.value else 0f
        val zoom = if (zoomed) fillZoom(containerAspect, videoAspect) else 1f
        if (zoom > 1.001f) {
            val (width, height) = if (containerAspect > videoAspect) maxHeight * videoAspect to maxHeight else maxWidth to maxWidth / videoAspect
            NativeVideoSurface(player, Modifier.requiredSize(width * zoom, height * zoom))
        } else {
            // Ambient glow only where there are bars to fill, and never in battery saving.
            val bars = videoAspect > 0f && containerAspect > 0f && abs(containerAspect - videoAspect) / videoAspect > 0.02f
            NativeVideoSurface(player, Modifier.fillMaxSize(), ambient = bars && !LocalUiPerformance.current.batterySaver)
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
