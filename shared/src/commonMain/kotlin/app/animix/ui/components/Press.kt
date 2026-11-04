package app.animix.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * The press of every AniMix control: a soft veil of the text colour over the control's own shape
 * (the clip applied before `clickable`), lighter under the mouse. A quick tap still shows it for a
 * moment, so the person always sees that the press landed. Without animations it appears and goes
 * at once; it is feedback, not decoration, so it stays in every mode.
 */
class PressIndication(private val color: Color, private val animate: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color, animate)
    override fun equals(other: Any?): Boolean = other is PressIndication && other.color == color && other.animate == animate
    override fun hashCode(): Int = color.hashCode() * 31 + animate.hashCode()
}

private class PressNode(private val source: InteractionSource, private val color: Color, private val animate: Boolean) : Modifier.Node(), DrawModifierNode {
    private val veil = Animatable(0f)
    private var presses = 0
    private var hovers = 0
    private var pressedAt = TimeSource.Monotonic.markNow()
    private var job: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> { presses++; pressedAt = TimeSource.Monotonic.markNow() }
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                }
                val target = when { presses > 0 -> PressedAlpha; hovers > 0 -> HoverAlpha; else -> 0f }
                val released = interaction is PressInteraction.Release || interaction is PressInteraction.Cancel
                job?.cancel()
                job = coroutineScope.launch {
                    if (released && target < PressedAlpha) {
                        // Hold a quick tap long enough to be seen.
                        val held = pressedAt.elapsedNow().inWholeMilliseconds
                        if (held < MinimumPressMs) { if (animate) veil.animateTo(PressedAlpha, tween(60)) else veil.snapTo(PressedAlpha); delay(MinimumPressMs - held) }
                    }
                    if (animate) veil.animateTo(target, tween(if (target > veil.value) 80 else 220)) else veil.snapTo(target)
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = veil.value
        if (alpha > 0f) drawRect(color.copy(alpha = alpha))
    }
}

private const val PressedAlpha = 0.12f
private const val HoverAlpha = 0.05f
private const val MinimumPressMs = 140L
