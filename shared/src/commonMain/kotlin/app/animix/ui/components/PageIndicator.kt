package app.animix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Page dots that follow the pager: the current dot widens into a pill and the next one grows
 * as the page is swiped, both straight from the fractional page position. The active pill also
 * fills up with the auto-advance timer. No custom drawing: plain shapes from the theme.
 */
@Composable
fun PageIndicator(count: Int, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, autoProgress: Float? = null) {
    if (count < 2) return
    val colors = AniMixTheme.colors
    val raw = if (position.isFinite()) position else 0f
    val visual = if (LocalUiPerformance.current.animations) raw else raw.roundToInt().toFloat()
    val normalized = ((visual % count) + count) % count
    val current = ((raw.roundToInt() % count) + count) % count
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            // Distance on a ring, so the last dot hands over to the first one smoothly.
            val distance = abs(normalized - index).let { minOf(it, count - it) }
            val weight = (1f - distance).coerceIn(0f, 1f)
            Box(
                Modifier.size(width = 28.dp, height = 36.dp)
                    .semantics { contentDescription = "Слайд ${index + 1} из $count" }
                    .selectable(current == index, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                val width = lerp(6.dp, 22.dp, weight)
                val timed = autoProgress != null && weight > 0.999f
                Box(
                    Modifier.size(width = width, height = 6.dp).clip(AniMixTheme.shapes.pill)
                        .background(if (timed) colors.accent.copy(alpha = 0.3f) else lerp(colors.outline, colors.accent, weight)),
                ) {
                    if (timed) Box(Modifier.fillMaxHeight().fillMaxWidth(autoProgress!!.coerceIn(0f, 1f)).clip(AniMixTheme.shapes.pill).background(colors.accent))
                }
            }
        }
    }
}
