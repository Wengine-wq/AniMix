package app.animix.ui.screens.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.model.Anime
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.AnimeCardPlaceholder
import app.animix.ui.components.Chip
import app.animix.ui.components.IconCircle
import app.animix.ui.components.MessageState
import app.animix.ui.components.Txt
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val kindFilters = listOf(
    "Все" to emptyList(),
    "Сериалы" to listOf("tv"),
    "Фильмы" to listOf("movie"),
    "OVA" to listOf("ova"),
    "ONA" to listOf("ona"),
    "Спешлы" to listOf("special"),
)

/**
 * The catalog grid. Used for the Catalog tab, for "see all" lists from Home
 * (with a [preset]) and for search ([searchMode]).
 */
@Composable
fun CatalogScreen(
    graph: AppGraph,
    navigator: Navigator,
    contentPadding: PaddingValues,
    title: String = "Каталог",
    preset: CatalogQuery = CatalogQuery(),
    searchMode: Boolean = false,
    showBack: Boolean = false,
    initialSearch: String = "",
) {
    var kinds by remember(preset) { mutableStateOf(preset.kinds) }
    var order by remember(preset) { mutableStateOf(preset.order) }
    var search by rememberSaveable { mutableStateOf(initialSearch) }
    val query = preset.copy(kinds = kinds, order = order, search = search.trim().ifEmpty { null }, page = 1)
    val paging = remember(query) { CatalogPaging(graph, query) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()

    LaunchedEffect(paging) {
        if (searchMode && query.search == null) return@LaunchedEffect
        delay(if (query.search != null) 350 else 0) // let typing settle before searching
        paging.loadNext()
    }
    LaunchedEffect(grid, paging) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= grid.layoutInfo.totalItemsCount - 8) scope.launch { paging.loadNext() }
            }
    }

    val direction = LocalLayoutDirection.current
    LazyVerticalGrid(
        state = grid,
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
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showBack) {
                        IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                        Spacer(Modifier.width(12.dp))
                    }
                    Txt(if (searchMode) "Поиск" else title, AniMixTheme.type.title, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                SearchField(search, onChange = { search = it }, autoFocus = searchMode && initialSearch.isEmpty())
                if (!searchMode) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(kindFilters) { (label, value) ->
                            Chip(label, selected = kinds == value, onClick = { kinds = value })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CatalogOrder.entries.toList()) { item ->
                            Chip(item.label, selected = order == item, onClick = { order = item }, icon = if (order == item) AniMixIcons.Filter else null)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        items(paging.items, key = { it.key }) { anime ->
            AnimeCard(anime, onClick = { navigator.push(Route.Detail(anime)) })
        }

        when {
            searchMode && query.search == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState(
                    "Что будем искать?",
                    message = "Название на русском, английском или японском",
                    icon = AniMixIcons.Search,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
            }
            paging.error != null && paging.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState(
                    "Не удалось загрузить",
                    message = paging.error?.userMessage(),
                    icon = AniMixIcons.Refresh,
                    actionLabel = "Повторить",
                    onAction = { scope.launch { paging.loadNext() } },
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
            }
            paging.loading && paging.items.isEmpty() -> items(12) { AnimeCardPlaceholder() }
            paging.endReached && paging.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState(
                    "Ничего не нашлось",
                    message = "Попробуйте изменить запрос",
                    icon = AniMixIcons.Search,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, autoFocus: Boolean) {
    val colors = AniMixTheme.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(AniMixTheme.shapes.pill)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, AniMixTheme.shapes.pill)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AniMixIcons.Search, null, Modifier.size(18.dp), tint = colors.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt("Поиск аниме", AniMixTheme.type.body, color = colors.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = AniMixTheme.type.body,
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AniMixIcons.Close, null,
                Modifier.size(22.dp).clip(AniMixTheme.shapes.pill).clickable { onChange("") }.padding(2.dp),
                tint = colors.textSecondary,
            )
        }
    }
}

/** Page-by-page loading of a catalog query. */
private class CatalogPaging(private val graph: AppGraph, private val query: CatalogQuery) {
    val items = mutableStateListOf<Anime>()
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var page = 1

    suspend fun loadNext() {
        if (loading || endReached) return
        loading = true
        error = null
        try {
            val result = graph.anime.catalog(query.copy(page = page))
            val known = items.mapTo(HashSet()) { it.key }
            items += result.filter { it.key !in known }
            if (result.size < query.limit) endReached = true
            page++
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            error = e
        } finally {
            loading = false
        }
    }
}
