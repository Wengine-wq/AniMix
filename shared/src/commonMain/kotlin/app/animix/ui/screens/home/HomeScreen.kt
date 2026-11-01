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
import androidx.compose.foundation.layout.widthIn
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
import app.animix.ui.components.pressScale
import app.animix.ui.components.rim
import app.animix.data.library.ContinueItem
import app.animix.data.library.continueWatching
import app.animix.data.library.tasteGenres
import app.animix.data.library.reviewAccess
import app.animix.ui.components.ProgressLine
import app.animix.ui.components.PrimaryButton
import app.animix.ui.components.SecondaryButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.animix.data.shikimori.CatalogOrder
import app.animix.data.shikimori.CatalogQuery
import app.animix.model.Anime
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.AnimeShelfPlaceholder
import app.animix.ui.components.SkeletonGroup
import app.animix.ui.components.SkeletonText
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



/** Bullet lines of the first list in GitHub release notes ("- …" or "* …"), without markdown emphasis. */
internal fun releaseChanges(notes: String): List<String> {
    val lines = notes.lines().map { it.trim() }
    val start = lines.indexOfFirst { it.startsWith("- ") || it.startsWith("* ") }.takeIf { it >= 0 } ?: return emptyList()
    return lines.drop(start).takeWhile { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).replace("**", "").replace("`", "").trim() }.filter { it.isNotEmpty() }
}

internal fun carouselStartPage(count: Int): Int = if (count <= 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }
internal fun carouselItem(page: Int, count: Int): Int = if (count <= 1) 0 else ((page % count) + count) % count

@Composable
fun HomeScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val ongoing = rememberLoad(Unit) { force -> graph.shikimori.ongoing(force) }
    val popular = rememberLoad(Unit) { force -> graph.shikimori.popular(force) }
    val top = rememberLoad(Unit) { force -> graph.shikimori.topRated(force) }
    val open: (Anime) -> Unit = { navigator.push(Route.Detail(it)) }
    var heroImage by remember { mutableStateOf<String?>(null) }
    Ambient(heroImage)
    val library by graph.library.state.collectAsState()
    val continueItems = remember(library) { library.continueWatching() }
    // Recomputed only when the taste changes, not on every saved position.
    val settings by graph.settings.state.collectAsState()
    val taste = remember(library) { library.tasteGenres().take(3).joinToString { it.first } }
    val picksOn = settings.experimentalPicks && taste.isNotEmpty()
    val picks = rememberLoad(taste to picksOn) { if (!picksOn) emptyList() else graph.recommendations.forLibrary(graph.library.state.value) }
    // Read before building lazy items: each request updates only its own section.
    val ongoingState = ongoing.value
    val popularState = popular.value
    val topState = top.value
    val picksState = picks.value

    // Seen «Итоги года» become the first quick chip; until then they get their own banner under the chips.
    val review = remember(library) { library.reviewAccess(app.animix.data.library.dayToDate(app.animix.core.localDayIndex())) }
    val reviewSeen = review.open && review.year in library.reviewedYears

    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") { HomeTopBar(onSearch = { navigator.push(Route.Search()) }) }
        item(key = "hero") {
            when (val state = ongoingState) {
                Load.Loading -> HomeHeroPlaceholder()
                is Load.Failed -> MessageState("Не удалось загрузить онгоинги", message = state.error.userMessage(),
                    icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = ongoing::refresh)
                is Load.Ready -> HeroCarousel(state.value.take(5), open, onShown = { heroImage = it.posterUrl })
            }
        }
        item(key = "filters") {
            QuickFilters(onOpen = { query, title -> navigator.push(Route.Catalog(query, title)) },
                review = review.year.takeIf { reviewSeen }, onReview = { navigator.push(Route.YearInReview(review.year)) })
        }
        if (!reviewSeen) item(key = "year-review") {
            YearReviewCard(library, review) { year -> navigator.push(Route.YearInReview(year)) }
        }
        item(key = "continue") {
            ContinueShelf(continueItems, onOpen = { item ->
                val saved = graph.downloads.state.value.firstOrNull { d -> d.anime.key == item.entry.anime.key &&
                    d.episode.number == item.episode && d.status == app.animix.data.downloads.DownloadStatus.Completed }
                navigator.push(if (saved != null) Route.Offline(saved.id) else Route.Watch(item.entry.anime, episode = item.episode))
            }) { navigator.push(Route.History) }
        }
        if (picksOn) item(key = "recommendations") {
            LoadingShelf("Подобрано для вас", picksState, open, picks::refresh)
        }
        item(key = "ongoing") {
            // The request error is already shown by the hero; don't repeat it on the same screen.
            if (ongoingState !is Load.Failed) LoadingShelf("Сейчас на экранах",
                if (ongoingState is Load.Ready) Load.Ready(ongoingState.value.drop(5)) else ongoingState,
                open, ongoing::refresh) {
                navigator.push(Route.Catalog(CatalogQuery(status = "ongoing"), "Сейчас на экранах"))
            }
        }
        item(key = "popular") {
            LoadingShelf("Популярное", popularState, open, popular::refresh) {
                navigator.push(Route.Catalog(CatalogQuery(kinds = listOf("tv", "movie")), "Популярное"))
            }
        }
        item(key = "top") {
            LoadingShelf("Лучшее по рейтингу", topState, open, top::refresh) {
                navigator.push(Route.Catalog(CatalogQuery(order = CatalogOrder.Ranked), "Лучшее по рейтингу"))
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LoadingShelf(title: String, state: Load<List<Anime>>, onOpen: (Anime) -> Unit,
    onRetry: () -> Unit, onMore: (() -> Unit)? = null) {
    when (state) {
        Load.Loading -> AnimeShelfPlaceholder(title, Modifier.padding(bottom = 32.dp), showMore = onMore != null)
        is Load.Ready -> Shelf(title, state.value, onOpen, onMore)
        is Load.Failed -> Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            SectionHeader(title)
            MessageState("Раздел не загрузился", message = state.error.userMessage(), icon = AniMixIcons.Refresh,
                actionLabel = "Повторить", onAction = onRetry)
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
private fun QuickFilters(onOpen: (CatalogQuery, String) -> Unit, review: Int? = null, onReview: () -> Unit = {}) {
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
        // Already seen: the year stays one tap away, first in the row and in the accent, without a banner.
        if (review != null) item(key = "review") { Chip("Итоги $review", selected = true, onClick = onReview, icon = AniMixIcons.Star) }
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

/** Matches the real hero at both breakpoints, including the pager's reserved height. */
@Composable
internal fun HomeHeroPlaceholder() {
    SkeletonGroup("Загружается главный баннер") {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                val wide = maxWidth > 700.dp
                val height = if (wide) 320.dp else 230.dp
                val inset = if (wide) 24.dp else 16.dp
                Row(Modifier.fillMaxWidth().height(height).clip(AniMixTheme.shapes.large)
                    .background(AniMixTheme.colors.surface).padding(inset), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (wide) 8.dp else 2.dp, end = 12.dp)) {
                        SkeletonText(AniMixTheme.type.micro, fraction = 0.8f)
                        Spacer(Modifier.weight(1f))
                        SkeletonText(if (wide) AniMixTheme.type.display else AniMixTheme.type.heading, lines = 2)
                        Spacer(Modifier.height(6.dp))
                        SkeletonText(AniMixTheme.type.caption, fraction = 0.65f)
                        Spacer(Modifier.height(if (wide) 18.dp else 12.dp))
                        Shimmer(Modifier.size(if (wide) 52.dp else 44.dp), CircleShape)
                    }
                    Shimmer(Modifier.fillMaxHeight().aspectRatio(0.7f), AniMixTheme.shapes.medium)
                }
            }
            Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index ->
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        Shimmer(Modifier.width(if (index == 0) 22.dp else 6.dp).height(6.dp), AniMixTheme.shapes.pill)
                    }
                }
            }
        }
    }
}

/**
 * A newer release from GitHub, as a dialog over the app once per launch: what changed, «Обновить» and
 * «Позже». «Позже» hides it until the next launch; meanwhile the update waits in «Настройки».
 */
@Composable
fun UpdateDialog(graph: AppGraph) {
    val state by graph.updates.state.collectAsState()
    var later by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { graph.updates.check() }
    val update = state.available ?: return
    if (later == update.version && !state.downloading && state.message == null) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!state.downloading) later = update.version }) {
        Column(Modifier.widthIn(max = 420.dp).clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surfaceRaised)
            .rim(AniMixTheme.shapes.large, accent = false).padding(20.dp)) {
            UpdateDetails(graph)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Обновить", graph.updates::install, Modifier.weight(1f), icon = AniMixIcons.Download, enabled = !state.downloading)
                SecondaryButton("Позже", { later = update.version }, Modifier.weight(1f), enabled = !state.downloading)
            }
        }
    }
}

/** The update's name, what changed (three lines, the rest on request), download progress and errors. */
@Composable
fun UpdateDetails(graph: AppGraph) {
    val colors = AniMixTheme.colors
    val state by graph.updates.state.collectAsState()
    val update = state.available ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(AniMixIcons.Download, null, Modifier.size(20.dp), tint = colors.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("Доступна версия ${update.label}", AniMixTheme.type.subheading)
            Txt(update.title, AniMixTheme.type.caption, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
        }
    }
    val changes = remember(update.notes) { releaseChanges(update.notes) }
    if (changes.isNotEmpty()) {
        var expanded by remember(update.version) { mutableStateOf(false) }
        Spacer(Modifier.height(16.dp))
        Txt("Что изменилось", AniMixTheme.type.micro, color = colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        (if (expanded) changes else changes.take(3)).forEach { change ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Icon(AniMixIcons.Check, null, Modifier.padding(top = 2.dp).size(14.dp), tint = colors.accent)
                Spacer(Modifier.width(10.dp))
                Txt(change, AniMixTheme.type.caption, color = colors.textPrimary)
            }
        }
        if (changes.size > 3) Txt(if (expanded) "Свернуть" else "Ещё ${changes.size - 3}", AniMixTheme.type.label, color = colors.accent,
            modifier = Modifier.padding(top = 4.dp).clip(AniMixTheme.shapes.pill).clickable { expanded = !expanded }.padding(vertical = 6.dp))
    }
    if (state.downloading) {
        Spacer(Modifier.height(12.dp))
        ProgressLine(state.progress)
        Txt("Скачиваем · ${(state.progress * 100).toInt()} %", AniMixTheme.type.micro, modifier = Modifier.padding(top = 6.dp))
    }
    state.message?.let { Txt(it, AniMixTheme.type.caption, modifier = Modifier.padding(top = 10.dp)) }
}
/**
 * «Итоги года» under the quick chips, at the same place in every state: a quiet card with the way to
 * open them early until they open, then a pink banner in the hero's geometry until the last slide is
 * seen. After that they live on as the first quick chip (see [QuickFilters]).
 */
@Composable
private fun YearReviewCard(library: app.animix.data.library.LibraryData, access: app.animix.data.library.ReviewAccess, onOpen: (Int) -> Unit) {
    val colors = AniMixTheme.colors
    if (!access.open) {
        Row(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).fillMaxWidth().clip(AniMixTheme.shapes.large)
                .background(colors.surface).rim(AniMixTheme.shapes.large, accent = false).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(AniMixIcons.Star, null, Modifier.size(20.dp), tint = colors.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt("Итоги ${access.year} · с ${access.opensOn.day} декабря", AniMixTheme.type.bodyStrong)
                Txt("Досрочно — после ${access.titlesNeeded} тайтлов, досмотренных в AniMix", AniMixTheme.type.caption,
                    modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(access.titlesWatched.toFloat() / access.titlesNeeded, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Txt("${access.titlesWatched} из ${access.titlesNeeded}", AniMixTheme.type.micro)
                }
            }
        }
        return
    }
    val posters = remember(library, access.year) {
        library.entries.values.sortedByDescending { entry ->
            entry.viewing.values.count { v -> v.completedAt?.let { app.animix.data.library.dayToDate(app.animix.core.localDayIndex(it)).year == access.year } == true }
        }.take(3).map { it.anime.posterUrl }
    }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        val wide = maxWidth > 700.dp
        Row(
            Modifier.fillMaxWidth().height(if (wide) 220.dp else 168.dp)
                .accentGlow(AniMixTheme.shapes.large)
                .pressScale(interaction)
                .clip(AniMixTheme.shapes.large)
                .background(colors.accentGradient)
                .highlight(AniMixTheme.shapes.large)
                .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button) { onOpen(access.year) }
                .padding(horizontal = if (wide) 28.dp else 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Txt("Новое · только раз в году", AniMixTheme.type.micro, color = colors.onAccent.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                Txt("Итоги ${access.year}", if (wide) AniMixTheme.type.display else AniMixTheme.type.title, color = colors.onAccent, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(if (access.early) "Открыты досрочно — ваш год в AniMix готов" else "Ваш год в AniMix готов",
                    AniMixTheme.type.caption, color = colors.onAccent.copy(alpha = 0.85f), maxLines = 2)
                Spacer(Modifier.height(if (wide) 16.dp else 12.dp))
                Row(Modifier.clip(AniMixTheme.shapes.pill).background(colors.onAccent.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AniMixIcons.Play, null, Modifier.size(14.dp), tint = colors.onAccent)
                    Spacer(Modifier.width(6.dp))
                    Txt("Смотреть", AniMixTheme.type.label, color = colors.onAccent)
                }
            }
            if (posters.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                val poster = if (wide) 96.dp else 66.dp
                Box(Modifier.width(poster * 2f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    posters.forEachIndexed { i, url ->
                        RemoteImage(url, Modifier.width(poster).aspectRatio(0.7f).graphicsLayer {
                            rotationZ = (i - (posters.size - 1) / 2f) * 10f
                            translationX = (i - (posters.size - 1) / 2f) * (poster * 0.42f).toPx()
                        }.rim(AniMixTheme.shapes.small, accent = false), shape = AniMixTheme.shapes.small)
                    }
                }
            }
        }
    }
}