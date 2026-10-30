package app.animix.ui.screens.catalog

import app.animix.core.AppLog

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import app.animix.ui.components.AniMixSheet
import app.animix.ui.components.MascotMessage
import app.animix.ui.components.MascotPose
import app.animix.ui.components.PrimaryButton
import app.animix.ui.components.SecondaryButton

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.animix.ui.components.Load
import app.animix.ui.components.Shimmer
import app.animix.ui.components.rememberLoad
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

/** Shikimori season filter values: recent years one by one, then decades. */
private val seasonFilters: List<Pair<String, String>> = run {
    val year = kotlin.time.Clock.System.now().toString().take(4).toInt()
    (year downTo year - 5).map { it.toString() to it.toString() } +
        listOf("2010-е" to "2010_2019", "2000-е" to "2000_2009", "1990-е" to "199x", "Раньше" to "ancient")
}

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
@OptIn(ExperimentalLayoutApi::class)
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
    var genres by remember(preset) { mutableStateOf(preset.genreIds) }
    var season by remember(preset) { mutableStateOf(preset.season) }
    var moreFilters by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(initialSearch) }
    val query = preset.copy(
        kinds = kinds, order = order, genreIds = genres, season = season,
        search = search.trim().ifEmpty { null }, page = 1,
    )
    val genreList = rememberLoad(Unit) { graph.shikimori.genres() }
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
                if ((!searchMode || query.search != null) && paging.error == null && last >= grid.layoutInfo.totalItemsCount - 8) {
                    scope.launch { paging.loadNext() }
                }
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
                val active = (if (kinds.isNotEmpty()) 1 else 0) + (if (order != preset.order) 1 else 0) + genres.size + (if (season != null) 1 else 0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SearchField(search, onChange = { search = it }, autoFocus = searchMode && initialSearch.isEmpty()) }
                    if (!searchMode) {
                        Spacer(Modifier.width(10.dp))
                        Box {
                            IconCircle(AniMixIcons.Filter, onClick = { moreFilters = true }, size = 48.dp, active = active > 0)
                            if (active > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(AniMixTheme.colors.accent),
                                    contentAlignment = Alignment.Center,
                                ) { Txt("$active", AniMixTheme.type.micro, color = AniMixTheme.colors.onAccent) }
                            }
                        }
                    }
                }
                if (!searchMode && active > 0) {
                    // What is applied right now; a tap on a chip removes that filter.
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kindFilters.firstOrNull { it.second == kinds && kinds.isNotEmpty() }?.let { (label, _) ->
                            item { Chip(label, selected = true, onClick = { kinds = emptyList() }, icon = AniMixIcons.Close) }
                        }
                        if (order != preset.order) item { Chip(order.label, selected = true, onClick = { order = preset.order }, icon = AniMixIcons.Close) }
                        seasonFilters.firstOrNull { it.second == season }?.let { (label, _) ->
                            item { Chip(label, selected = true, onClick = { season = null }, icon = AniMixIcons.Close) }
                        }
                        val names = (genreList.value as? Load.Ready)?.value.orEmpty().associate { it.id to it.title }
                        items(genres) { id -> Chip(names[id] ?: "Жанр", selected = true, onClick = { genres = genres - id }, icon = AniMixIcons.Close) }
                    }
                }
                if (moreFilters) {
                    AniMixSheet(onDismiss = { moreFilters = false }, title = "Фильтры") {
                        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                            FilterTitle("Тип")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                kindFilters.forEach { (label, value) -> Chip(label, selected = kinds == value, onClick = { kinds = value }) }
                            }
                            FilterTitle("Сортировка")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CatalogOrder.entries.forEach { item -> Chip(item.label, selected = order == item, onClick = { order = item }) }
                            }
                            FilterTitle("Год")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Любой", selected = season == null, onClick = { season = null })
                                seasonFilters.forEach { (label, value) ->
                                    Chip(label, selected = season == value, onClick = { season = if (season == value) null else value })
                                }
                            }
                            FilterTitle("Жанры")
                            when (val state = genreList.value) {
                                is Load.Ready -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.value.filterNot { it.isDemographic }.forEach { genre ->
                                        val on = genre.id in genres
                                        Chip(genre.title, selected = on, onClick = { genres = if (on) genres - genre.id else genres + genre.id })
                                    }
                                }
                                is Load.Failed -> Txt(state.error.userMessage(), AniMixTheme.type.caption)
                                Load.Loading -> Shimmer(Modifier.fillMaxWidth().height(36.dp), AniMixTheme.shapes.pill)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Сбросить", onClick = { kinds = emptyList(); order = preset.order; genres = emptyList(); season = null }, modifier = Modifier.weight(1f), icon = AniMixIcons.Refresh)
                            PrimaryButton("Показать", onClick = { moreFilters = false }, modifier = Modifier.weight(1f), icon = AniMixIcons.Check)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        items(paging.items, key = { it.key }) { anime ->
            AnimeCard(anime, onClick = {
                if (query.search != null) graph.achievements.openedFromSearch(anime.key)
                if (query.kinds.isNotEmpty()) graph.achievements.openedFromFilter(anime.key)
                navigator.push(Route.Detail(anime))
            })
        }

        if (paging.loading && paging.items.isNotEmpty()) {
            items(6) { AnimeCardPlaceholder() }
        }
        if (paging.error != null && paging.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            MessageState("Не удалось загрузить следующую страницу", message = paging.error?.userMessage(),
                icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = { scope.launch { paging.loadNext() } })
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
                MascotMessage(
                    "Ничего не нашлось",
                    message = if (query.search != null) "Попробуйте другое название или измените фильтры" else "Попробуйте изменить фильтры",
                    pose = MascotPose.Thinking,
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
            val start = items.size
            var baseCount = 0
            val result = graph.anime.catalog(query.copy(page = page), onBase = { base ->
                baseCount = base.size
                val known = items.mapTo(HashSet()) { it.key }
                items += base.filter { known.add(it.key) }
            })
            // Replace only this page to keep relevance ordering when Yummy completes the search.
            while (items.size > start) items.removeAt(items.lastIndex)
            val known = items.mapTo(HashSet()) { it.key }
            items += result.filter { known.add(it.key) }
            if (baseCount < query.limit) endReached = true
            page++
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            AppLog.failure("Catalog", "next_page", e)
            error = e
        } finally {
            loading = false
        }
    }
}

@Composable
private fun FilterTitle(text: String) {
    Txt(text, AniMixTheme.type.label, color = AniMixTheme.colors.textSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}
