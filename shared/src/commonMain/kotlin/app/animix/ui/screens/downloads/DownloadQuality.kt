package app.animix.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.animix.ui.components.Txt
import app.animix.ui.components.rim
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

/** Download qualities offered to the viewer; a missing rendition falls back to the closest lower one. */
val DownloadQualities = listOf(
    "480p" to "Меньше места",
    "720p" to "Оптимально",
    "1080p" to "Лучшее качество",
)

/** Asked on the first download; the answer is kept and can be changed in settings. */
@Composable
fun DownloadQualityDialog(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AniMixTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 380.dp).clip(AniMixTheme.shapes.large).background(colors.surfaceRaised)
                .rim(AniMixTheme.shapes.large, accent = false).padding(20.dp),
        ) {
            Txt("Качество загрузок", AniMixTheme.type.heading)
            Spacer(Modifier.height(4.dp))
            Txt("Запомним выбор. Поменять его можно в настройках.", AniMixTheme.type.caption)
            Spacer(Modifier.height(16.dp))
            DownloadQualityOptions(current, onPick)
        }
    }
}

@Composable
fun DownloadQualityOptions(current: String?, onPick: (String) -> Unit) {
    val colors = AniMixTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadQualities.forEach { (quality, hint) ->
            val selected = quality == current
            Row(
                Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium)
                    .background(if (selected) colors.accentSoft else colors.chip)
                    .rim(AniMixTheme.shapes.medium, accent = selected)
                    .clickable { onPick(quality) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(quality, AniMixTheme.type.bodyStrong, color = if (selected) colors.accentText else colors.textPrimary)
                    Txt(hint, AniMixTheme.type.micro)
                }
                if (selected) Icon(AniMixIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
            }
        }
    }
}
