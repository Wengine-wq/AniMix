package app.animix.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import app.animix.AppGraph
import app.animix.Ambient
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import app.animix.ui.components.PageIndicator
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.LocalUiPerformance
import kotlinx.coroutines.launch
import app.animix.ui.components.accentGlow
import app.animix.ui.components.highlight
import app.animix.ui.components.rim
import app.animix.data.library.ContinueItem
import app.animix.data.library.continueWatching
import app.animix.data.library.tasteGenres
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.model.Anime
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.AnimeCardPlaceholder
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Load
import app.animix.ui.components.MessageState
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.SectionHeader
import app.animix.ui.components.Shimmer
import app.animix.ui.components.Chip
import app.animix.ui.components.Txt
import app.animix.ui.components.formatScore
import app.animix.ui.components.metaLine
import app.animix.ui.components.rememberLoad
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class HomeData(val ongoing: List<Anime>, val popular: List<Anime>, val top: List<Anime>)

internal fun carouselStartPage(count: Int): Int = if (count <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }
internal fun carouselItem(page: Int, count: Int): Int = if (count <= 1) 0 else ((page % count) + count) % count

@Composable
fun HomeScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val load = rememberLoad(Unit) { force ->
        coroutineScope {
            val ongoing = async { graph.shikimori.ongoing(force) }
            val popular = async { graph.shikimori.popular(force) }
            val top = async { graph.shikimori.topRated(force) }
            HomeData(ongoing.await(), popular.await(), top.await())
        }
    }
    val open: (Anime) -> Unit = { navigator.push(Route.Detail(it)) }
    var heroImage by remember { mutableStateOf<String?>(null) }
    Ambient(heroImage)
    val library by graph.library.state.collectAsState()
    val continueItems = remember(library) { library.continueWatching() }
    // Recomputed only when the taste changes, not on every saved position.
    val taste = remember(library) { library.tasteGenres().take(3).joinToString { it.first } }
    val picks = rememberLoad(taste) { if (taste.isEmpty()) emptyList() else graph.recommendations.forLibrary(graph.library.state.value) }
    val homeState = load.value
    val recommended = (picks.value as? Load.Ready)?.value.orEmpty()

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { HomeTopBar(onSearch = { navigator.push(Route.Search()) }) }
        when (val state = homeState) {
            Load.Loading -> item { HomePlaceholder() }
            is Load.Failed -> item {
                MessageState(
                    title = "Не удалось загрузить",
                    message = state.error.userMessage(),
                    icon = AniMixIcons.Refresh,
                    actionLabel = "Повторить",
                    onAction = load::refresh,
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                )
            }
            is Load.Ready -> {
                val data = state.value
                item { HeroCarousel(data.ongoing.take(5), open, onShown = { heroImage = it.posterUrl }) }
                item {
                    QuickFilters(
                        onOpen = { query, title -> navigator.push(Route.Catalog(query, title)) },
                    )
                }
                item {
                    ContinueShelf(continueItems, onOpen = { item ->
                        // A downloaded episode opens offline: it starts at once and works without a network.
                        val saved = graph.downloads.state.value.firstOrNull { d -> d.anime.key == item.entry.anime.key && d.episode.number == item.episode && d.status == app.animix.data.downloads.DownloadStatus.Completed }
                        navigator.push(if (saved != null) Route.Offline(saved.id) else Route.Watch(item.entry.anime, episode = item.episode))
                    }) { navigator.push(Route.History) }
                }
                recommended.takeIf { it.isNotEmpty() }?.let { recommended ->
                    item { Shelf("Подобрано для вас", recommended, open, onMore = null) }
                }
                item { Shelf("Сейчас на экранах", data.ongoing.drop(5), open) {
                    navigator.push(Route.Catalog(CatalogQuery(status = "ongoing"), "Сейчас на экранах"))
                } }
                item { Shelf("Популярное", data.popular, open) {
                    navigator.push(Route.Catalog(CatalogQuery(kinds = listOf("tv", "movie")), "Популярное"))
                } }
                item { Shelf("Лучшее по рейтингу", data.top, open) {
                    navigator.push(Route.Catalog(CatalogQuery(order = CatalogOrder.Ranked), "Лучшее по рейтингу"))
                } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun AniMixWordmark(modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    BasicText(
        buildAnnotatedString {
            append("Ani")
            withStyle(SpanStyle(color = colors.accent)) { append("Mix") }
        },
        style = AniMixTheme.type.title,
        modifier = modifier,
    )
}

@Composable
private fun HomeTopBar(onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AniMixWordmark(Modifier.weight(1f))
        IconCircle(AniMixIcons.Search, onSearch)
    }
}

@Composable
private fun HeroCarousel(items: List<Anime>, onOpen: (Anime) -> Unit, onShown: (Anime) -> Unit) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(initialPage = carouselStartPage(items.size)) { if (items.size > 1) Int.MAX_VALUE else 1 }
    val performance = LocalUiPerformance.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hoverInteraction = remember { MutableInteractionSource() }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val timer = remember { Animatable(0f) }
    var selectionRevision by remember { mutableStateOf(0) }
    val paused = dragged || pressed || hovered || pager.isScrollInProgress
    LaunchedEffect(pager.currentPage, items) { onShown(items[carouselItem(pager.currentPage, items.size)]) }
    LaunchedEffect(pager.settledPage, paused, selectionRevision, items.size, performance.animations) {
        timer.snapTo(0f)
        if (!performance.animations || paused || items.size < 2) return@LaunchedEffect
        timer.animateTo(1f, tween(6_000, easing = LinearEasing))
        // The scroll has its own job: changes to isScrollInProgress reset the timer, not the scroll.
        scope.launch { pager.animateScrollToPage(pager.currentPage + 1,
            animationSpec = tween(AniMixMotion.Page, easing = FastOutSlowInEasing)) }
    }
    Column(Modifier.hoverable(hoverInteraction)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
        ) { page ->
            // Neighbouring banners sit slightly smaller and dimmer, so the current one leads.
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).let { if (it < 0) -it else it }.coerceAtMost(1f)
            HeroCard(
                items[carouselItem(page, items.size)],
                onClick = { onOpen(items[carouselItem(page, items.size)]) },
                interaction = interaction,
                modifier = Modifier.graphicsLayer {
                    val scale = if (performance.animations) 1f - 0.05f * offset else 1f
                    scaleX = scale
                    scaleY = scale
                    alpha = if (performance.animations) 1f - 0.35f * offset else 1f
                },
            )
        }
        PageIndicator(items.size, carouselItem(pager.currentPage, items.size) + pager.currentPageOffsetFraction,
            onSelect = { index ->
                selectionRevision++
                val target = pager.currentPage + index - carouselItem(pager.currentPage, items.size)
                scope.launch { if (performance.animations) pager.animateScrollToPage(target,
                    animationSpec = tween(AniMixMotion.Page, easing = FastOutSlowInEasing)) else pager.scrollToPage(target) }
            }, modifier = Modifier.padding(top = 2.dp),
            autoProgress = timer.value.takeIf { performance.animations && !paused })
    }
}

/** The same banner on every platform: blurred backdrop, poster on the right, title and play on the left. */
@Composable
private fun HeroCard(anime: Anime, onClick: () -> Unit, modifier: Modifier = Modifier, interaction: MutableInteractionSource) {
    val colors = AniMixTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth > 700.dp
        val height = if (wide) 320.dp else 230.dp
        val inset = if (wide) 24.dp else 16.dp
        Box(
            Modifier.fillMaxWidth().height(height).clip(AniMixTheme.shapes.large).clickable(interaction, indication = null, onClick = onClick),
        ) {
            if (!LocalUiPerformance.current.batterySaver) RemoteImage(anime.posterUrl, Modifier.fillMaxSize().blur(36.dp), shape = AniMixTheme.shapes.large)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Color(0x99000000), 0.6f to Color(0x4D000000), 1f to Color(0x1A000000)),
                ),
            )
            Row(Modifier.fillMaxSize().padding(inset), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (wide) 8.dp else 2.dp, end = 12.dp)) {
                    Txt("Сейчас на экранах", AniMixTheme.type.micro, color = colors.gradientStart)
                    Spacer(Modifier.weight(1f))
                    Txt(anime.title, if (wide) AniMixTheme.type.display else AniMixTheme.type.heading, color = Color.White, maxLines = if (wide) 2 else 3)
                    Spacer(Modifier.height(6.dp))
                    val meta = listOfNotNull(anime.metaLine(), anime.score?.let { "★ ${formatScore(it)}" }).joinToString(" · ")
                    Txt(meta, AniMixTheme.type.caption, color = Color(0xCCFFFFFF), maxLines = 1)
                    Spacer(Modifier.height(if (wide) 18.dp else 12.dp))
                    Box(
                        Modifier.size(if (wide) 52.dp else 44.dp).accentGlow(CircleShape).clip(CircleShape)
                            .background(colors.accentGradient).highlight(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AniMixIcons.Play, null, Modifier.size(if (wide) 22.dp else 18.dp), tint = colors.onAccent)
                    }
                }
                RemoteImage(
                    anime.posterUrl,
                    Modifier.fillMaxHeight().aspectRatio(0.7f).rim(AniMixTheme.shapes.medium, accent = false),
                    shape = AniMixTheme.shapes.medium,
                )
            }
        }
    }
}

@Composable
private fun QuickFilters(onOpen: (CatalogQuery, String) -> Unit) {
    val presets = listOf(
        Triple("Онгоинги", CatalogQuery(status = "ongoing"), AniMixIcons.Clock),
        Triple("Новинки", CatalogQuery(order = CatalogOrder.AiredOn, status = "released"), AniMixIcons.Star),
        Triple("Фильмы", CatalogQuery(kinds = listOf("movie")), AniMixIcons.Play),
        Triple("Анонсы", CatalogQuery(status = "anons"), AniMixIcons.Bell),
    )
    LazyRow(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(presets) { (label, query, icon) ->
            Chip(label, selected = false, onClick = { onOpen(query, label) }, icon = icon)
        }
    }
}

/** «Продолжить просмотр»: wide cards with the episode to open and how far the title is watched. */
@Composable
private fun ContinueShelf(items: List<ContinueItem>, onOpen: (ContinueItem) -> Unit, onMore: () -> Unit) {
    if (items.isEmpty()) return
    val colors = AniMixTheme.colors
    Column(Modifier.padding(bottom = 32.dp)) {
        SectionHeader("Продолжить просмотр", Modifier.padding(horizontal = 20.dp), action = "История", onAction = onMore)
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.entry.anime.key }) { item ->
                Row(
                    Modifier.width(300.dp).height(112.dp).clip(AniMixTheme.shapes.large).background(colors.surface)
                        .rim(AniMixTheme.shapes.large, accent = false).clickable { onOpen(item) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteImage(item.entry.anime.posterUrl, Modifier.fillMaxHeight().aspectRatio(0.7f), shape = AniMixTheme.shapes.medium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(item.entry.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Txt(if (item.episodeStarted) "Серия ${item.episode} · продолжить" else "Серия ${item.episode} · дальше по списку", AniMixTheme.type.micro, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                            Box(Modifier.fillMaxWidth(item.titleProgress.coerceAtLeast(0.03f)).fillMaxHeight().background(colors.accentGradient))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                        Icon(AniMixIcons.Play, null, Modifier.size(15.dp), tint = colors.onAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, items: List<Anime>, onOpen: (Anime) -> Unit, onMore: (() -> Unit)?) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 32.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = 20.dp), action = if (onMore != null) "Все" else null, onAction = onMore)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.key }) { anime ->
                AnimeCard(anime, onClick = { onOpen(anime) }, modifier = Modifier.width(132.dp))
            }
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Shimmer(Modifier.fillMaxWidth().height(220.dp), AniMixTheme.shapes.large)
        Spacer(Modifier.height(24.dp))
        repeat(2) {
            Shimmer(Modifier.width(160.dp).height(18.dp), AniMixTheme.shapes.small)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { AnimeCardPlaceholder(Modifier.width(132.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
