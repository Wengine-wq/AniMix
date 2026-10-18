package app.animix.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.ui.components.AniMixSheet
import app.animix.ui.components.SheetOption
import app.animix.ui.components.Txt
import app.animix.ui.components.rim
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

/** «Списки»: favourites plus one status, all from one sheet. */
@Composable
fun ListsSheet(
    entry: LibraryEntry?,
    favoritesLast: Boolean,
    onFavorite: () -> Unit,
    onStatus: (ListStatus?) -> Unit,
    onDismiss: () -> Unit,
) {
    AniMixSheet(onDismiss, title = "Мой список", subtitle = "Выберите статус просмотра") {
        val favorite: @Composable () -> Unit = {
            SheetOption("Избранное", entry?.favorite == true, onFavorite, icon = if (entry?.favorite == true) AniMixIcons.HeartFilled else AniMixIcons.Heart)
        }
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!favoritesLast) favorite()
            ListStatus.entries.forEach { status ->
                val selected = entry?.status == status
                // Tapping the current status again takes the anime off that list.
                SheetOption(status.label, selected, { onStatus(if (selected) null else status) }, icon = status.icon)
            }
            if (favoritesLast) favorite()
        }
    }
}

internal val ListStatus.icon: ImageVector get() = when (this) {
    ListStatus.Watching -> AniMixIcons.Play
    ListStatus.Planned -> AniMixIcons.Clock
    ListStatus.Completed -> AniMixIcons.Check
    ListStatus.OnHold -> AniMixIcons.Pause
    ListStatus.Dropped -> AniMixIcons.Close
}

/** A personal 1–10 score, kept with the local lists. */
@Composable
fun RateSheet(score: Int?, onScore: (Int?) -> Unit, onDismiss: () -> Unit) {
    val colors = AniMixTheme.colors
    AniMixSheet(onDismiss, title = "Моя оценка", subtitle = "От 1 до 10 · можно изменить в любой момент") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..10).chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { value ->
                        val chosen = value == score
                        Column(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(AniMixTheme.shapes.medium)
                                .background(if (chosen) colors.accentSoft else colors.chip)
                                .rim(AniMixTheme.shapes.medium, accent = chosen)
                                .semantics { selected = chosen; contentDescription = "Оценка $value из 10" }
                                .clickable(role = Role.Button) { onScore(value) }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(if (chosen) AniMixIcons.Star else AniMixIcons.StarOutline, null, Modifier.size(16.dp),
                                tint = if (chosen) colors.accent else colors.textMuted)
                            Spacer(Modifier.height(4.dp))
                            Txt("$value", AniMixTheme.type.subheading, color = if (chosen) colors.accentText else colors.textPrimary)
                        }
                    }
                }
            }
        }
        if (score != null) {
            Spacer(Modifier.height(14.dp))
            Txt(
                "Убрать оценку",
                AniMixTheme.type.label,
                color = colors.accent,
                modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable { onScore(null) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
