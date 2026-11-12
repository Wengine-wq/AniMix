package app.animix.ui.components

import app.animix.ui.theme.animateMotionColorAsState as animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.model.Anime
import app.animix.model.AnimeKind
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import app.animix.ui.theme.LocalUiPerformance
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = if (color != Color.Unspecified) color else style.color,
            textAlign = align,
        ),
        maxLines = maxLines,
        minLines = minLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Keep long page titles readable on narrow screens and with larger system text. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // The page's scroll column shows the same title in a bar once this header scrolls away.
    LocalPageBar.current?.let { bar -> androidx.compose.runtime.SideEffect { bar.title = title; bar.onBack = onBack } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(AniMixIcons.Back, onClick = onBack, contentDescription = "Назад")
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val type = AniMixTheme.type
            val measurer = rememberTextMeasurer()
            val naturalWidth = measurer.measure(title, style = type.title, softWrap = false, maxLines = 1).size.width
            val availableWidth = with(LocalDensity.current) { maxWidth.roundToPx() }
            Txt(title, if (naturalWidth > availableWidth) type.heading else type.title)
        }
    }
}

/** What a scrolling page shows in its bar: the [PageHeader] of the page registers itself here. */
@androidx.compose.runtime.Stable
class PageBarState {
    var title by mutableStateOf<String?>(null)
    var onBack by mutableStateOf<(() -> Unit)?>(null)
}

val LocalPageBar = androidx.compose.runtime.staticCompositionLocalOf<PageBarState?> { null }

/**
 * The bar a page grows when its own header scrolls away: «Назад» and the page's name on a frosted strip
 * under the status bar, with a hairline at its lower edge. It fades in with the scroll ([shown] from 0
 * to 1), so it reads as the same header following the reader, not as a new element. It never blurs the
 * page it lies on; battery saving makes it solid.
 */
@Composable
fun CollapsedPageBar(title: String, onBack: () -> Unit, shown: () -> Float, topInset: Dp, modifier: Modifier = Modifier,
    source: dev.chrisbanes.haze.HazeState? = null) {
    val colors = AniMixTheme.colors
    val visible by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { shown() > 0.01f } }
    if (!visible) return
    // Frosted over the page's own layer: rows passing under it blur instead of reading through the title.
    Column(modifier.fillMaxWidth().graphicsLayer { alpha = shown().coerceIn(0f, 1f) }.frosted(source).liquidSheen(0.6f)) {
        Spacer(Modifier.height(topInset))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(AniMixIcons.Back, onBack, size = 40.dp, contentDescription = "Назад")
            Spacer(Modifier.width(12.dp))
            Txt(title, AniMixTheme.type.subheading, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
    }
}

/** Remote image with a quiet placeholder while it loads or when it fails. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = AniMixTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Boolean = true,
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
    /** A picture kept on this device, shown instead of [url] when present. */
    local: okio.Path? = null,
) {
    val colors = AniMixTheme.colors
    var state by remember(url, local) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    // Fade in on every load, including memory-cache hits, so posters never pop in.
    val alpha by animateFloatAsState(
        if (state is AsyncImagePainter.State.Success) 1f else 0f,
        animationSpec = tween(if (state is AsyncImagePainter.State.Success) 260 else 0),
    )
    Box(modifier.clip(shape).then(if (placeholder) Modifier.background(colors.surfaceRaised) else Modifier)) {
        if (placeholder && !url.isNullOrBlank() && (state is AsyncImagePainter.State.Empty || state is AsyncImagePainter.State.Loading)) {
            Shimmer(Modifier.fillMaxSize(), RectangleShape)
        }
        if (url != null || local != null) {
            AsyncImage(
                model = local ?: url,
                contentDescription = null,
                contentScale = contentScale,
                colorFilter = colorFilter,
                onState = { state = it },
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, color: Color = AniMixTheme.colors.accent, strokeWidth: Dp = 3.dp) {
    if (LocalUiPerformance.current.animations) CircularProgressIndicator(modifier, color = color, strokeWidth = strokeWidth)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(AniMixIcons.Clock, "Загрузка", Modifier.fillMaxSize(), tint = color) }
}

/** A page that is still loading: the indicator in the middle of the page width, where `MessageState` puts its icon. */
@Composable
fun PageLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

fun Anime.metaLine(): String = buildList {
    val count = availableEpisodes
    when {
        kind == AnimeKind.Movie -> add("Фильм")
        count > 0 -> add("$count ${episodesWord(count)}")
        else -> add(kind.label)
    }
    year?.let { add(it.toString()) }
}.joinToString(" · ")

fun episodesWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "серий"
        mod10 == 1 -> "серия"
        mod10 in 2..4 -> "серии"
        else -> "серий"
    }
}

fun formatScore(score: Double): String {
    val tenths = (score * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
fun ScoreBadge(score: Double, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Row(
        modifier.clip(AniMixTheme.shapes.pill).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AniMixIcons.Star, null, Modifier.size(11.dp), tint = colors.star)
        Spacer(Modifier.width(3.dp))
        Txt(formatScore(score), AniMixTheme.type.micro, color = Color.White)
    }
}

/**
 * A long press that has to be meant: the finger stays down for [holdMs] and moves less than [tolerance],
 * and nothing under it (a list, a pager) takes the gesture over. A press that starts a scroll, a fling
 * stopped by a touch or a resting thumb drifting by a few pixels never opens anything. Observes only:
 * taps still reach the clickable below.
 */
fun Modifier.deliberateHold(holdMs: Long = 500, tolerance: androidx.compose.ui.unit.Dp = 8.dp, onHold: () -> Unit): Modifier =
    pointerInput(Unit) {
        val slop = tolerance.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val fired = withTimeoutOrNull(holdMs) {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!change.pressed || event.changes.size > 1) return@withTimeoutOrNull false
                    // The press itself is always consumed by the card's own clickable: that is not a takeover.
                    // Only a consumed movement means a list or a pager has taken the gesture. (Until 1.3.1 the
                    // consumed press was read as a takeover, so the hold never fired on a real touch.)
                    if (change.previousPressed && change.isConsumed) return@withTimeoutOrNull false
                    if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (fired == null) onHold()
        }
    }

/** What the viewer's own lists say about a title: its list, and how far a title in «Смотрю» is watched. */
@androidx.compose.runtime.Immutable
data class ListMark(val label: String, val progress: Float? = null)

/** List marks by [Anime.key] for every poster on screen; the app shell provides them from the library. */
val LocalListMarks = androidx.compose.runtime.compositionLocalOf<Map<String, ListMark>> { emptyMap() }

/**
 * Poster card used in shelves and grids. A title from the viewer's lists says so on the poster, in
 * words («Смотрю», «Просмотрено»), with a thin line of progress while it is being watched; [mark] is
 * off where the screen already says it (one's own lists).
 */
@Composable
fun AnimeCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null, mark: Boolean = true) {
    val type = AniMixTheme.type
    val interaction = remember { MutableInteractionSource() }
    val listed = if (mark) LocalListMarks.current[anime.key] else null
    val actions = LocalPosterActions.current
    val haptics = rememberHaptics()
    val menu = LocalPosterMenu.current.takeIf { actions != null }
    // Where the poster is on screen: its menu drops from it.
    var poster by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    // The release after a hold that opened the menu is not a tap on the poster.
    var held by remember { mutableStateOf(false) }
    val openMenu = {
        val place = poster?.takeIf { it.isAttached }
        if (menu != null && place != null) menu.request = PosterMenuRequest(anime, place.boundsInRoot())
    }
    Column(modifier.pressScale(interaction)
        .then(if (menu != null) Modifier.deliberateHold { haptics.longPress(); held = true; openMenu() }
            // With a mouse the same menu opens on the right button.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) openMenu()
                    }
                }
            } else Modifier)
        .clickable(interaction, indication = null, onClick = { if (held) held = false else onClick() })
        .semantics { if (menu != null) onLongClick("Действия с тайтлом") { openMenu(); true } }
        .focusOutline(interaction, AniMixTheme.shapes.large)) {
        // The press shows on the poster itself, in its rounded shape, not as a box around the caption.
        Box(Modifier.onGloballyPositioned { poster = it }.clip(AniMixTheme.shapes.large).indication(interaction, androidx.compose.foundation.LocalIndication.current)) {
            RemoteImage(anime.posterUrl, Modifier.fillMaxWidth().aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
            anime.score?.let { ScoreBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
            if (listed != null) {
                Txt(listed.label, type.micro, color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, bottom = if (listed.progress != null) 9.dp else 6.dp)
                        .clip(AniMixTheme.shapes.pill).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 3.dp))
                listed.progress?.let { progress ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(AniMixTheme.colors.accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // The year and episodes follow the title directly; rows of cards still line up at the posters.
        Txt(anime.title, type.label, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(2.dp))
        Txt(caption ?: anime.metaLine(), type.micro, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun AnimeCardPlaceholder(modifier: Modifier = Modifier) {
    SkeletonGroup("Загружается тайтл", modifier) {
        Column {
            Shimmer(Modifier.fillMaxWidth().aspectRatio(0.7f), AniMixTheme.shapes.large)
            Spacer(Modifier.height(10.dp))
            SkeletonText(AniMixTheme.type.label, Modifier.padding(horizontal = 2.dp), lines = 2)
            Spacer(Modifier.height(2.dp))
            SkeletonText(AniMixTheme.type.micro, Modifier.padding(horizontal = 2.dp), fraction = 0.6f)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) MoreChip(action, onAction)
    }
}

/**
 * The quiet way onward from a heading: «Все ›», «Открыть ›». A chip with a chevron reads as the way to
 * the whole list, not as a second heading; the same in every section and on every plate.
 */
@Composable
fun MoreChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.clip(AniMixTheme.shapes.pill).background(AniMixTheme.colors.chip)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .focusOutline(interaction, AniMixTheme.shapes.pill)
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, AniMixTheme.type.label, color = AniMixTheme.colors.textPrimary, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        Icon(AniMixIcons.ChevronRight, null, Modifier.size(14.dp), tint = AniMixTheme.colors.textSecondary)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.pill
    val background by animateColorAsState(if (selected) colors.accentSoft else colors.chip)
    val content by animateColorAsState(if (selected) colors.accentText else colors.textSecondary)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .clip(shape)
            .background(background)
            .rim(shape, accent = selected)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .focusOutline(interaction, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = content)
            Spacer(Modifier.width(6.dp))
        }
        Txt(text, AniMixTheme.type.label, color = content, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(50.dp)
            .pressScale(interaction)
            .then(if (enabled) Modifier.accentGlow(shape) else Modifier)
            .clip(shape)
            .background(if (enabled) colors.accentGradient else SolidColor(colors.surfaceRaised))
            .then(if (enabled) Modifier.highlight(shape) else Modifier)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick)
            .focusOutline(interaction, shape)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (enabled) colors.onAccent else colors.textMuted
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = content)
            Spacer(Modifier.width(8.dp))
        }
        Txt(text, AniMixTheme.type.subheading, color = content, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val colors = AniMixTheme.colors
    val shape = AniMixTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction)
            .clip(shape)
            .background(colors.chip)
            .liquidSheen()
            .rim(shape, accent = false)
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .focusOutline(interaction, shape)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = colors.textPrimary)
            Spacer(Modifier.width(8.dp))
        }
        Txt(text, AniMixTheme.type.subheading, maxLines = 1)
    }
}

/**
 * Unread count on the corner of a round button: an accent pill outlined in the page colour so it
 * reads as separate from the button under it. Above nine it says «9+».
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Box(
        modifier
            .height(20.dp)
            .widthIn(min = 20.dp)
            .border(2.dp, colors.background, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(if (count > 9) "9+" else "$count", AniMixTheme.type.micro, color = colors.onAccent, maxLines = 1)
    }
}

/** Round icon button; [glass] gives it a translucent backing for use over images. */
@Composable
fun IconCircle(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glass: Boolean = false,
    tint: Color = Color.Unspecified,
    active: Boolean = false,
    contentDescription: String? = null,
    enabled: Boolean = true,
    /** The layer under a glass button that lies over a page: blurred for real instead of a translucent fill. */
    glassSource: dev.chrisbanes.haze.HazeState? = null,
) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction, pressed = 0.92f)
            .then(
                when {
                    active -> Modifier.clip(CircleShape).background(colors.accentSoft).rim(CircleShape, accent = true)
                    glass -> Modifier.glass(CircleShape, backdrop = glassSource != null, source = glassSource, radius = 18.dp, tint = 0.45f, lens = true)
                    else -> Modifier.clip(CircleShape).background(colors.chip).liquidSheen().rim(CircleShape, accent = false)
                },
            )
            .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .focusOutline(interaction, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val iconTint = when {
            tint != Color.Unspecified -> tint
            active -> colors.accent
            else -> colors.textPrimary
        }
        Icon(icon, contentDescription ?: if (icon == AniMixIcons.Back) "Назад" else null,
            Modifier.size(if (size >= 50.dp) 24.dp else 20.dp), tint = iconTint)
    }
}

/**
 * The edge of every AniMix surface, cut like glass: light falls from the top left, so that corner is the
 * brightest, the middle of the edge almost vanishes and the lower right catches a fainter reflection.
 * Accent rims carry the accent the same way. Battery saving keeps the simple top-lit rim.
 */
@Composable
fun Modifier.rim(shape: Shape, accent: Boolean): Modifier {
    val colors = AniMixTheme.colors
    // Frosted glass (liquid glass off) and battery saving: the calm rim, lit evenly along the top.
    if (!LocalUiPerformance.current.liquid) {
        val plain = if (accent) Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.45f), colors.accent.copy(alpha = 0.12f)))
            else Brush.verticalGradient(listOf(colors.rimHighlight, Color.Transparent))
        return border(1.dp, plain, shape)
    }
    val (bright, faint) = if (accent) colors.accent.copy(alpha = 0.6f) to colors.accent.copy(alpha = 0.22f)
        else colors.rimHighlight.copy(alpha = (colors.rimHighlight.alpha * if (colors.isDark) 2.6f else 1f).coerceAtMost(1f)) to
            colors.rimHighlight.copy(alpha = colors.rimHighlight.alpha * if (colors.isDark) 1.1f else 0.7f)
    return border(1.dp, SpecularEdge(bright, faint), shape)
}

/** A diagonal light across a shape's bounds: [bright] at the top left, nothing in the middle, [faint] at the lower right. */
private class SpecularEdge(private val bright: Color, private val faint: Color) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader =
        androidx.compose.ui.graphics.LinearGradientShader(
            from = androidx.compose.ui.geometry.Offset.Zero,
            to = androidx.compose.ui.geometry.Offset(size.width, size.height),
            colors = listOf(bright, bright.copy(alpha = bright.alpha * 0.35f), Color.Transparent, Color.Transparent, faint),
            colorStops = listOf(0f, 0.22f, 0.42f, 0.7f, 1f),
        )
    override fun equals(other: Any?) = other is SpecularEdge && other.bright == bright && other.faint == faint
    override fun hashCode() = bright.hashCode() * 31 + faint.hashCode()
}

/**
 * Depth for surfaces on a light theme: a wide, faint shadow of the text colour under the shape, so
 * white plates do not melt into a white page. Dark themes keep their rims; battery saving drops it.
 */
@Composable
fun Modifier.softShadow(shape: Shape): Modifier {
    val colors = AniMixTheme.colors
    if (colors.isDark || LocalUiPerformance.current.batterySaver) return this
    return dropShadow(shape, Shadow(radius = 20.dp, color = colors.textPrimary.copy(alpha = 0.08f), offset = DpOffset(0.dp, 6.dp)))
}

/** Soft pink light around an accented control. */
@Composable
fun Modifier.accentGlow(shape: Shape, strength: Float = 0.22f): Modifier {
    if (LocalUiPerformance.current.batterySaver) return this
    val colors = AniMixTheme.colors
    return dropShadow(
        shape,
        Shadow(radius = 18.dp, color = colors.accent.copy(alpha = strength * colors.glowScale), offset = DpOffset(0.dp, 6.dp)),
    )
}

/** The glossy highlight on filled buttons: light catching the top edge. */
@Composable
fun Modifier.highlight(shape: Shape): Modifier =
    if (LocalUiPerformance.current.batterySaver) this else innerShadow(shape, Shadow(radius = 4.dp, color = Color.White.copy(alpha = 0.22f), offset = DpOffset(0.dp, 1.dp)))

@Composable
fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector = AniMixIcons.Refresh,
) {
    val colors = AniMixTheme.colors
    // Always centred across the page, also inside left-aligned columns.
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = colors.accent)
            }
            Spacer(Modifier.height(16.dp))
        }
        Txt(title, AniMixTheme.type.subheading, align = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Txt(message, AniMixTheme.type.caption, align = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(actionLabel, onAction, icon = actionIcon)
        }
    }
}

/** The icon of a settings-style row: the glyph in a soft accent circle, the same in every list of the app. */
@Composable
fun RowIcon(icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AniMixTheme.colors
    Box(modifier.size(RowIconSize).clip(CircleShape).background(if (enabled) colors.accentSoft else colors.chip), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (enabled) colors.accent else colors.textMuted)
    }
}

/** Size of [RowIcon]; with the 14 dp edge and the 12 dp gap the text of every row starts [RowTextInset] in. */
val RowIconSize = 28.dp
val RowTextInset = 54.dp

/** A thin line between rows of one panel, starting where their text starts. */
@Composable
fun RowDivider() = Box(Modifier.fillMaxWidth().padding(start = RowTextInset).height(1.dp).background(AniMixTheme.colors.outline))

/** A group of rows under a quiet caption: settings read as a few named groups instead of one long list. */
@Composable
fun RowGroup(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Txt(title.uppercase(), AniMixTheme.type.micro, color = AniMixTheme.colors.textMuted,
            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        Panel { content() }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null, subtitle: String? = null, enabled: Boolean = true) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().toggleable(checked, interactionSource = interaction,
            indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Switch,
            onValueChange = { value -> haptics.toggle(value); onChange(value) })
            .focusOutline(interaction, AniMixTheme.shapes.medium)
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, enabled = enabled)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            if (subtitle != null) Txt(subtitle, AniMixTheme.type.caption)
        }
        Spacer(Modifier.width(12.dp))
        val thumb by animateFloatAsState(if (checked) 1f else 0f)
        val track by animateColorAsState(if (checked && enabled) colors.accent else colors.surfaceRaised)
        Box(Modifier.size(width = 44.dp, height = 26.dp).clip(AniMixTheme.shapes.pill).background(track)
            .border(1.dp, if (checked && enabled) track else colors.outline, AniMixTheme.shapes.pill), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = (3 + 18 * thumb).dp).size(20.dp).clip(CircleShape)
                .background(if (checked && enabled) colors.onAccent else colors.textMuted))
        }
    }
}

@Composable
fun NavRow(title: String, icon: ImageVector, onClick: () -> Unit, trailing: String? = null) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .focusOutline(interaction, AniMixTheme.shapes.medium).padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val type = AniMixTheme.type
            val measurer = rememberTextMeasurer()
            val available = with(LocalDensity.current) { maxWidth.roundToPx() }
            val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
            val titleWidth = measurer.measure(title, type.bodyStrong, softWrap = false, maxLines = 1).size.width
            val trailingWidth = trailing?.let { measurer.measure(it, type.caption, softWrap = false, maxLines = 1).size.width } ?: 0
            if (trailing != null && titleWidth + trailingWidth + gap > available) {
                Column {
                    Txt(title, type.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    Txt(trailing, type.caption)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, type.bodyStrong, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    Txt(trailing, type.caption)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

/** Shared, quiet progress track for profile XP and other determinate values. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val value by animateFloatAsState(progress.coerceIn(0f, 1f))
    Box(modifier.fillMaxWidth().height(4.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) }) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(colors.accent))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = AniMixTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(AniMixTheme.shapes.large)
            .clip(AniMixTheme.shapes.large)
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.72f else 0.82f))
            .liquidSheen(0.7f)
            .rim(AniMixTheme.shapes.large, accent = false),
    ) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
