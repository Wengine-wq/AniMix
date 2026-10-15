package app.animix.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { HomeTopBar(onSearch = { navigator.push(Route.Search()) }) }
        when (val state = load.value) {
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
                data.ongoing.firstOrNull()?.let { hero ->
                    item { HeroCard(hero, onClick = { open(hero) }) }
                }
                item {
                    QuickFilters(
                        onOpen = { query, title -> navigator.push(Route.Catalog(query, title)) },
                    )
                }
                item { Shelf("Сейчас на экранах", data.ongoing.drop(1), open) {
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
    val colors = AniMixTheme.colors
    BoxWithConstraints {
    // On wide windows the side rail already shows the wordmark.
    val wide = maxWidth >= 600.dp
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide) Txt("Главная", AniMixTheme.type.title, modifier = Modifier.weight(1f))
        else AniMixWordmark(Modifier.weight(1f))
        IconCircle(AniMixIcons.Search, onSearch)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(colors.accentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AniMixIcons.User, null, Modifier.size(20.dp), tint = colors.onAccent)
        }
    }
    }
}

@Composable
private fun HeroCard(anime: Anime, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val wide = maxWidth > 700.dp
        val height = if (wide) 320.dp else 220.dp
        Box(
            Modifier.fillMaxWidth().height(height).clip(AniMixTheme.shapes.large).clickable(onClick = onClick),
        ) {
            RemoteImage(
                anime.posterUrl,
                if (wide) Modifier.fillMaxSize().blur(36.dp) else Modifier.fillMaxSize(),
                shape = AniMixTheme.shapes.large,
            )
            if (wide) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                RemoteImage(
                    anime.posterUrl,
                    Modifier.align(Alignment.CenterEnd).padding(24.dp).fillMaxHeight().aspectRatio(0.7f),
                    shape = AniMixTheme.shapes.medium,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color(0x33000000),
                        1f to Color(0xE6000000),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(start = if (wide) 32.dp else 18.dp, bottom = if (wide) 28.dp else 18.dp, top = 18.dp, end = if (wide) 230.dp else 18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt("Сейчас на экранах", AniMixTheme.type.micro, color = colors.gradientStart)
                    Spacer(Modifier.height(4.dp))
                    Txt(anime.title, AniMixTheme.type.title, color = Color.White, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(anime.metaLine(), anime.score?.let { "★ ${formatScore(it)}" }).joinToString(" · ")
                    Txt(meta, AniMixTheme.type.caption, color = Color(0xCCFFFFFF))
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(colors.accentGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AniMixIcons.Play, null, Modifier.size(20.dp), tint = colors.onAccent)
                }
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(presets) { (label, query, icon) ->
            Chip(label, selected = false, onClick = { onOpen(query, label) }, icon = icon)
        }
    }
}

@Composable
private fun Shelf(title: String, items: List<Anime>, onOpen: (Anime) -> Unit, onMore: () -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 22.dp)) {
        SectionHeader(title, Modifier.padding(horizontal = 20.dp), action = "Все", onAction = onMore)
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
