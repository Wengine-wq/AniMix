package app.animix.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    AniMixSheet(onDismiss, title = "Добавить в списки", subtitle = "Избранное и статус можно сочетать") {
        val favorite: @Composable () -> Unit = {
            SheetOption("Избранное", entry?.favorite == true, onFavorite, icon = if (entry?.favorite == true) AniMixIcons.HeartFilled else AniMixIcons.Heart)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!favoritesLast) favorite()
            ListStatus.entries.forEach { status ->
                val selected = entry?.status == status
                // Tapping the current status again takes the anime off that list.
                SheetOption(status.label, selected, { onStatus(if (selected) null else status) }, icon = AniMixIcons.Bookmark)
            }
            if (favoritesLast) favorite()
        }
    }
}

/** A personal 1–10 score, kept with the local lists. */
@Composable
fun RateSheet(score: Int?, onScore: (Int?) -> Unit, onDismiss: () -> Unit) {
    val colors = AniMixTheme.colors
    AniMixSheet(onDismiss, title = "Оценить тайтл", subtitle = score?.let { "Ваша оценка: $it" } ?: "Оценка видна только вам") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { value ->
                val selected = score != null && value <= score
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                        .background(if (selected) colors.accentSoft else colors.chip)
                        .rim(CircleShape, accent = value == score)
                        .clickable { onScore(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("$value", AniMixTheme.type.label, color = if (selected) colors.accentText else colors.textPrimary)
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
