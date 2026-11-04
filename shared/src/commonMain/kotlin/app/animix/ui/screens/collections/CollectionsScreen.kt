package app.animix.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.AppJson
import app.animix.data.PickFilters
import app.animix.data.library.ListStatus
import app.animix.data.library.UserCollection
import app.animix.data.library.tasteGenres
import app.animix.model.Anime
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

/**
 * «Подборки» (experimental): «Умный подбор» finds good titles for the chosen filters, «Мои подборки»
 * are named lists beyond the statuses. Both are in the shared poster grid of «Списки».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    var mine by rememberSaveable { mutableStateOf(false) }
    var filtersText by rememberSaveable { mutableStateOf(AppJson.encodeToString(PickFilters.serializer(), PickFilters())) }
    val filters = remember(filtersText) { AppJson.decodeFromString(PickFilters.serializer(), filtersText) }
    fun setFilters(next: PickFilters) { filtersText = AppJson.encodeToString(PickFilters.serializer(), next) }
    // The library changes while browsing (a title added from its page): picks follow without a reload flash.
    val picks = rememberLoad(filtersText to mine) { if (mine) emptyList() else graph.recommendations.withFilters(graph.library.state.value, filters) }
    val taste = remember(library) { library.tasteGenres().take(6).map { it.first } }
    val genres = (taste + CommonGenres).distinct().take(14)
    PosterGrid(padding) {
        header {
            PageHeader("Подборки", { navigator.back() })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Умный подбор", !mine, { mine = false }, icon = AniMixIcons.Sparkle)
                Chip("Мои подборки · ${library.collections.size}", mine, { mine = true }, icon = AniMixIcons.Layers)
            }
        }
        if (!mine) {
            header {
                // The filters fold into one line of what is chosen; open, every genre of the catalog is there.
                var open by rememberSaveable { mutableStateOf(false) }
                var allGenres by rememberSaveable { mutableStateOf(false) }
                val catalogGenres = rememberLoad(allGenres) { if (allGenres) graph.shikimori.genres().filter { !it.isDemographic }.map { it.title } else emptyList() }
                val chosen = listOfNotNull(
                    filters.genres.joinToString(" + ").ifEmpty { null },
                    mapOf("tv" to "Сериалы", "movie" to "Фильмы", "short" to "OVA и спешлы")[filters.kind],
                    mapOf("new" to "С 2020", "2010s" to "2010-е", "classic" to "Классика")[filters.era],
                    mapOf("short" to "До 13 серий", "season" to "14–26 серий", "long" to "Длинные")[filters.length],
                    "Только вышедшие".takeIf { filters.finished },
                )
                Panel {
                    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clickable(interaction, androidx.compose.foundation.LocalIndication.current) { open = !open }
                        .focusOutline(interaction, AniMixTheme.shapes.large).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AniMixIcons.Filter, null, Modifier.size(20.dp), tint = AniMixTheme.colors.textSecondary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Txt("Фильтры", AniMixTheme.type.bodyStrong)
                            Txt(chosen.joinToString(" · ").ifEmpty { "По вашему вкусу: любимые жанры и тайтлы" }, AniMixTheme.type.caption, maxLines = 2)
                        }
                        if (chosen.isNotEmpty()) Chip("Сбросить", false, { setFilters(PickFilters()) })
                        Spacer(Modifier.width(8.dp))
                        Icon(if (open) AniMixIcons.ChevronUp else AniMixIcons.ChevronDown, if (open) "Свернуть фильтры" else "Развернуть фильтры",
                            Modifier.size(18.dp), tint = AniMixTheme.colors.textMuted)
                    }
                }
                if (open) Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FilterGroup(if (filters.genres.size > 1) "Жанры · все выбранные сразу" else "Жанры") {
                        val names = ((catalogGenres.value as? Load.Ready)?.value.orEmpty().let { all -> (genres + all).distinct() }
                            .takeIf { allGenres } ?: genres) + filters.genres.filter { it !in genres }
                        names.distinct().forEach { genre ->
                            Chip(genre, genre in filters.genres, { setFilters(filters.copy(genres = if (genre in filters.genres) filters.genres - genre else filters.genres + genre)) })
                        }
                        Chip(if (allGenres) "Свернуть" else "Все жанры", false, { allGenres = !allGenres },
                            icon = if (allGenres) AniMixIcons.ChevronUp else AniMixIcons.ChevronDown)
                    }
                    FilterGroup("Формат") {
                        listOf(null to "Любой", "tv" to "Сериалы", "movie" to "Фильмы", "short" to "OVA и спешлы").forEach { (key, label) ->
                            Chip(label, filters.kind == key, { setFilters(filters.copy(kind = key)) })
                        }
                    }
                    FilterGroup("Время выхода") {
                        listOf(null to "Любое", "new" to "С 2020", "2010s" to "2010-е", "classic" to "Классика").forEach { (key, label) ->
                            Chip(label, filters.era == key, { setFilters(filters.copy(era = key)) })
                        }
                    }
                    FilterGroup("Длина") {
                        listOf(null to "Любая", "short" to "До 13 серий", "season" to "14–26", "long" to "Длинные").forEach { (key, label) ->
                            Chip(label, filters.length == key, { setFilters(filters.copy(length = key)) })
                        }
                        Chip("Только вышедшие", filters.finished, { setFilters(filters.copy(finished = !filters.finished)) }, icon = AniMixIcons.Check)
                    }
                    Txt(if (filters.genres.isEmpty()) "Без выбранных жанров подбор опирается на ваши любимые жанры и тайтлы. Уже добавленное в списки не предлагается."
                        else "Тайтлы со всеми выбранными жанрами, лучшие по вашему вкусу и оценке. Уже добавленное в списки не предлагается.",
                        AniMixTheme.type.micro)
                }
            }
            when (val state = picks.value) {
                Load.Loading -> items(12) { AnimeCardPlaceholder() }
                is Load.Failed -> header { MessageState("Не удалось подобрать", message = state.error.userMessage(), icon = AniMixIcons.Sparkle,
                    actionLabel = "Повторить", onAction = picks::refresh) }
                is Load.Ready -> if (state.value.isEmpty()) header {
                    MessageState("Ничего не нашлось", message = "Ослабьте фильтры: уберите жанр или выберите любое время выхода.", icon = AniMixIcons.Filter)
                } else items(state.value, key = { it.key }) { anime ->
                    AnimeCard(anime, { navigator.push(Route.Detail(anime)) },
                        caption = listOfNotNull(anime.score?.takeIf { it > 0 }?.let { "★ ${formatScore(it)}" }, anime.year?.toString()).joinToString(" · ").ifEmpty { null })
                }
            }
        } else {
            header { NewCollection { name -> navigator.push(Route.Collection(graph.library.createCollection(name))) } }
            val collections = library.collections.values.sortedByDescending { it.updatedAt }
            if (collections.isEmpty()) header {
                MessageState("Своих подборок пока нет", message = "Создайте подборку и добавляйте тайтлы со страницы аниме: «Ещё» → «В подборку».",
                    icon = AniMixIcons.Layers)
            } else header {
                Panel {
                    collections.forEachIndexed { index, collection ->
                        if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(AniMixTheme.colors.outline))
                        NavRow(collection.name, AniMixIcons.Layers, { navigator.push(Route.Collection(collection.id)) },
                            trailing = "${collection.titles.size} ${app.animix.ui.screens.profile.titlesWord(collection.titles.size)}")
                    }
                }
            }
        }
    }
}

/** One collection: sort, select several and act on them at once, or open a title. */
@Composable
fun CollectionScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val collection = library.collections[id]
    var sort by rememberSaveable { mutableStateOf(Sort.Added) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    if (collection == null) {
        Column(Modifier.padding(padding).padding(20.dp)) {
            PageHeader("Подборка", { navigator.back() })
            MessageState("Подборка удалена", icon = AniMixIcons.Layers)
        }
        return
    }
    val titles = when (sort) {
        Sort.Added -> collection.titles
        Sort.Name -> collection.titles.sortedBy { it.title.lowercase() }
        Sort.Score -> collection.titles.sortedByDescending { it.score ?: 0.0 }
        Sort.Year -> collection.titles.sortedByDescending { it.year ?: 0 }
    }
    val chosen = titles.filter { it.key in selected }
    Box(Modifier.fillMaxSize()) {
        PosterGrid(padding, bottomSpace = if (selecting) 88.dp else 0.dp) {
            header {
                PageHeader(collection.name, { navigator.back() })
                Spacer(Modifier.height(16.dp))
                if (rename != null) {
                    NameField(rename.orEmpty(), { rename = it }, "Название подборки")
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Отмена", { rename = null }, Modifier.weight(1f))
                        PrimaryButton("Сохранить", { graph.library.renameCollection(id, rename.orEmpty()); rename = null }, Modifier.weight(1f),
                            enabled = !rename.isNullOrBlank())
                    }
                    Spacer(Modifier.height(12.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sort.entries.forEach { option -> Chip(option.label, sort == option, { sort = option }) }
                    Chip(if (selecting) "Готово" else "Выбрать", selecting, { selecting = !selecting; selected = emptySet() }, icon = AniMixIcons.Check)
                    Chip("Ещё", false, { sheet = "more" }, icon = AniMixIcons.More)
                }
            }
            if (titles.isEmpty()) header {
                MessageState("Подборка пуста", message = "Добавляйте тайтлы со страницы аниме: «Ещё» → «В подборку».", icon = AniMixIcons.Layers)
            } else items(titles, key = { it.key }) { anime ->
                val isSelected = anime.key in selected
                Box {
                    AnimeCard(anime, {
                        if (selecting) selected = if (isSelected) selected - anime.key else selected + anime.key
                        else navigator.push(Route.Detail(anime))
                    }, caption = library.entry(anime)?.status?.label)
                    if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(if (isSelected) AniMixTheme.colors.accent else AniMixTheme.colors.scrim)
                        .border(1.5.dp, androidx.compose.ui.graphics.Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        if (isSelected) Icon(AniMixIcons.Check, null, Modifier.size(16.dp), tint = AniMixTheme.colors.onAccent)
                    }
                }
            }
        }
        // Bulk actions float above the grid while selecting, worded with the count so nothing is ambiguous.
        if (selecting) Row(Modifier.align(Alignment.BottomCenter).padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)
            .widthIn(max = 640.dp).fillMaxWidth().glass(AniMixTheme.shapes.pill).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Статус", { sheet = "status" }, Modifier.weight(1f), icon = AniMixIcons.Bookmark, enabled = chosen.isNotEmpty())
            SecondaryButton(if (chosen.isEmpty()) "Убрать" else "Убрать · ${chosen.size}", {
                graph.library.removeFromCollection(id, selected); selected = emptySet()
            }, Modifier.weight(1f), icon = AniMixIcons.Close, enabled = chosen.isNotEmpty())
        }
    }
    when (sheet) {
        "status" -> AniMixSheet({ sheet = null }, title = "Статус для ${chosen.size} ${app.animix.ui.screens.profile.titlesWord(chosen.size)}") {
            ListStatus.entries.forEach { status -> SheetOption(status.label, false, { graph.library.setStatusMany(chosen, status); sheet = null; selecting = false; selected = emptySet() }) }
            SheetOption("Убрать из списков", false, { graph.library.setStatusMany(chosen, null); sheet = null; selecting = false; selected = emptySet() }, icon = AniMixIcons.Close)
        }
        "more" -> AniMixSheet({ sheet = null }, title = collection.name) {
            NavRow("Переименовать", AniMixIcons.Edit, { sheet = null; rename = collection.name })
            NavRow("Удалить подборку", AniMixIcons.Trash, { sheet = "delete" })
        }
        "delete" -> AniMixSheet({ sheet = null }, title = "Удалить «${collection.name}»?", subtitle = "Тайтлы останутся в своих списках") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Отмена", { sheet = null }, Modifier.weight(1f))
                PrimaryButton("Удалить", { graph.library.deleteCollection(id); sheet = null; navigator.back() }, Modifier.weight(1f), icon = AniMixIcons.Trash)
            }
        }
    }
}

/** From a title's page: tick the collections it belongs to, or start a new one with it. */
@Composable
fun CollectionSheet(graph: AppGraph, anime: Anime, onDismiss: () -> Unit) {
    val library by graph.library.state.collectAsState()
    AniMixSheet(onDismiss, title = "В подборку", subtitle = anime.title) {
        Column(Modifier.heightIn(max = 420.dp).verticalScrollIfNeeded(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            library.collections.values.sortedByDescending { it.updatedAt }.forEach { collection ->
                SheetOption(collection.name, collection.titles.any { it.key == anime.key }, { graph.library.toggleInCollection(collection.id, anime) },
                    icon = AniMixIcons.Layers, subtitle = "${collection.titles.size} ${app.animix.ui.screens.profile.titlesWord(collection.titles.size)}")
            }
        }
        Spacer(Modifier.height(12.dp))
        NewCollection { name -> graph.library.toggleInCollection(graph.library.createCollection(name), anime) }
    }
}

@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier = verticalScroll(androidx.compose.foundation.rememberScrollState())

/** «Новая подборка»: a button that becomes a name field with «Создать». */
@Composable
private fun NewCollection(onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf<String?>(null) }
    val current = name
    if (current == null) SecondaryButton("Новая подборка", { name = "" }, Modifier.fillMaxWidth(), icon = AniMixIcons.Layers)
    else Column {
        NameField(current, { name = it.take(40) }, "Например, «На выходные»")
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Отмена", { name = null }, Modifier.weight(1f))
            PrimaryButton("Создать", { onCreate(current); name = null }, Modifier.weight(1f), enabled = current.isNotBlank())
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, hint: String) {
    val colors = AniMixTheme.colors
    Box(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).background(colors.chip).rim(AniMixTheme.shapes.medium, accent = false).padding(16.dp)) {
        if (value.isEmpty()) Txt(hint, AniMixTheme.type.body, color = colors.textMuted, maxLines = 1)
        BasicTextField(value, onChange, singleLine = true, textStyle = AniMixTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        Txt(title, AniMixTheme.type.label, color = AniMixTheme.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** The poster grid of «Списки» with full-width rows for headers and states. */
@Composable
private fun PosterGrid(padding: PaddingValues, bottomSpace: androidx.compose.ui.unit.Dp = 0.dp, content: LazyGridScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(direction) + 20.dp, end = padding.calculateEndPadding(direction) + 20.dp,
            top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp + bottomSpace,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

private fun LazyGridScope.header(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { Column { content() } }

private enum class Sort(val label: String) { Added("Добавлены"), Name("По названию"), Score("По оценке"), Year("По году") }

private val CommonGenres = listOf("Экшен", "Комедия", "Романтика", "Драма", "Фэнтези", "Повседневность", "Приключения", "Фантастика", "Детектив", "Спорт", "Психологическое", "Ужасы")
