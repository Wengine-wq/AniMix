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

/** Remote image with a quiet placeholder while it loads or when it fails. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = AniMixTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Boolean = true,
) {
    val colors = AniMixTheme.colors
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    // Fade in on every load, including memory-cache hits, so posters never pop in.
    val alpha by animateFloatAsState(
        if (state is AsyncImagePainter.State.Success) 1f else 0f,
        animationSpec = tween(if (state is AsyncImagePainter.State.Success) 260 else 0),
    )
    Box(modifier.clip(shape).then(if (placeholder) Modifier.background(colors.surfaceRaised) else Modifier)) {
        if (placeholder && !url.isNullOrBlank() && (state is AsyncImagePainter.State.Empty || state is AsyncImagePainter.State.Loading)) {
            Shimmer(Modifier.fillMaxSize(), RectangleShape)
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
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

/** Poster card used in shelves and grids. */
@Composable
fun AnimeCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    val type = AniMixTheme.type
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.pressScale(interaction).clickable(interaction, indication = null, onClick = onClick)
        .focusOutline(interaction, AniMixTheme.shapes.large)) {
        // The press shows on the poster itself, in its rounded shape, not as a box around the caption.
        Box(Modifier.clip(AniMixTheme.shapes.large).indication(interaction, androidx.compose.foundation.LocalIndication.current)) {
            RemoteImage(anime.posterUrl, Modifier.fillMaxWidth().aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
            anime.score?.let { ScoreBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
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
        if (action != null && onAction != null) {
            val interaction = remember { MutableInteractionSource() }
            Txt(
                action,
                AniMixTheme.type.label,
                color = AniMixTheme.colors.accent,
                modifier = Modifier.clip(AniMixTheme.shapes.pill)
                    .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onAction)
                    .focusOutline(interaction, AniMixTheme.shapes.pill)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
                    glass -> Modifier.glass(CircleShape, backdrop = false)
                    else -> Modifier.clip(CircleShape).background(colors.chip).rim(CircleShape, accent = false)
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
 * The thin light rim from the concepts: brighter along the top edge, fading downwards.
 * Accent rims are pink; neutral ones are a faint highlight.
 */
@Composable
fun Modifier.rim(shape: Shape, accent: Boolean): Modifier {
    val colors = AniMixTheme.colors
    val brush = if (accent) {
        Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.45f), colors.accent.copy(alpha = 0.12f)))
    } else {
        Brush.verticalGradient(listOf(colors.rimHighlight, Color.Transparent))
    }
    return border(1.dp, brush, shape)
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

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null, subtitle: String? = null, enabled: Boolean = true) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().toggleable(checked, interactionSource = interaction,
            indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .focusOutline(interaction, AniMixTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            if (subtitle != null) Txt(subtitle, AniMixTheme.type.caption)
        }
        Spacer(Modifier.width(12.dp))
        val thumb by animateFloatAsState(if (checked) 1f else 0f)
        val track by animateColorAsState(if (checked && enabled) colors.accent else colors.surfaceRaised)
        Box(Modifier.size(width = 52.dp, height = 32.dp).clip(AniMixTheme.shapes.pill).background(track)
            .border(1.dp, if (checked && enabled) track else colors.outline, AniMixTheme.shapes.pill), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = (4 + 20 * thumb).dp).size(24.dp).clip(CircleShape)
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
            .focusOutline(interaction, AniMixTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.textSecondary)
        Spacer(Modifier.width(14.dp))
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
            .clip(AniMixTheme.shapes.large)
            .background(colors.surface.copy(alpha = 0.72f))
            .rim(AniMixTheme.shapes.large, accent = false),
    ) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
