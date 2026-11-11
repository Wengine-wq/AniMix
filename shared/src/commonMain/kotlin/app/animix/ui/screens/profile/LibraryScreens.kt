package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.Chip
import app.animix.ui.components.liquidSheen
import app.animix.ui.components.rim
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Txt
import app.animix.ui.components.MascotMessage
import app.animix.ui.components.MascotPose
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

/**
 * «Списки» tab: «Избранное» and every status in one place. Settings can move
 * «Избранное» from the first chip to the last.
 */
@Composable
fun ListsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues, showBack: Boolean = false) {
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val categories = buildList<ListStatus?> {
        if (!settings.favoritesLast) add(null)
        addAll(ListStatus.entries)
        if (settings.favoritesLast) add(null)
    }
    // null stands for «Избранное».
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable { mutableStateOf(false) }
    // Until a chip is tapped, open the first list that has something in it, not an empty «Избранное».
    val firstFilled = categories.firstOrNull { item -> if (item == null) library.favorites.isNotEmpty() else library.entries.values.any { it.status == item } }
    val status: ListStatus? = if (chosen) ListStatus.entries.firstOrNull { it.name == selectedName } else firstFilled ?: categories.first()
    val entries = if (status == null) library.favorites else library.inStatus(status)
    LibraryGrid(
        title = "Списки",
        entries = entries,
        navigator = navigator,
        contentPadding = contentPadding,
        showBack = showBack,
        header = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { item ->
                    val count = if (item == null) library.favorites.size else library.entries.values.count { it.status == item }
                    val label = item?.label ?: "Избранное"
                    Chip(
                        if (count > 0) "$label · $count" else label,
                        selected = item == status,
                        onClick = { chosen = true; selectedName = item?.name },
                        icon = if (item == null) AniMixIcons.Heart else null,
                    )
                }
                // «Подборки» (experimental) live next to the statuses as one more tab of the lists.
                item {
                    Chip("Подборки · ${library.collections.size}", false, { navigator.push(Route.Collections) }, icon = AniMixIcons.Layers)
                }
            }
            if (status == ListStatus.Watching) WatchingWeek(graph, entries, navigator)
        },
        empty = {
            EmptyLibrary(
                if (status == null) "Здесь будут любимые тайтлы" else "В списке «${status.label}» пока пусто",
                "Найдите аниме в каталоге и добавьте его через закладку на странице тайтла.",
                onExplore = { navigator.select(app.animix.ui.navigation.Tab.Catalog) },
            )
        },
    )
}

@Composable
private fun LibraryGrid(
    title: String,
    entries: List<LibraryEntry>,
    navigator: Navigator,
    contentPadding: PaddingValues,
    showBack: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + 20.dp,
            end = contentPadding.calculateEndPadding(direction) + 20.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showBack) {
                        IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                        Spacer(Modifier.width(12.dp))
                    }
                    Txt(title, AniMixTheme.type.title)
                }
                if (header != null) {
                    Spacer(Modifier.height(16.dp))
                    header()
                }
            }
        }
        if (entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { empty() }
        } else {
            items(entries, key = { it.anime.key }) { entry ->
                AnimeCard(entry.anime, onClick = { navigator.push(Route.Detail(entry.anime)) }, mark = false)
            }
        }
    }
}

/**
 * «На этой неделе» over «Смотрю»: when the next episode of each airing title comes, nearest first, within
 * seven days. The dates are the catalog's; the line is left out when nothing airs this week.
 */
@Composable
private fun WatchingWeek(graph: AppGraph, entries: List<LibraryEntry>, navigator: Navigator) {
    val airing = entries.map { it.anime }.filter { it.status == app.animix.model.AnimeStatus.Ongoing }.take(12)
    val week = app.animix.ui.components.rememberLoad(airing.map { it.key }) {
        val today = app.animix.core.localDayIndex()
        airing.mapNotNull { anime ->
            val detail = runCatching { graph.anime.detail(anime) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
            val at = detail?.nextEpisodeAt ?: return@mapNotNull null
            val label = app.animix.core.weekDayLabel(at, today) ?: return@mapNotNull null
            Triple(detail.anime, label, app.animix.core.daysUntil(at, today) ?: 0L)
        }.sortedBy { it.third }
    }
    val days = (week.value as? app.animix.ui.components.Load.Ready)?.value.orEmpty()
    if (days.isEmpty()) return
    val colors = AniMixTheme.colors
    Spacer(Modifier.height(20.dp))
    Txt("На этой неделе", AniMixTheme.type.subheading)
    Spacer(Modifier.height(10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days, key = { it.first.key }) { (anime, day, _) ->
            Row(Modifier.width(196.dp).clip(AniMixTheme.shapes.medium).background(colors.chip)
                .liquidSheen().rim(AniMixTheme.shapes.medium, accent = day == "Сегодня")
                .clickable { navigator.push(Route.Detail(anime)) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                app.animix.ui.components.RemoteImage(anime.posterUrl, Modifier.width(34.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(day, AniMixTheme.type.label, color = if (day == "Сегодня") colors.accentText else colors.textPrimary, maxLines = 1)
                    Txt(anime.title, AniMixTheme.type.micro, maxLines = 1)
                    Txt("Серия ${anime.episodesAired + 1}", AniMixTheme.type.micro, color = colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(title: String, message: String, onExplore: () -> Unit) {
    MascotMessage(title, message, MascotPose.Welcome, onExplore = onExplore)
}
