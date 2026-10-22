package app.animix.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance

private val LocalSkeletonPhase = staticCompositionLocalOf<State<Float>?> { null }

@Composable
private fun skeletonPhase(): State<Float>? {
    if (!LocalUiPerformance.current.animations) return null
    return LocalSkeletonPhase.current ?: rememberInfiniteTransition(label = "skeleton").animateFloat(
        -0.5f, 1.5f,
        infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "skeletonSweep",
    )
}

/** One animation clock and one accessible loading announcement per group. No fake controls. */
@Composable
fun SkeletonGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val phase = skeletonPhase()
    CompositionLocalProvider(LocalSkeletonPhase provides phase) {
        Box(modifier.clearAndSetSemantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }) { content() }
    }
}

/** The phase is read while drawing, so shimmer never recomposes or remeasures its content. */
@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: Shape = AniMixTheme.shapes.medium) {
    val colors = AniMixTheme.colors
    val phase = skeletonPhase()
    val base = colors.textPrimary.copy(alpha = 0.07f)
    val highlight = colors.textPrimary.copy(alpha = 0.045f)
    Box(modifier.clip(shape).background(base).drawBehind {
        if (phase != null) {
            val center = size.width * phase.value
            val band = size.width * 0.5f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(center - band, 0f), end = Offset(center + band, size.height * 0.3f)))
        }
    })
}

/** Reserve the real type's line height, including the user's system font scale. */
@Composable
fun SkeletonText(style: TextStyle, modifier: Modifier = Modifier, lines: Int = 1, fraction: Float = 0.85f) {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Column(modifier.fillMaxWidth()) {
        repeat(lines) { index ->
            Box(Modifier.fillMaxWidth().height(line), contentAlignment = Alignment.CenterStart) {
                Shimmer(Modifier.fillMaxWidth(if (index == lines - 1 && lines > 1) fraction * 0.72f else fraction)
                    .height(line * 0.55f), AniMixTheme.shapes.pill)
            }
        }
    }
}

@Composable
fun AnimeShelfPlaceholder(title: String, modifier: Modifier = Modifier, cardWidth: Dp = 132.dp,
    horizontalPadding: Dp = 20.dp, showMore: Boolean = false) {
    SkeletonGroup("Загружается раздел «$title»", modifier) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
                Txt(title, AniMixTheme.type.heading, Modifier.weight(1f))
                if (showMore) Box(Modifier.width(44.dp).height(28.dp), contentAlignment = Alignment.Center) {
                    Shimmer(Modifier.width(28.dp).height(10.dp), AniMixTheme.shapes.pill)
                }
            }
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val count = (maxWidth / (cardWidth + 12.dp)).toInt() + 2
                LazyRow(contentPadding = PaddingValues(horizontal = horizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                    items(count) { AnimeCardPlaceholder(Modifier.width(cardWidth)) }
                }
            }
        }
    }
}

@Composable
fun SelectionPlaceholder(title: String = "Озвучка") {
    SkeletonGroup("Загружается $title") {
        Column {
            Txt(title, AniMixTheme.type.heading, Modifier.padding(bottom = 8.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Shimmer(Modifier.size(18.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonText(AniMixTheme.type.bodyStrong, fraction = 0.6f)
                    SkeletonText(AniMixTheme.type.micro, fraction = 0.4f)
                }
            }
        }
    }
}

@Composable
fun ReviewPlaceholder() {
    SkeletonGroup("Загружается отзыв") {
        Panel {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Shimmer(Modifier.size(36.dp), CircleShape)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        SkeletonText(AniMixTheme.type.bodyStrong, fraction = 0.45f)
                        SkeletonText(AniMixTheme.type.micro, fraction = 0.3f)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SkeletonText(AniMixTheme.type.body, lines = 3)
            }
        }
    }
}
