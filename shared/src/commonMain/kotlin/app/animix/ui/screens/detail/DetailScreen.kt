package app.animix.ui.screens.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.awaitCancellation
import app.animix.data.yummy.WatchSourceNotFound
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeSource
import app.animix.model.Dubbing
import app.animix.model.WatchSource
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.Chip
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Load
import app.animix.ui.components.PrimaryButton
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.SectionHeader
import app.animix.ui.components.Shimmer
import app.animix.ui.components.Txt
import app.animix.ui.components.episodesWord
import app.animix.ui.components.formatScore
import app.animix.ui.components.rememberLoad
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

@Composable
fun DetailScreen(graph: AppGraph, navigator: Navigator, opened: Anime, contentPadding: PaddingValues) {
    val detail = rememberLoad(opened.key) { force -> graph.anime.detail(opened, force) }
    val ready = (detail.value as? Load.Ready)?.value
    // A deep link only knows the id; show the full entry once it has loaded.
    val anime = ready?.anime ?: opened
    val source = rememberLoad(anime.key to anime.title.isNotEmpty()) { force ->
        if (anime.title.isEmpty()) awaitCancellation()
        graph.yummy.watchSource(anime, force)
    }

    val similar = rememberLoad(anime.key to anime.title.isNotEmpty()) {
        if (anime.title.isEmpty()) awaitCancellation()
        graph.anime.similar(anime)
    }
    val library by graph.library.state.collectAsState()
    val entry = library.entry(anime)
    val actions: @Composable () -> Unit = {
        WatchActions(
            entry = entry,
            onWatch = {},
            onFavorite = { graph.library.toggleFavorite(anime) },
            onStatus = { graph.library.setStatus(anime, it) },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 32.dp),
        ) {
            if (wide) {
                WideHeader(anime, ready, actions)
            } else {
                Backdrop(anime.posterUrl, height = 380.dp)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    TitleBlock(anime, ready)
                    Spacer(Modifier.height(18.dp))
                    actions()
                }
            }
            Column(
                Modifier.padding(horizontal = if (wide) 40.dp else 20.dp).widthIn(max = 1100.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                when (val state = detail.value) {
                    Load.Loading -> DescriptionPlaceholder()
                    is Load.Failed -> Txt(state.error.userMessage(), AniMixTheme.type.caption)
                    is Load.Ready -> Description(state.value)
                }
                ready?.screenshots?.takeIf { it.isNotEmpty() }?.let { shots ->
                    Spacer(Modifier.height(28.dp))
                    SectionHeader("Кадры")
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shots) { url ->
                            RemoteImage(url, Modifier.width(if (wide) 300.dp else 240.dp).aspectRatio(16f / 9f), shape = AniMixTheme.shapes.medium)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                WatchSection(source.value, onRetry = source::refresh)
                ready?.related?.takeIf { it.isNotEmpty() }?.let { related ->
                    AnimeShelf("Связанное", related, navigator)
                }
                (similar.value as? Load.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { items ->
                    AnimeShelf("Похожее", items, navigator)
                }
                Spacer(Modifier.height(28.dp))
                ReviewsPreview(graph, anime, ready, onOpenAll = { navigator.push(Route.Reviews(anime)) })
            }
        }
        IconCircle(
            AniMixIcons.Back,
            onClick = { navigator.back() },
            glass = true,
            modifier = Modifier.statusBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun AnimeShelf(title: String, items: List<Anime>, navigator: Navigator) {
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.key }) { item ->
            AnimeCard(item, onClick = { navigator.push(Route.Detail(item)) }, modifier = Modifier.width(120.dp))
        }
    }
}

@Composable
private fun Backdrop(url: String?, height: androidx.compose.ui.unit.Dp) {
    val colors = AniMixTheme.colors
    Box(Modifier.fillMaxWidth().height(height)) {
        RemoteImage(url, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to colors.background),
            ),
        )
    }
}

@Composable
private fun WideHeader(anime: Anime, detail: AnimeDetail?, actions: @Composable () -> Unit) {
    val colors = AniMixTheme.colors
    Box(Modifier.fillMaxWidth().height(460.dp)) {
        RemoteImage(
            anime.posterUrl,
            Modifier.fillMaxSize().blur(40.dp),
            shape = androidx.compose.ui.graphics.RectangleShape,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to colors.background.copy(alpha = 0.55f), 1f to colors.background),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RemoteImage(anime.posterUrl, Modifier.width(240.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.widthIn(max = 640.dp).padding(bottom = 8.dp)) {
                TitleBlock(anime, detail)
                Spacer(Modifier.height(20.dp))
                actions()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleBlock(anime: Anime, detail: AnimeDetail?) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    Txt(anime.title, type.display, maxLines = 3)
    if (anime.originalTitle != anime.title) {
        Spacer(Modifier.height(4.dp))
        Txt(anime.originalTitle, type.caption, maxLines = 1)
    }
    if (anime.source == AnimeSource.Yummy) {
        Spacer(Modifier.height(8.dp))
        Txt(
            "Нет на Shikimori · из каталога YummyAnime",
            type.micro,
            color = colors.accent,
            modifier = Modifier.clip(AniMixTheme.shapes.pill).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    val meta = buildList {
        add(anime.kind.label)
        anime.year?.let { add(it.toString()) }
        anime.availableEpisodes.takeIf { it > 0 }?.let { add("$it ${episodesWord(it)}") }
        detail?.durationMinutes?.let { add("$it мин") }
        detail?.rating?.let { add(it) }
    }.joinToString(" · ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        anime.score?.let {
            Icon(AniMixIcons.Star, null, Modifier.size(16.dp), tint = colors.star)
            Spacer(Modifier.width(4.dp))
            Txt(formatScore(it), type.bodyStrong)
            Spacer(Modifier.width(10.dp))
        }
        Txt(meta, type.caption, maxLines = 2)
    }
    if (anime.genres.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            anime.genres.take(6).forEach { genre ->
                Txt(
                    genre,
                    type.micro,
                    color = colors.textPrimary,
                    modifier = Modifier.clip(AniMixTheme.shapes.pill).background(colors.surfaceRaised)
                        .border(1.dp, colors.outline, AniMixTheme.shapes.pill).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun WatchActions(
    entry: LibraryEntry?,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onStatus: (ListStatus?) -> Unit,
) {
    val favorite = entry?.favorite == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Смотреть", onWatch, icon = AniMixIcons.Play, modifier = Modifier.weight(1f, fill = false).widthIn(min = 200.dp))
        Spacer(Modifier.width(10.dp))
        IconCircle(if (favorite) AniMixIcons.HeartFilled else AniMixIcons.Heart, onClick = onFavorite, size = 50.dp, active = favorite)
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Download, onClick = {}, size = 50.dp)
    }
    Spacer(Modifier.height(14.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ListStatus.entries.toList()) { status ->
            val selected = entry?.status == status
            Chip(
                status.label,
                selected = selected,
                // Tapping the current status again takes the anime off the list.
                onClick = { onStatus(if (selected) null else status) },
                icon = if (selected) AniMixIcons.Check else null,
            )
        }
    }
}

@Composable
private fun Description(detail: AnimeDetail) {
    val type = AniMixTheme.type
    var expanded by remember { mutableStateOf(false) }
    if (detail.description.isNotBlank()) {
        Column(Modifier.animateContentSize().clickable { expanded = !expanded }) {
            Txt(detail.description, type.body, color = AniMixTheme.colors.textSecondary, maxLines = if (expanded) Int.MAX_VALUE else 5)
            Spacer(Modifier.height(6.dp))
            Txt(if (expanded) "Свернуть" else "Подробнее", type.label, color = AniMixTheme.colors.accent)
        }
    }
    if (detail.studios.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Txt("Студия: ${detail.studios.joinToString()}", type.caption)
    }
}

@Composable
private fun DescriptionPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(4) { Shimmer(Modifier.fillMaxWidth(if (it == 3) 0.6f else 1f).height(12.dp), AniMixTheme.shapes.small) }
    }
}

@Composable
private fun WatchSection(state: Load<WatchSource>, onRetry: () -> Unit) {
    val colors = AniMixTheme.colors
    SectionHeader("Озвучки и серии")
    Spacer(Modifier.height(12.dp))
    when (state) {
        Load.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { Shimmer(Modifier.width(120.dp).height(36.dp), AniMixTheme.shapes.pill) }
        }
        is Load.Failed -> {
            val text = if (state.error is WatchSourceNotFound) "Пока нет на YummyAnime" else state.error.userMessage()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(text, AniMixTheme.type.caption, modifier = Modifier.weight(1f, fill = false))
                if (state.error !is WatchSourceNotFound) {
                    Spacer(Modifier.width(10.dp))
                    Txt("Повторить", AniMixTheme.type.label, color = colors.accent, modifier = Modifier.clickable(onClick = onRetry))
                }
            }
        }
        is Load.Ready -> DubbingPicker(state.value.dubbings)
    }
}

@Composable
private fun DubbingPicker(dubbings: List<Dubbing>) {
    if (dubbings.isEmpty()) {
        Txt("Серий пока нет", AniMixTheme.type.caption)
        return
    }
    val colors = AniMixTheme.colors
    var selected by remember(dubbings) { mutableStateOf(dubbings.first()) }
    // When one studio is served by several players, name the player to tell them apart.
    val sharedStudios = dubbings.groupingBy { it.studio }.eachCount().filterValues { it > 1 }.keys
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 20.dp)) {
        items(dubbings, key = { it.key }) { dubbing ->
            Chip(
                buildString {
                    append(dubbing.studio)
                    if (dubbing.studio in sharedStudios) append(" (${dubbing.player})")
                    append(" · ${dubbing.episodes.size}")
                },
                selected = dubbing.key == selected.key,
                onClick = { selected = dubbing },
                icon = AniMixIcons.Mic,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Txt("Плеер: ${selected.player}", AniMixTheme.type.micro)
    Spacer(Modifier.height(14.dp))
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.episodes.forEach { episode ->
            Box(
                Modifier.size(width = 56.dp, height = 44.dp).clip(AniMixTheme.shapes.medium)
                    .background(colors.surfaceRaised).border(1.dp, colors.outline, AniMixTheme.shapes.medium)
                    .clickable { },
                contentAlignment = Alignment.Center,
            ) {
                Txt(episode.number, AniMixTheme.type.bodyStrong)
            }
        }
    }
}
