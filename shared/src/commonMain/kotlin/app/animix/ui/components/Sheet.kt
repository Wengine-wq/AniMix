package app.animix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.animix.ui.theme.AniMixTheme

/** The AniMix bottom sheet: one look for filters, lists, rating and downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniMixSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AniMixTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp,
        containerColor = colors.surfaceRaised,
        scrimColor = colors.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(AniMixTheme.shapes.pill).background(colors.outline))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            if (title != null) {
                Txt(title, AniMixTheme.type.heading)
                if (subtitle != null) Txt(subtitle, AniMixTheme.type.caption, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/** A full-width option row for sheets: icon, title and an optional check. */
@Composable
fun SheetOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
) {
    val colors = AniMixTheme.colors
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium)
            .background(if (selected) colors.accentSoft else colors.chip)
            .rim(AniMixTheme.shapes.medium, accent = selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp), tint = if (selected) colors.accent else colors.textSecondary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong, color = if (selected) colors.accentText else colors.textPrimary, maxLines = 1)
            if (subtitle != null) Txt(subtitle, AniMixTheme.type.micro, maxLines = 1)
        }
        if (selected) androidx.compose.material3.Icon(app.animix.ui.icons.AniMixIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
    }
}

