package app.animix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.model.Anime
import app.animix.model.AnimeKind
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import coil3.compose.SubcomposeAsyncImage

@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
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
        overflow = TextOverflow.Ellipsis,
    )
}

/** Remote image with a quiet placeholder while it loads or when it fails. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = AniMixTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = AniMixTheme.colors
    Box(modifier.clip(shape).background(colors.surfaceRaised)) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { Shimmer(Modifier.fillMaxSize()) },
                error = { Box(Modifier.fillMaxSize().background(colors.surfaceRaised)) },
            )
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: Shape = AniMixTheme.shapes.medium) {
    val colors = AniMixTheme.colors
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
    )
    Box(modifier.clip(shape).background(colors.surfaceRaised.copy(alpha = alpha)))
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
fun AnimeCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val type = AniMixTheme.type
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)) {
        Box {
            RemoteImage(anime.posterUrl, Modifier.fillMaxWidth().aspectRatio(0.7f))
            anime.score?.let { ScoreBadge(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Txt(anime.title, type.label, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(2.dp))
        Txt(anime.metaLine(), type.micro, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun AnimeCardPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier) {
        Shimmer(Modifier.fillMaxWidth().aspectRatio(0.7f))
        Spacer(Modifier.height(8.dp))
        Shimmer(Modifier.fillMaxWidth(0.8f).height(12.dp), AniMixTheme.shapes.small)
        Spacer(Modifier.height(6.dp))
        Shimmer(Modifier.fillMaxWidth(0.5f).height(10.dp), AniMixTheme.shapes.small)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Txt(
                action,
                AniMixTheme.type.label,
                color = AniMixTheme.colors.accent,
                modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = AniMixTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else colors.surfaceRaised)
    val content by animateColorAsState(if (selected) colors.onAccent else colors.textPrimary)
    Row(
        modifier
            .clip(AniMixTheme.shapes.pill)
            .background(background)
            .border(1.dp, if (selected) Color.Transparent else colors.outline, AniMixTheme.shapes.pill)
            .clickable(onClick = onClick)
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
    Row(
        modifier
            .height(50.dp)
            .clip(AniMixTheme.shapes.pill)
            .background(if (enabled) colors.accentGradient else Brush.linearGradient(listOf(colors.surfaceRaised, colors.surfaceRaised)))
            .clickable(enabled = enabled, onClick = onClick)
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
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = AniMixTheme.colors
    Row(
        modifier
            .height(50.dp)
            .clip(AniMixTheme.shapes.pill)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, AniMixTheme.shapes.pill)
            .clickable(onClick = onClick)
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
    size: Dp = 42.dp,
    glass: Boolean = false,
    tint: Color = Color.Unspecified,
    active: Boolean = false,
) {
    val colors = AniMixTheme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) colors.accentSoft else if (glass) colors.surfaceGlass else colors.surfaceRaised)
            .border(1.dp, if (active) colors.accent else colors.outline.copy(alpha = if (glass) 0.4f else 1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconTint = when {
            tint != Color.Unspecified -> tint
            active -> colors.accent
            else -> colors.textPrimary
        }
        Icon(icon, null, Modifier.size(size * 0.48f), tint = iconTint)
    }
}

@Composable
fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = AniMixTheme.colors
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
            SecondaryButton(actionLabel, onAction, icon = AniMixIcons.Refresh)
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null, subtitle: String? = null) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
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
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceRaised,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

@Composable
fun NavRow(title: String, icon: ImageVector, onClick: () -> Unit, trailing: String? = null) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.textSecondary)
        Spacer(Modifier.width(14.dp))
        Txt(title, AniMixTheme.type.bodyStrong, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Txt(trailing, AniMixTheme.type.caption)
            Spacer(Modifier.width(6.dp))
        }
        Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

/** Rounded panel that groups rows, as in the settings concept. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = AniMixTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AniMixTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.outline, AniMixTheme.shapes.large),
    ) { content() }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
