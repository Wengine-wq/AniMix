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
import app.animix.Ambient
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
import app.animix.ui.components.Expandable
import app.animix.ui.components.RichTextView
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
import kotlinx.coroutines.launch
import app.animix.ui.screens.downloads.DownloadSheet

@Composable
fun DetailScreen(graph: AppGraph, navigator: Navigator, opened: Anime, contentPadding: PaddingValues) {
    val detail = rememberLoad(opened.key) { force -> graph.anime.detail(opened, force) }
    val ready = (detail.value as? Load.Ready)?.value
    // A deep link only knows the id; show the full entry once it has loaded.
    val anime = ready?.anime ?: opened

    val similar = rememberLoad(anime.key to anime.title.isNotEmpty()) {
        if (anime.title.isEmpty()) awaitCancellation()
        graph.anime.similar(anime)
    }
    Ambient(anime.posterUrl)
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val entry = library.entry(anime)
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    // Умное соединение: warm the episode list, so «Смотреть» opens without waiting.
    androidx.compose.runtime.LaunchedEffect(anime.key, anime.title.isNotEmpty()) {
        if (anime.title.isNotEmpty() && graph.connection.prefetch) graph.scope.launch { runCatching { graph.yummy.watchSource(anime) } }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    val actions: @Composable () -> Unit = {
        WatchActions(
            entry = entry,
            onWatch = { navigator.push(Route.Watch(anime)) },
            onLists = { sheet = DetailSheet.Lists },
            onDownload = { sheet = DetailSheet.Download },
            onRate = { sheet = DetailSheet.Rate },
            notice = notice,
        )
    }
    when (sheet) {
        DetailSheet.Lists -> ListsSheet(
            entry = entry,
            favoritesLast = settings.favoritesLast,
            onFavorite = { graph.library.toggleFavorite(anime) },
            onStatus = { graph.library.setStatus(anime, it) },
            onDismiss = { sheet = null },
        )
        DetailSheet.Rate -> RateSheet(entry?.score, onScore = { graph.library.setScore(anime, it); sheet = null }, onDismiss = { sheet = null })
        DetailSheet.Download -> DownloadSheet(graph, anime, onQueued = { notice = it }, onDismiss = { sheet = null })
        null -> Unit
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
                MobileHero(anime, ready)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Genres(anime)
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

private enum class DetailSheet { Lists, Rate, Download }

/**
 * The phone hero: a wide still (or the poster) under a soft scrim, with the poster
 * and title laid over its lower part, like the desktop header.
 */
@Composable
private fun MobileHero(anime: Anime, detail: AnimeDetail?) {
    val colors = AniMixTheme.colors
    val still = detail?.screenshots?.firstOrNull()
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        RemoteImage(still ?: anime.posterUrl, Modifier.fillMaxSize().then(if (still == null) Modifier.blur(24.dp) else Modifier), shape = androidx.compose.ui.graphics.RectangleShape)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.45f),
                    0.3f to Color.Transparent,
                    0.62f to colors.background.copy(alpha = 0.78f),
                    1f to colors.background,
                ),
            ),
        )
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            RemoteImage(anime.posterUrl, Modifier.width(112.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.medium)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) { TitleBlock(anime, detail, genres = false, compact = true) }
        }
    }
}

@Composable
private fun WideHeader(anime: Anime, detail: AnimeDetail?, actions: @Composable () -> Unit) {
    // The blurred mood comes from the shared ambient backdrop, so the header stays open and light.
    Box(Modifier.fillMaxWidth().height(460.dp)) {
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

@Composable
private fun TitleBlock(anime: Anime, detail: AnimeDetail?, genres: Boolean = true, compact: Boolean = false) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    Txt(anime.title, if (compact) type.title else type.display, maxLines = 3)
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
    if (genres) {
        Spacer(Modifier.height(12.dp))
        Genres(anime)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Genres(anime: Anime) {
    val colors = AniMixTheme.colors
    if (anime.genres.isEmpty()) return
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        anime.genres.take(6).forEach { genre ->
            Txt(
                genre,
                AniMixTheme.type.micro,
                color = colors.textPrimary,
                modifier = Modifier.clip(AniMixTheme.shapes.pill).background(colors.surfaceRaised)
                    .border(1.dp, colors.outline, AniMixTheme.shapes.pill).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun WatchActions(
    entry: LibraryEntry?,
    onWatch: () -> Unit,
    onLists: () -> Unit,
    onDownload: () -> Unit,
    onRate: () -> Unit,
    notice: String?,
) {
    val colors = AniMixTheme.colors
    val listed = entry?.favorite == true || entry?.status != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Смотреть", onWatch, icon = AniMixIcons.Play, modifier = Modifier.weight(1f, fill = false).widthIn(min = 200.dp))
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Bookmark, onClick = onLists, size = 50.dp, active = listed)
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Download, onClick = onDownload, size = 50.dp)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Bubble(
            text = entry?.score?.let { "Ваша оценка · $it" } ?: "Оценить тайтл",
            icon = AniMixIcons.Star,
            active = entry?.score != null,
            onClick = onRate,
        )
        val lists = listOfNotNull("Избранное".takeIf { entry?.favorite == true }, entry?.status?.label)
        if (lists.isNotEmpty()) Bubble(lists.joinToString(" · "), AniMixIcons.Bookmark, active = true, onClick = onLists)
    }
    if (notice != null) Txt(notice, AniMixTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
}

/** A small rounded pill for personal state on the anime page. */
@Composable
private fun Bubble(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.clip(AniMixTheme.shapes.pill).background(if (active) colors.accentSoft else colors.chip)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.4f) else colors.outline, AniMixTheme.shapes.pill)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (active) colors.accent else colors.star)
        Spacer(Modifier.width(6.dp))
        Txt(text, AniMixTheme.type.label, color = if (active) colors.accentText else colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Description(detail: AnimeDetail) {
    val type = AniMixTheme.type
    if (!detail.description.isBlank) {
        Expandable(collapsedHeight = 112.dp) { RichTextView(detail.description) }
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

