package app.animix.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme

/** Focus is functional feedback: keep it visible even when decorative motion is disabled. */
@Composable
fun Modifier.focusOutline(interaction: MutableInteractionSource, shape: Shape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) border(2.dp, AniMixTheme.colors.accent, shape) else this
}
