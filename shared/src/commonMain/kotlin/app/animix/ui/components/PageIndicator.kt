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
import app.animix.ui.theme.AniMixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fractional pager position drives both shape and tint, including during a finger drag. */
@Composable
fun PageIndicator(count: Int, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (count < 2) return
    val colors = AniMixTheme.colors
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { index ->
            val emphasis = (1f - abs(position - index)).coerceIn(0f, 1f)
            Box(Modifier.size(width = 28.dp, height = 32.dp)
                .semantics { contentDescription = "Слайд ${index + 1} из $count" }
                .selectable(position.roundToInt() == index, interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center) {
                Box(Modifier.width((6f + 12f * emphasis).dp).height(6.dp).clip(AniMixTheme.shapes.pill)
                    .background(lerp(colors.outline, colors.accent, emphasis)))
            }
        }
    }
}
