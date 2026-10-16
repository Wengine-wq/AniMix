package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.Chip
import app.animix.ui.components.IconCircle
import app.animix.ui.components.MessageState
import app.animix.ui.components.Txt
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
    val status: ListStatus? = if (chosen) ListStatus.entries.firstOrNull { it.name == selectedName } else categories.first()
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
            }
        },
        empty = {
            if (status == null) EmptyLibrary(AniMixIcons.Heart, "В избранном пусто", "Откройте аниме и нажмите «Списки»")
            else EmptyLibrary(AniMixIcons.Bookmark, "В списке «${status.label}» пусто", "Откройте аниме и нажмите «Списки»")
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
                AnimeCard(entry.anime, onClick = { navigator.push(Route.Detail(entry.anime)) })
            }
        }
    }
}

@Composable
private fun EmptyLibrary(icon: ImageVector, title: String, message: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        MessageState(title, message = message, icon = icon)
    }
}
