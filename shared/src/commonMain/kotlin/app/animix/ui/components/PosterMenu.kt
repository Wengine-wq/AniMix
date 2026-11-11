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
 * The menu of a long-pressed poster: a small sheet of glass over the poster with «Смотреть», the list,
 * favourites, downloads and the anime page. One tap does it and closes the menu; a tap outside closes it.
 */
@Composable
internal fun PosterMenu(anime: Anime, actions: PosterActions, onDismiss: () -> Unit) {
    val colors = AniMixTheme.colors
    val entry = actions.entry(anime)
    val shape = AniMixTheme.shapes.large
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 0), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(232.dp)
                .dropShadow(shape, Shadow(radius = 28.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.45f else 0.14f), offset = DpOffset(0.dp, 10.dp)))
                .clip(shape).background(colors.surfaceRaised.copy(alpha = 0.97f)).liquidSheen().rim(shape, accent = false)
                .padding(vertical = 6.dp),
        ) {
            Txt(anime.title, AniMixTheme.type.label, color = colors.textSecondary, maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.accent)
        Spacer(Modifier.width(12.dp))
        Txt(label, AniMixTheme.type.bodyStrong, maxLines = 1)
    }
}
