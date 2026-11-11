package app.animix.ui.screens.detail

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.Ambient
import app.animix.data.library.LibraryEntry
import app.animix.data.library.ListStatus
import app.animix.data.library.watchedEpisodeCount
import app.animix.data.mascot.mascotCosplayFor
import app.animix.ui.components.MascotCosplayNote
import androidx.compose.runtime.collectAsState
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeSource
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.Expandable
import app.animix.ui.components.focusOutline
import app.animix.ui.components.rim
import app.animix.ui.components.pageHazeSource
import androidx.compose.ui.graphics.graphicsLayer
import app.animix.ui.components.RichTextView
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Load
import app.animix.ui.components.MessageState
import app.animix.ui.components.PrimaryButton
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.SectionHeader
import app.animix.ui.components.AnimeShelfPlaceholder
import app.animix.ui.components.SkeletonGroup
import app.animix.ui.components.SkeletonText
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
import app.animix.ui.theme.LocalUiPerformance
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(graph: AppGraph, navigator: Navigator, opened: Anime, contentPadding: PaddingValues) {
    val detail = rememberLoad(opened.key) { force -> graph.anime.detail(opened, force) }
    val ready = (detail.value as? Load.Ready)?.value
    // A deep link only knows the id; show the full entry once it has loaded.
    val anime = ready?.anime ?: opened

    val similar = rememberLoad(anime.key to anime.title.isNotEmpty()) {
        if (anime.title.isBlank()) emptyList() else graph.anime.similar(anime, graph.library.state.value)
    }
    LaunchedEffect(ready?.anime) { ready?.anime?.let(graph.library::refreshMetadata) }
    Ambient(null)
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val downloads by graph.downloads.state.collectAsState()
    val entry = library.entry(anime)
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    // Умное соединение: warm the episode list, so «Смотреть» opens without waiting.
    androidx.compose.runtime.LaunchedEffect(anime.key, anime.title.isNotEmpty()) {
        if (anime.title.isNotEmpty() && graph.connection.prefetch) graph.scope.launch { runCatching { graph.yummy.watchSource(anime) } }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    // The still opened in full: an index into the stills, null when none is open.
    var stillShown by remember { mutableStateOf<Int?>(null) }
    stillShown?.let { start -> ready?.screenshots?.takeIf { it.isNotEmpty() }?.let { StillViewer(it, start) { stillShown = null } } }
    val openStudio: (app.animix.model.Studio) -> Unit = { studio ->
        navigator.push(Route.Catalog(app.animix.data.shikimori.CatalogQuery(studioId = studio.id), studio.name))
    }
    // «Смотреть» says where it leads: the episode left half-way, or the next one after those watched.
    val resume = remember(entry, anime.availableEpisodes) { resumeTarget(entry, anime.availableEpisodes) }
    val actions: @Composable () -> Unit = {
        if (anime.title.isBlank()) {
            PrimaryButton("Смотреть", {}, icon = AniMixIcons.Play, enabled = false, modifier = Modifier.fillMaxWidth())
        } else WatchActions(
            entry = entry,
            // The button stays short; the episode and the moment are in the status line right under it.
            watchLabel = if (resume?.second == true) "Продолжить" else "Смотреть",
            resumeNote = resume?.let { (episode, started) ->
                val at = entry?.episodePositions?.get(episode)?.takeIf { started && it > 0 }
                if (at != null) "серия $episode с ${at / 60_000}:${(at / 1000 % 60).toString().padStart(2, '0')}" else "дальше серия $episode"
            },
            onWatch = { navigator.push(Route.Watch(anime, episode = resume?.first)) },
            onLists = { sheet = DetailSheet.Lists },
            onDownload = { navigator.push(Route.Downloads(anime, add = true)) },
            onSaved = { navigator.push(Route.Downloads(anime)) },
            onFlow = { navigator.push(Route.WatchFlow(anime)) },
            downloaded = downloads.count { it.anime.key == anime.key && it.status == app.animix.data.downloads.DownloadStatus.Completed },
            queued = downloads.count { it.anime.key == anime.key },
            onFavorite = { graph.library.toggleFavorite(anime) },
            onRate = { sheet = DetailSheet.Rate },
            onCollection = { sheet = DetailSheet.Collection },
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
        DetailSheet.Collection -> app.animix.ui.screens.collections.CollectionSheet(graph, anime) { sheet = null }
        null -> Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        // The page is its own layer, so «Назад» over the picture and the bar are real frosted glass.
        val pageHaze = app.animix.ui.components.rememberPageHaze()
        Column(
            Modifier.fillMaxSize().pageHazeSource(pageHaze).background(AniMixTheme.colors.background).verticalScroll(scroll)
                .padding(bottom = contentPadding.calculateBottomPadding() + 32.dp),
        ) {
            if (wide) {
                WideHeader(anime, ready, contentPadding, actions, openStudio)
            } else {
                MobileHero(anime, ready, contentPadding, actions, openStudio)
            }
            Column(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 1100.dp)
                    .fillMaxWidth().padding(horizontal = if (wide) 40.dp else 20.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                when (val state = detail.value) {
                    Load.Loading -> DescriptionPlaceholder()
                    is Load.Failed -> MessageState("Не удалось загрузить аниме", message = state.error.userMessage(),
                        icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = detail::refresh)
                    is Load.Ready -> Description(state.value)
                }
                mascotCosplayFor(anime)?.takeIf { app.animix.ui.components.MascotShown }?.let { cosplay ->
                    Spacer(Modifier.height(24.dp))
                    MascotCosplayNote(cosplay)
                }
                // Other seasons and films come right after the description: where to go next matters more than stills.
                ready?.related?.takeIf { it.isNotEmpty() }?.let { related ->
                    AnimeShelf("Связанное", related, navigator, labels = ready.relatedLabels)
                }
                // The trailer leads the row of stills: one shelf of what the title looks like, no extra section.
                val trailer = ready?.trailer
                val shots = ready?.screenshots.orEmpty()
                if (trailer != null || shots.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(when { trailer == null -> "Кадры"; shots.isEmpty() -> "Трейлер"; else -> "Трейлер и кадры" })
                    Spacer(Modifier.height(12.dp))
                    val frame = Modifier.width(if (wide) 300.dp else 240.dp).aspectRatio(16f / 9f)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (trailer != null) item(key = "trailer") { TrailerTile(trailer, shots.firstOrNull() ?: anime.posterUrl, frame) }
                        items(shots.size) { index ->
                            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            Box(frame.clip(AniMixTheme.shapes.medium).clickable(interaction, androidx.compose.foundation.LocalIndication.current,
                                role = Role.Button, onClickLabel = "Открыть кадр") { stillShown = index }.focusOutline(interaction, AniMixTheme.shapes.medium)) {
                                RemoteImage(shots[index], Modifier.fillMaxSize(), shape = AniMixTheme.shapes.medium)
                            }
                        }
                    }
                }
                if (anime.title.isNotBlank() && similar.value is Load.Loading) {
                    AnimeShelfPlaceholder("Похожее", Modifier.padding(top = 28.dp), cardWidth = 120.dp, horizontalPadding = 0.dp)
                }
                (similar.value as? Load.Ready)?.value?.takeIf { it.isNotEmpty() }?.let { items ->
                    AnimeShelf("Похожее", items, navigator)
                }
                Spacer(Modifier.height(28.dp))
                if (anime.title.isNotBlank()) {
                    ReviewsPreview(graph, anime, ready, onOpenAll = { navigator.push(Route.Reviews(anime)) })
                }
            }
        }
        // Past the picture the floating «Назад» hands over to a bar with the title: one way back at a time.
        val barShown = { with(density) { (scroll.value - 240.dp.toPx()) / 48.dp.toPx() }.coerceIn(0f, 1f) }
        IconCircle(
            AniMixIcons.Back,
            onClick = { navigator.back() },
            glass = true,
            glassSource = pageHaze,
            modifier = Modifier.statusBarsPadding().padding(16.dp).graphicsLayer { alpha = 1f - barShown() },
        )
        if (anime.title.isNotBlank()) app.animix.ui.components.CollapsedPageBar(anime.title, { navigator.back() }, barShown,
            topInset = contentPadding.calculateTopPadding(), source = pageHaze)
    }
}

@Composable
private fun AnimeShelf(title: String, items: List<Anime>, navigator: Navigator, labels: Map<String, String> = emptyMap()) {
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.key }) { item ->
            // In «Связанное» the line under the title says how it relates: «Продолжение · 2017».
            AnimeCard(item, onClick = { navigator.push(Route.Detail(item)) }, modifier = Modifier.width(120.dp),
                caption = labels[item.key]?.let { label -> listOfNotNull(label, item.year?.toString()).joinToString(" · ") })
        }
    }
}

/**
 * A still in full: the picture fitted to the screen on black, a swipe to the next one, the place in the
 * row («3 / 12») and «Закрыть». «Назад» and a tap on the dark around the picture close it as well.
 */
@Composable
private fun StillViewer(stills: List<String>, start: Int, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start.coerceIn(0, stills.lastIndex)) { stills.size }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xF2000000))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp) { page ->
                Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    RemoteImage(stills[page], Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = AniMixTheme.shapes.medium,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, placeholder = false)
                }
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("${pager.currentPage + 1} / ${stills.size}", AniMixTheme.type.label, color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.weight(1f))
                IconCircle(AniMixIcons.Close, onClose, glass = true, contentDescription = "Закрыть")
            }
        }
    }
}

/**
 * The trailer as the first frame of the stills: its picture under a scrim, a round play button and where
 * it opens. The video plays where it is hosted (the YouTube app or the browser), and the tile says so.
 */
@Composable
private fun TrailerTile(trailer: app.animix.model.Trailer, fallbackImage: String?, modifier: Modifier) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val host = trailerHost(trailer.url)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.clip(AniMixTheme.shapes.medium)
        .clickable(interaction, androidx.compose.foundation.LocalIndication.current, role = Role.Button,
            onClickLabel = "Смотреть трейлер на $host") { runCatching { uri.openUri(trailer.url) } }
        .focusOutline(interaction, AniMixTheme.shapes.medium)) {
        RemoteImage(trailer.imageUrl ?: fallbackImage, Modifier.fillMaxSize(), shape = RectangleShape)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x59000000)))
        Box(Modifier.align(Alignment.Center).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Color(0xCC141418)), contentAlignment = Alignment.Center) {
            Icon(AniMixIcons.Play, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        Txt("Трейлер · $host", AniMixTheme.type.micro, color = androidx.compose.ui.graphics.Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(AniMixTheme.shapes.pill)
                .background(androidx.compose.ui.graphics.Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** The name of the place a trailer opens in: «YouTube», «VK Видео», «Rutube», or the site's address. */
internal fun trailerHost(url: String): String {
    val host = url.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
    return when {
        host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
        host.endsWith("vk.com") || host.endsWith("vkvideo.ru") || host.endsWith("vk.ru") -> "VK Видео"
        host.endsWith("rutube.ru") -> "Rutube"
        else -> host.ifEmpty { "сайте" }
    }
}

private enum class DetailSheet { Lists, Rate, Collection }

/**
 * Where «Смотреть» leads: the episode with a saved position (true: it is continued), else the one after
 * those watched while there are more; null for a title not started or watched to the end.
 */
internal fun resumeTarget(entry: LibraryEntry?, available: Int): Pair<String, Boolean>? {
    entry ?: return null
    entry.episodePositions.filterValues { it > 0 }.keys.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }?.let { return it to true }
    val watched = entry.watchedEpisodeCount
    return if (watched > 0 && watched < available) (watched + 1).toString() to false else null
}

/**
 * The phone hero: a still of the title across the whole width from the top of the screen, light at the
 * top and fading into the page colour, with the poster, the title, the genres and the actions over its
 * lower part. Without a still the blurred poster stands in under a heavier scrim.
 */
@Composable
private fun MobileHero(anime: Anime, detail: AnimeDetail?, padding: PaddingValues, actions: @Composable () -> Unit,
    onStudio: (app.animix.model.Studio) -> Unit) {
    val colors = AniMixTheme.colors
    val top = padding.calculateTopPadding()
    val still = detail?.screenshots?.firstOrNull()
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(top + MobileStillHeight).clip(RectangleShape)) {
            RemoteImage(still ?: anime.posterUrl,
                Modifier.fillMaxSize().then(if (still == null && !LocalUiPerformance.current.batterySaver) Modifier.blur(24.dp) else Modifier),
                shape = RectangleShape)
            // The rule of the anime page: a scrim over any picture that ends in the page's own colour.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to colors.background.copy(alpha = 0.55f),
                0.16f to colors.background.copy(alpha = if (still != null) 0.06f else 0.4f),
                0.42f to colors.background.copy(alpha = if (still != null) 0.12f else 0.5f),
                0.72f to colors.background.copy(alpha = 0.82f),
                1f to colors.background,
            )))
        }
        // Content determines the height: long titles and larger system fonts stay over the fade.
        Column(Modifier.fillMaxWidth().padding(top = top + MobileStillHeight - 104.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                RemoteImage(anime.posterUrl, Modifier.width(96.dp).aspectRatio(0.7f).rim(AniMixTheme.shapes.medium, accent = false),
                    shape = AniMixTheme.shapes.medium)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(bottom = 2.dp)) { TitleBlock(anime, detail, genres = false, compact = true, onStudio = onStudio) }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(10.dp))
                Genres(anime)
                Spacer(Modifier.height(18.dp))
                actions()
            }
        }
    }
}

/** Height of the still on a phone below the status bar; the poster and title sit over its last third. */
private val MobileStillHeight = 300.dp

@Composable
private fun HeroBackdrop(anime: Anime, detail: AnimeDetail?, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val still = detail?.screenshots?.firstOrNull()
    Box(modifier.clip(RectangleShape)) {
        RemoteImage(
            still ?: anime.posterUrl,
            Modifier.fillMaxSize().then(if (still == null && !LocalUiPerformance.current.batterySaver) Modifier.blur(24.dp) else Modifier),
            shape = RectangleShape,
        )
        // Keep a scrim even over the brightest frame; finish at the same opaque surface as the body.
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to colors.background.copy(alpha = if (colors.isDark) 0.38f else 0.48f),
                0.3f to colors.background.copy(alpha = if (colors.isDark) 0.48f else 0.64f),
                0.7f to colors.background.copy(alpha = 0.91f),
                1f to colors.background,
            ),
        ))
    }
}

@Composable
private fun WideHeader(anime: Anime, detail: AnimeDetail?, padding: PaddingValues, actions: @Composable () -> Unit,
    onStudio: (app.animix.model.Studio) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        HeroBackdrop(anime, detail, Modifier.matchParentSize())
        Row(
            Modifier.widthIn(max = 1100.dp).fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = padding.calculateTopPadding() + 88.dp, bottom = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RemoteImage(anime.posterUrl, Modifier.width(220.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                TitleBlock(anime, detail, onStudio = onStudio)
                Spacer(Modifier.height(20.dp))
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) { actions() }
            }
        }
    }
}

@Composable
private fun TitleBlock(anime: Anime, detail: AnimeDetail?, genres: Boolean = true, compact: Boolean = false,
    onStudio: (app.animix.model.Studio) -> Unit = {}) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    // A word never breaks in the middle: if the longest word is wider than the column, the title steps down a size.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val preferred = if (compact) type.title else type.display
        val longest = anime.title.ifBlank { "Аниме" }.split(' ').maxByOrNull { it.length }.orEmpty()
        val fits = measurer.measure(longest, preferred, softWrap = false, maxLines = 1).size.width <= constraints.maxWidth
        Txt(anime.title.ifBlank { "Аниме" }, if (fits) preferred else type.heading, maxLines = 3)
    }
    if (anime.title.isBlank()) return
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        anime.score?.let {
            Icon(AniMixIcons.Star, null, Modifier.size(16.dp), tint = colors.star)
            Spacer(Modifier.width(4.dp))
            Txt(formatScore(it), type.bodyStrong)
            Spacer(Modifier.width(10.dp))
        }
        Txt(listOfNotNull(anime.year?.toString(), anime.kind.label).joinToString(" · "), type.caption, maxLines = 1)
    }
    Spacer(Modifier.height(4.dp))
    Txt(buildList {
        anime.availableEpisodes.takeIf { it > 0 }?.let { add("$it ${episodesWord(it)}") }
        detail?.durationMinutes?.let { add("$it мин") }
        detail?.rating?.let { add(it) }
    }.joinToString(" · "), type.micro, maxLines = 2)
    // Who made it, right with the facts: a studio is a way into everything it made, not a footnote at the bottom.
    val studios = detail?.studioLinks.orEmpty()
    if (studios.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            studios.take(2).forEachIndexed { index, studio ->
                if (index > 0) Txt(" · ", type.micro)
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(Modifier.clip(AniMixTheme.shapes.pill)
                    .clickable(interaction, androidx.compose.foundation.LocalIndication.current, role = Role.Button,
                        onClickLabel = "Все тайтлы студии ${studio.name}") { onStudio(studio) }
                    .focusOutline(interaction, AniMixTheme.shapes.pill).padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(AniMixIcons.Catalog, null, Modifier.size(12.dp), tint = colors.accent)
                    Spacer(Modifier.width(5.dp))
                    Txt(studio.name, type.label, color = colors.accentText, maxLines = 1)
                    Icon(AniMixIcons.ChevronRight, null, Modifier.size(12.dp), tint = colors.accentText)
                }
            }
        }
    } else detail?.studios?.takeIf { it.isNotEmpty() }?.let {
        Spacer(Modifier.height(4.dp))
        Txt(it.joinToString(), type.micro, maxLines = 1)
    }
    // An airing title says when to come back: the date is known to the catalog, so it is shown, not guessed.
    detail?.takeIf { anime.status != app.animix.model.AnimeStatus.Released }
        ?.let { app.animix.core.nextEpisodeLine(anime.episodesAired, it.nextEpisodeAt) }?.let { line ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AniMixIcons.Calendar, null, Modifier.size(14.dp), tint = colors.accent)
                Spacer(Modifier.width(6.dp))
                Txt(line, type.label, color = colors.accentText, maxLines = 2)
            }
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
    watchLabel: String,
    resumeNote: String?,
    onWatch: () -> Unit,
    onLists: () -> Unit,
    onDownload: () -> Unit,
    onSaved: () -> Unit,
    onFlow: () -> Unit,
    onFavorite: () -> Unit,
    onRate: () -> Unit,
    onCollection: (() -> Unit)?,
    downloaded: Int,
    queued: Int,
    notice: String?,
) {
    var more by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(watchLabel, onWatch, icon = AniMixIcons.Play, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        IconCircle(AniMixIcons.Bookmark, onLists, size = 50.dp, active = entry?.status != null || entry?.favorite == true,
            contentDescription = entry?.status?.label ?: "Добавить в список")
        Spacer(Modifier.width(8.dp))
        IconCircle(AniMixIcons.More, { more = true }, size = 50.dp, contentDescription = "Ещё действия")
    }
    if (entry?.status != null || entry?.score != null || downloaded > 0 || resumeNote != null) {
        Spacer(Modifier.height(10.dp))
        Txt(listOfNotNull(entry?.status?.label, resumeNote, entry?.score?.let { "Моя оценка $it / 10" },
            "$downloaded ${episodesWord(downloaded)} скачано".takeIf { downloaded > 0 }).joinToString(" · ").replaceFirstChar { it.uppercase() }, AniMixTheme.type.caption)
    }
    if (more) app.animix.ui.components.AniMixSheet({ more = false }, title = "Действия с тайтлом") {
        app.animix.ui.components.NavRow(entry?.score?.let { "Моя оценка: $it / 10" } ?: "Оценить тайтл", AniMixIcons.StarOutline,
            { more = false; onRate() })
        app.animix.ui.components.NavRow(if (entry?.favorite == true) "Убрать из избранного" else "В избранное", AniMixIcons.Heart,
            { more = false; onFavorite() })
        app.animix.ui.components.NavRow("Загрузки", AniMixIcons.Download,
            { more = false; if (queued > 0) onSaved() else onDownload() },
            trailing = if (queued > 0) "Готово $downloaded из $queued" else "Скачать серии")
        app.animix.ui.components.NavRow("Watch flow", AniMixIcons.Clock, { more = false; onFlow() }, trailing = "Просмотр по времени")
        if (onCollection != null) app.animix.ui.components.NavRow("В подборку", AniMixIcons.Layers, { more = false; onCollection() }, trailing = "Свои подборки")
    }
    if (notice != null) Txt(notice, AniMixTheme.type.caption, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Description(detail: AnimeDetail) {
    if (!detail.description.isBlank) {
        Expandable(collapsedHeight = 112.dp) { RichTextView(detail.description) }
    }
}

@Composable
private fun DescriptionPlaceholder() {
    SkeletonGroup("Загружается описание") {
        SkeletonText(AniMixTheme.type.body, lines = 4)
    }
}

