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
import androidx.compose.runtime.collectAsState
import app.animix.model.Anime
import app.animix.model.AnimeDetail
import app.animix.model.AnimeSource
import app.animix.ui.components.AnimeCard
import app.animix.ui.components.Expandable
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
        if (anime.title.isBlank()) emptyList() else graph.anime.similar(anime)
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
    val actions: @Composable () -> Unit = {
        if (anime.title.isBlank()) {
            PrimaryButton("Смотреть", {}, icon = AniMixIcons.Play, enabled = false, modifier = Modifier.fillMaxWidth())
        } else WatchActions(
            entry = entry,
            onWatch = { navigator.push(Route.Watch(anime)) },
            onLists = { sheet = DetailSheet.Lists },
            onDownload = { navigator.push(Route.Downloads(anime, add = true)) },
            onSaved = { navigator.push(Route.Downloads(anime)) },
            onFlow = { navigator.push(Route.WatchFlow(anime)) },
            downloaded = downloads.count { it.anime.key == anime.key && it.status == app.animix.data.downloads.DownloadStatus.Completed },
            queued = downloads.count { it.anime.key == anime.key },
            onFavorite = { graph.library.toggleFavorite(anime) },
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
        null -> Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().background(AniMixTheme.colors.background).verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 32.dp),
        ) {
            if (wide) {
                WideHeader(anime, ready, contentPadding, actions)
            } else {
                MobileHero(anime, ready, contentPadding)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Genres(anime)
                    Spacer(Modifier.height(20.dp))
                    actions()
                }
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

private enum class DetailSheet { Lists, Rate }

/**
 * The phone hero: a wide still (or the poster) under a soft scrim, with the poster
 * and title laid over its lower part, like the desktop header.
 */
@Composable
private fun MobileHero(anime: Anime, detail: AnimeDetail?, padding: PaddingValues) {
    Box(Modifier.fillMaxWidth()) {
        HeroBackdrop(anime, detail, Modifier.matchParentSize())
        // Content determines the height: long titles and larger system fonts cannot escape the scrim.
        Column(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding() + 152.dp, bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                RemoteImage(anime.posterUrl, Modifier.width(108.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.medium)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(bottom = 2.dp)) { TitleBlock(anime, detail, genres = false, compact = true) }
            }
        }
    }
}

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
private fun WideHeader(anime: Anime, detail: AnimeDetail?, padding: PaddingValues, actions: @Composable () -> Unit) {
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
                TitleBlock(anime, detail)
                Spacer(Modifier.height(20.dp))
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) { actions() }
            }
        }
    }
}

@Composable
private fun TitleBlock(anime: Anime, detail: AnimeDetail?, genres: Boolean = true, compact: Boolean = false) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    Txt(anime.title.ifBlank { "Аниме" }, if (compact) type.title else type.display, maxLines = 3)
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
    onSaved: () -> Unit,
    onFlow: () -> Unit,
    onFavorite: () -> Unit,
    onRate: () -> Unit,
    downloaded: Int,
    queued: Int,
    notice: String?,
) {
    val colors = AniMixTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Смотреть", onWatch, icon = AniMixIcons.Play, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        IconCircle(if (entry?.favorite == true) AniMixIcons.HeartFilled else AniMixIcons.Heart,
            onClick = onFavorite, size = 50.dp, active = entry?.favorite == true,
            contentDescription = if (entry?.favorite == true) "Убрать из избранного" else "В избранное")
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Download, onClick = onDownload, size = 50.dp, active = queued > 0,
            contentDescription = if (queued > 0) "Загрузки: скачано $downloaded из $queued" else "Скачать серии")
    }
    Spacer(Modifier.height(12.dp))
    val listAction: @Composable (Modifier) -> Unit = { modifier ->
        PersonalAction(
            label = "Мой список",
            value = entry?.status?.label ?: "Добавить",
            icon = entry?.status?.icon ?: AniMixIcons.Bookmark,
            active = entry?.status != null,
            onClick = onLists,
            modifier = modifier,
        )
    }
    val rateAction: @Composable (Modifier) -> Unit = { modifier ->
        PersonalAction(
            label = "Моя оценка",
            value = entry?.score?.let { "$it / 10" } ?: "Оценить",
            icon = if (entry?.score != null) AniMixIcons.Star else AniMixIcons.StarOutline,
            active = entry?.score != null,
            onClick = onRate,
            modifier = modifier,
        )
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp || fontScale > 1.15f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listAction(Modifier.fillMaxWidth())
                rateAction(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listAction(Modifier.weight(1.1f))
                rateAction(Modifier.weight(1f))
            }
        }
    }
    if (queued > 0) {
        // The saved episodes of this title, as calm as «Мой список» and «Моя оценка».
        Spacer(Modifier.height(8.dp))
        PersonalAction(
            label = "Загрузки",
            value = if (downloaded == queued) "Готово $downloaded ${episodesWord(downloaded)} · без интернета" else "Готово $downloaded из $queued",
            icon = AniMixIcons.Download,
            active = downloaded > 0,
            onClick = onSaved,
            trailing = AniMixIcons.ChevronRight,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // Watch flow: a session for the time you have, online or from downloads, starting with this title.
    Spacer(Modifier.height(8.dp))
    PersonalAction(
        label = "Watch flow",
        value = "Серии под ваше время",
        icon = AniMixIcons.Clock,
        active = false,
        onClick = onFlow,
        modifier = Modifier.fillMaxWidth(),
        trailing = AniMixIcons.ChevronRight,
    )
    if (notice != null) Txt(notice, AniMixTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun PersonalAction(label: String, value: String, icon: ImageVector, active: Boolean, onClick: () -> Unit, modifier: Modifier, trailing: ImageVector = AniMixIcons.ChevronDown) {
    val colors = AniMixTheme.colors
    Row(
        modifier.clip(AniMixTheme.shapes.medium).background(colors.surface)
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.22f) else colors.outline, AniMixTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (active) colors.accent else colors.textSecondary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, AniMixTheme.type.micro, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Txt(value, AniMixTheme.type.label, color = if (active) colors.accentText else colors.textPrimary, maxLines = 1)
        }
        Icon(trailing, null, Modifier.size(14.dp), tint = colors.textMuted)
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
    SkeletonGroup("Загружается описание") {
        SkeletonText(AniMixTheme.type.body, lines = 4)
    }
}

