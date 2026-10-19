package app.animix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.roundToInt

/** One liquid bridge follows the fractional page position. The active capsule also shows the timer. */
@Composable
fun PageIndicator(count: Int, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, autoProgress: Float? = null) {
    if (count < 2) return
    val colors = AniMixTheme.colors
    val raw = if (position.isFinite()) position else 0f
    val visual = if (LocalUiPerformance.current.animations) raw else raw.roundToInt().toFloat()
    val normalized = ((visual % count) + count) % count
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val spacing = 28.dp.toPx()
                val radius = 3.dp.toPx()
                val y = size.height / 2
                repeat(count) { drawCircle(colors.outline, radius, Offset(spacing * (it + 0.5f), y)) }
                val source = floor(normalized).toInt()
                val phase = normalized - source
                clipRect {
                    // Copies at the edges let the last dot flow out right and reappear at the first.
                    for (shift in listOf(-count, 0, count)) {
                        val x1 = spacing * (source + shift + 0.5f)
                        val x2 = x1 + spacing
                        val r1 = radius * (3f - 2f * phase)
                        val r2 = radius * (1f + 2f * phase)
                        drawOval(lerp(colors.outline, colors.accent, 1f - phase), Offset(x1 - r1, y - radius), Size(r1 * 2, radius * 2))
                        drawOval(lerp(colors.outline, colors.accent, phase), Offset(x2 - r2, y - radius), Size(r2 * 2, radius * 2))
                        val connection = sin(phase * PI).toFloat().coerceAtLeast(0f)
                        if (connection > 0.01f) {
                            val start = x1 + r1 * 0.5f
                            val end = x2 - r2 * 0.5f
                            val middle = (start + end) / 2
                            val neck = radius * connection * 0.75f
                            val bridge = Path().apply {
                                moveTo(start, y - radius * 0.86f)
                                cubicTo(middle, y - neck, middle, y - neck, end, y - radius * 0.86f)
                                lineTo(end, y + radius * 0.86f)
                                cubicTo(middle, y + neck, middle, y + neck, start, y + radius * 0.86f)
                                close()
                            }
                            drawPath(bridge, colors.accent.copy(alpha = connection))
                        }
                        if (phase < 0.001f && autoProgress != null) {
                            val origin = Offset(x1 - r1, y - radius)
                            // Erase the full active oval first, leaving a readable tinted track.
                            drawRoundRect(colors.surfaceRaised, origin, Size(r1 * 2, radius * 2), CornerRadius(radius))
                            drawRoundRect(colors.accent.copy(alpha = 0.3f), origin, Size(r1 * 2, radius * 2), CornerRadius(radius))
                            clipRect(left = origin.x, right = origin.x + r1 * 2 * autoProgress.coerceIn(0f, 1f)) {
                                drawRoundRect(colors.accent, origin, Size(r1 * 2, radius * 2), CornerRadius(radius))
                            }
                        }
                    }
                }
            }
            Row(Modifier.selectableGroup()) {
                repeat(count) { index ->
                    Box(Modifier.size(width = 28.dp, height = 36.dp)
                        .semantics { contentDescription = "Слайд ${index + 1} из $count" }
                        .selectable(((raw.roundToInt() % count) + count) % count == index,
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Tab, onClick = { onSelect(index) }))
                }
            }
        }
    }
}
