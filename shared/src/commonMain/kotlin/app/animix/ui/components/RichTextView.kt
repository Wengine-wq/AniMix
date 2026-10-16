package app.animix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.core.RichBlock
import app.animix.core.RichText
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import kotlin.math.roundToInt

/** Renders [RichText]: paragraphs, quotes with their author, and tap-to-reveal spoilers. */
@Composable
fun RichTextView(text: RichText, modifier: Modifier = Modifier, style: TextStyle = AniMixTheme.type.body, color: Color = AniMixTheme.colors.textSecondary) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        text.blocks.forEach { RichBlockView(it, style, color) }
    }
}

@Composable
private fun RichBlockView(block: RichBlock, style: TextStyle, color: Color) {
    val colors = AniMixTheme.colors
    when (block) {
        is RichBlock.Paragraph -> BasicText(block.annotated(), style = style.copy(color = color))
        is RichBlock.Quote -> Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(AniMixTheme.shapes.medium).background(colors.chip),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accent.copy(alpha = 0.45f)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.author?.let { Txt(it, AniMixTheme.type.micro, color = colors.accentText) }
                block.blocks.forEach { RichBlockView(it, style, color.copy(alpha = 0.85f)) }
            }
        }
        is RichBlock.Spoiler -> SpoilerView(block, style, color)
    }
}

@Composable
private fun SpoilerView(block: RichBlock.Spoiler, style: TextStyle, color: Color) {
    val colors = AniMixTheme.colors
    var shown by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).background(colors.chip)
            .clickable { shown = !shown }.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(if (shown) "Спойлер" else "Спойлер · нажмите, чтобы показать", AniMixTheme.type.label, color = colors.accentText, modifier = Modifier.weight(1f))
            val angle by animateFloatAsState(if (shown) 180f else 0f)
            Icon(AniMixIcons.ChevronDown, null, Modifier.size(16.dp).rotate(angle), tint = colors.accentText)
        }
        AnimatedVisibility(shown, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { RichBlockView(it, style, color) }
            }
        }
    }
}

private fun RichBlock.Paragraph.annotated(): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val weight = if (span.bold) FontWeight.SemiBold else null
        val italic = if (span.italic) FontStyle.Italic else null
        if (weight == null && italic == null) append(span.text)
        else withStyle(SpanStyle(fontWeight = weight, fontStyle = italic)) { append(span.text) }
    }
}

/**
 * Shows the first [collapsedHeight] of its content with a soft fade and a clear
 * "Читать полностью" control, and expands or collapses smoothly in both directions.
 * Content that fits is shown as is, without any control.
 */
@Composable
fun Expandable(
    modifier: Modifier = Modifier,
    collapsedHeight: Dp = 120.dp,
    fadeColor: Color = AniMixTheme.colors.background,
    content: @Composable () -> Unit,
) {
    val colors = AniMixTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fullHeight by remember { mutableIntStateOf(-1) }
    val collapsedPx = with(LocalDensity.current) { collapsedHeight.roundToPx() }
    // A little slack so a line that barely overflows isn't hidden behind a button.
    val overflows = fullHeight > collapsedPx * 1.15f
    val target = when {
        fullHeight < 0 -> collapsedPx
        !overflows || expanded -> fullHeight
        else -> collapsedPx
    }
    val height = remember { Animatable(collapsedPx.toFloat()) }
    var measuredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(target, fullHeight >= 0) {
        if (fullHeight < 0) return@LaunchedEffect
        if (!measuredOnce) {
            height.snapTo(target.toFloat())
            measuredOnce = true
        } else {
            height.animateTo(target.toFloat(), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        Box(
            Modifier.clipToBounds().then(
                if (overflows) Modifier.clickable(interaction, indication = null) { expanded = !expanded } else Modifier,
            ),
        ) {
            Layout(content) { measurables, constraints ->
                val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                if (placeable.height != fullHeight) fullHeight = placeable.height
                val shownHeight = if (!measuredOnce) minOf(placeable.height, collapsedPx) else height.value.roundToInt().coerceAtMost(placeable.height)
                layout(placeable.width, shownHeight) { placeable.place(0, 0) }
            }
            val fade by animateFloatAsState(if (overflows && !expanded) 1f else 0f)
            if (fade > 0f) {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to fadeColor.copy(alpha = fade)),
                    ),
                )
            }
        }
        if (overflows) {
            Row(
                Modifier.padding(top = 6.dp).clip(AniMixTheme.shapes.pill).clickable { expanded = !expanded }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(if (expanded) "Свернуть" else "Читать полностью", AniMixTheme.type.label, color = colors.accent)
                Spacer(Modifier.width(4.dp))
                val angle by animateFloatAsState(if (expanded) 180f else 0f)
                Icon(AniMixIcons.ChevronDown, null, Modifier.size(16.dp).rotate(angle), tint = colors.accent)
            }
        }
    }
}
