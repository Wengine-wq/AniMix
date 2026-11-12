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
import app.animix.data.library.LibraryEntry
import app.animix.model.Anime
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

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

/** The poster whose menu is open: the title and where its poster is on screen, in the app's root. */
class PosterMenuRequest(val anime: Anime, val bounds: Rect)

/** One menu for the whole app: a held poster asks for it here, [PosterMenuHost] in the app shell draws it. */
@Stable
class PosterMenuState {
    var request by mutableStateOf<PosterMenuRequest?>(null)
}

val LocalPosterMenu = staticCompositionLocalOf<PosterMenuState?> { null }

/**
 * The menu of a held poster, dropped from the poster itself. The page dims, the poster stays lit where
 * it is and a small glass card opens right under it (above it when there is no room below): «Смотреть»,
 * the list, favourites, downloads and the anime page. One tap does it and closes the menu; a tap
 * anywhere else or «Назад» closes it.
 *
 * It is a layer of the app, not a window: the 1.3.0 popup window closed under the finger that was still
 * down, and the 1.3.1 bottom sheet opened far from the poster one was holding. A layer never sees the
 * finger that opened it, so lifting it changes nothing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PosterMenuHost(state: PosterMenuState, actions: PosterActions, topInset: Dp, bottomInset: Dp) {
    val request = state.request
    // The last request keeps drawing while the menu fades out.
    var shown by remember { mutableStateOf<PosterMenuRequest?>(null) }
    val progress = remember { Animatable(0f) }
    val motion = LocalUiPerformance.current.animations
    LaunchedEffect(request) {
        if (request != null) {
            shown = request
            if (motion) progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 520f)) else progress.snapTo(1f)
        } else {
            if (motion) progress.animateTo(0f, tween(140)) else progress.snapTo(0f)
            shown = null
        }
    }
    val current = shown ?: return
    val colors = AniMixTheme.colors
    val density = LocalDensity.current
    val dismiss = { state.request = null }
    @Suppress("DEPRECATION")
    androidx.compose.ui.backhandler.BackHandler(enabled = request != null, onBack = dismiss)
    val entry = actions.entry(current.anime)
    val shape = AniMixTheme.shapes.large
    Layout(
        content = {
            // The dimmed page: a tap on it closes the menu and reaches nothing under it.
            Box(Modifier.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }.background(colors.background.copy(alpha = if (colors.isDark) 0.62f else 0.5f))
                .pointerInput(Unit) { detectTapGestures { dismiss() } })
            // The poster itself, lit over the dimmed page, so the menu visibly belongs to it.
            RemoteImage(current.anime.posterUrl, Modifier.graphicsLayer {
                val lift = 1f + 0.03f * progress.value
                scaleX = lift; scaleY = lift
                alpha = (progress.value * 3f).coerceIn(0f, 1f)
            }.dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.3f), offset = DpOffset(0.dp, 10.dp))), shape = shape)
            Column(
                Modifier.width(236.dp)
                    .dropShadow(shape, Shadow(radius = 28.dp, color = Color.Black.copy(alpha = if (colors.isDark) 0.4f else 0.14f), offset = DpOffset(0.dp, 12.dp)))
                    .glass(shape, tint = 1f, radius = 32.dp, lens = true)
                    // The card swallows taps between its rows instead of letting them close the menu.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .semantics { contentDescription = "Действия с тайтлом" }
                    .padding(6.dp),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                    Txt(current.anime.title, AniMixTheme.type.label, maxLines = 1)
                    Txt(current.anime.metaLine(), AniMixTheme.type.micro, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(colors.outline))
                Spacer(Modifier.height(4.dp))
                val act = { action: (Anime) -> Unit -> { action(current.anime); dismiss() } }
                MenuRow(AniMixIcons.Play, if (entry?.episodePositions?.values?.any { it > 0 } == true) "Продолжить" else "Смотреть", accent = true, onClick = act(actions.watch))
                if (entry?.status == null) MenuRow(AniMixIcons.Bookmark, "Запланировать", onClick = act(actions.plan))
                MenuRow(AniMixIcons.Heart, if (entry?.favorite == true) "Убрать из избранного" else "В избранное", onClick = act(actions.favorite))
                MenuRow(AniMixIcons.Download, "Скачать серии", onClick = act(actions.download))
                MenuRow(AniMixIcons.Info, "Страница аниме", onClick = act(actions.open))
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val anchor = current.bounds
        val scrim = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
        val poster = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(anchor.width.roundToInt().coerceAtLeast(1), anchor.height.roundToInt().coerceAtLeast(1)))
        val menu = measurables[2].measure(androidx.compose.ui.unit.Constraints(maxWidth = width, maxHeight = height))
        val edge = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 8.dp.roundToPx() }
        val top = with(density) { topInset.roundToPx() } + edge
        val bottom = height - with(density) { bottomInset.roundToPx() } - edge
        // Starts at the poster's left edge; a poster by the right edge of the screen gets the menu ending at its right edge.
        val fromEnd = anchor.left + menu.width > width - edge
        val x = (if (fromEnd) anchor.right.roundToInt() - menu.width else anchor.left.roundToInt()).coerceIn(edge, (width - edge - menu.width).coerceAtLeast(edge))
        val below = anchor.bottom.roundToInt() + gap
        val above = anchor.top.roundToInt() - gap - menu.height
        // Under the poster when it fits, over it otherwise; on a short screen it lies over the poster's lower part.
        val up = below + menu.height > bottom && above >= top
        val y = if (up) above else below.coerceAtMost((bottom - menu.height).coerceAtLeast(top))
        layout(width, height) {
            scrim.place(0, 0)
            poster.place(anchor.left.roundToInt(), anchor.top.roundToInt())
            menu.placeWithLayer(x, y) {
                // The card grows out of the poster's corner it hangs from.
                transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, if (up) 1f else 0f)
                val p = progress.value
                scaleX = 0.86f + 0.14f * p; scaleY = scaleX
                alpha = (p * 1.6f).coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().height(44.dp).clip(AniMixTheme.shapes.medium)
        .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
        .focusOutline(interaction, AniMixTheme.shapes.medium)
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (accent) colors.accent else colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Txt(label, AniMixTheme.type.bodyStrong, color = if (accent) colors.accentText else colors.textPrimary, maxLines = 1)
    }
}
