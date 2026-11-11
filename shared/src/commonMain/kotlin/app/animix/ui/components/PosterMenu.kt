package app.animix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.animix.data.library.LibraryEntry
import app.animix.model.Anime
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance

/**
 * What a long press on a poster can do without opening the title: the app shell provides it, so every
 * [AnimeCard] in shelves, grids, search and lists offers the same short menu.
 */
@Stable
class PosterActions(
    val entry: (Anime) -> LibraryEntry?,
    val watch: (Anime) -> Unit,
    val plan: (Anime) -> Unit,
    val favorite: (Anime) -> Unit,
    val download: (Anime) -> Unit,
    val open: (Anime) -> Unit,
)

val LocalPosterActions = compositionLocalOf<PosterActions?> { null }

/**
 * Light touches of the device for the moments a person feels: a tab changes, a switch flips, a menu
 * opens, a skip is taken. They go with «Жидкое стекло»: calm frosted glass keeps the phone still too.
 */
class AniMixHaptics(private val perform: ((HapticFeedbackType) -> Unit)?) {
    fun tick() = perform?.invoke(HapticFeedbackType.SegmentTick)
    fun toggle(on: Boolean) = perform?.invoke(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    fun longPress() = perform?.invoke(HapticFeedbackType.LongPress)
    fun confirm() = perform?.invoke(HapticFeedbackType.Confirm)
}

@Composable
fun rememberHaptics(): AniMixHaptics {
    val feedback = LocalHapticFeedback.current
    val on = LocalUiPerformance.current.liquid
    return AniMixHaptics(if (on) { type -> feedback.performHapticFeedback(type) } else null)
}

/**
 * The menu of a long-pressed poster: the shared bottom sheet with the title and «Смотреть», the list,
 * favourites, downloads and the anime page. One tap does it and closes the sheet. A popup over the
 * poster was tried in 1.3.0: under the finger that was still down it opened and closed at once, so the
 * sheet, which lives in its own window, is the reliable place for it.
 */
@Composable
internal fun PosterMenu(anime: Anime, actions: PosterActions, onDismiss: () -> Unit) {
    val entry = actions.entry(anime)
    AniMixSheet(onDismiss, title = anime.title, subtitle = anime.metaLine()) {
        Column(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large)) {
            MenuRow(AniMixIcons.Play, if (entry?.episodePositions?.values?.any { it > 0 } == true) "Продолжить" else "Смотреть") { actions.watch(anime); onDismiss() }
            if (entry?.status == null) MenuRow(AniMixIcons.Bookmark, "Запланировать") { actions.plan(anime); onDismiss() }
            MenuRow(AniMixIcons.Heart, if (entry?.favorite == true) "Убрать из избранного" else "В избранное") { actions.favorite(anime); onDismiss() }
            MenuRow(AniMixIcons.Download, "Скачать серии") { actions.download(anime); onDismiss() }
            MenuRow(AniMixIcons.Info, "Страница аниме") { actions.open(anime); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(AniMixTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Txt(label, AniMixTheme.type.bodyStrong, maxLines = 1)
    }
}
