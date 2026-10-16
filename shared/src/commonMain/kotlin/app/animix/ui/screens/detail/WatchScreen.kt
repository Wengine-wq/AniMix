package app.animix.ui.screens.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.animix.Ambient
import app.animix.AppGraph
import app.animix.data.library.LibraryEntry
import app.animix.data.downloads.rememberDownloadPermissionRequest
import app.animix.ui.screens.downloads.DownloadQualityDialog
import app.animix.data.yummy.WatchSourceNotFound
import app.animix.data.playback.ResolvedStream
import app.animix.ui.player.AniMixPlayer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.animix.model.Anime
import app.animix.model.Dubbing
import app.animix.model.Episode
import app.animix.ui.components.IconCircle
import app.animix.ui.components.Load
import app.animix.ui.components.MessageState
import app.animix.ui.components.Panel
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.Shimmer
import app.animix.ui.components.Txt
import app.animix.ui.components.episodesWord
import app.animix.ui.components.pressScale
import app.animix.ui.components.rememberLoad
import app.animix.ui.components.rim
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.theme.AniMixTheme

/** The episode to offer first: the one in progress, else the first unwatched, else the last one. */
internal fun resumeEpisode(episodes: List<Episode>, entry: LibraryEntry?): Episode? {
    if (episodes.isEmpty()) return null
    val positions = entry?.episodePositions.orEmpty()
    episodes.lastOrNull { (positions[it.number] ?: 0) > 0 }?.let { return it }
    val watched = entry?.episodesWatched ?: 0
    return episodes.firstOrNull { it.sortKey > watched } ?: episodes.last()
}

/** The same episode in another dubbing, or the closest one it has. */
internal fun closestEpisode(episodes: List<Episode>, number: String?): Episode? {
    if (number == null) return null
    episodes.firstOrNull { it.number == number }?.let { return it }
    val wanted = number.toDoubleOrNull() ?: return episodes.firstOrNull()
    return episodes.minByOrNull { kotlin.math.abs(it.sortKey - wanted) }
}

private fun Episode.isWatched(entry: LibraryEntry?): Boolean = entry != null && sortKey <= entry.episodesWatched

/**
 * «Смотреть»: the player area, the dubbing choice and the episode list,
 * kept off the anime page so it stays about the anime.
 */
@Composable
fun WatchScreen(graph: AppGraph, navigator: Navigator, opened: Anime, contentPadding: PaddingValues, openEpisode: String? = null) {
    val detail = rememberLoad(opened.key to opened.title.isEmpty()) {
        if (opened.title.isNotEmpty()) awaitCancellation()
        graph.anime.detail(opened)
    }
    val anime = (detail.value as? Load.Ready)?.value?.anime ?: opened
    Ambient(anime.posterUrl)
    val source = rememberLoad(anime.key to anime.title.isNotEmpty()) { force ->
        if (anime.title.isEmpty()) awaitCancellation()
        graph.yummy.watchSource(anime, force)
    }
    val settings by graph.settings.state.collectAsState()
    val library by graph.library.state.collectAsState()
    val entry = library.entry(anime)
    val scope = rememberCoroutineScope()
    val downloads by graph.downloads.state.collectAsState()
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var selecting by remember(anime.key) { mutableStateOf(false) }
    var picked by remember(anime.key) { mutableStateOf(emptySet<String>()) }
    var askQuality by remember { mutableStateOf<Pair<Dubbing, List<Episode>>?>(null) }
    val askPermission = rememberDownloadPermissionRequest()
    fun startDownload(dubbing: Dubbing, episodes: List<Episode>, quality: String) {
        askPermission()
        scope.launch {
            downloadMessage = try {
                if (episodes.size == dubbing.episodes.size) graph.downloads.enqueueSeason(anime, dubbing, quality)
                else graph.downloads.enqueueEpisodes(anime, dubbing, episodes, quality)
                graph.skips.prefetch(anime, episodes)
                if (episodes.size == 1) "Серия ${episodes.single().number} в загрузках · ${dubbing.studio} · $quality"
                else "${episodes.size} ${episodesWord(episodes.size)} в загрузках · ${dubbing.studio} · $quality"
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { "Не удалось добавить в загрузки" }
        }
    }
    /** The quality is asked once, on the first download, then taken from settings. */
    fun requestDownload(dubbing: Dubbing, episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val quality = settings.downloadQuality
        if (quality == null) askQuality = dubbing to episodes else startDownload(dubbing, episodes, quality)
    }
    askQuality?.let { (dubbing, episodes) ->
        DownloadQualityDialog(
            current = null,
            onPick = { quality ->
                askQuality = null
                graph.settings.update { it.copy(downloadQuality = quality) }
                startDownload(dubbing, episodes, quality)
            },
            onDismiss = { askQuality = null },
        )
    }
    val remembered = settings.dubbingByAnime[anime.key]
    var selectedKey by rememberSaveable(anime.key) { mutableStateOf(remembered) }
    var episodeNumber by rememberSaveable(anime.key) { mutableStateOf(openEpisode) }
    val dubbings = (source.value as? Load.Ready)?.value?.dubbings.orEmpty()
    val selectedDubbing = dubbings.firstOrNull { it.key == selectedKey } ?: dubbings.firstOrNull()
    val selectedEpisode = selectedDubbing?.episodes?.firstOrNull { it.number == episodeNumber }
    val suggested = if (selectedEpisode == null) resumeEpisode(selectedDubbing?.episodes.orEmpty(), entry) else null
    val stream = rememberLoad(selectedDubbing?.key to selectedEpisode?.iframeUrl) { force ->
        if (selectedEpisode == null || !selectedDubbing.isKodik) awaitCancellation()
        graph.kodik.resolve(selectedEpisode.iframeUrl, forceRefresh = force)
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 900.dp) 10 else if (maxWidth >= 600.dp) 8 else 5
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(anime.title, AniMixTheme.type.heading, maxLines = 1)
                            Txt(
                                episodeNumber?.let { number -> listOfNotNull("Серия $number", selectedDubbing?.studio).joinToString(" · ") } ?: "Выберите серию",
                                AniMixTheme.type.caption,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PlayerArea(graph, anime, selectedEpisode, selectedDubbing, stream.value, stream::refresh,
                            next = selectedDubbing?.episodes?.let { episodes ->
                                val index = episodes.indexOfFirst { it.number == episodeNumber }
                                episodes.getOrNull(index + 1)?.takeIf { index >= 0 }
                            },
                            onNext = { next -> episodeNumber = next.number },
                            suggested = suggested,
                            onPlay = { episode -> episodeNumber = episode.number },
                            onDownload = { dubbing, episode -> requestDownload(dubbing, listOf(episode)) },
                            modifier = Modifier.widthIn(max = 860.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            when (val state = source.value) {
                Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { Shimmer(Modifier.fillMaxWidth().height(56.dp), AniMixTheme.shapes.medium) }
                    }
                }
                is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    if (state.error is WatchSourceNotFound) {
                        MessageState("Пока нет на YummyAnime", message = "Серии появятся, когда релиз добавят", icon = AniMixIcons.Clock)
                    } else {
                        MessageState("Не удалось загрузить серии", message = state.error.userMessage(), icon = AniMixIcons.Refresh, actionLabel = "Повторить", onAction = source::refresh)
                    }
                }
                is Load.Ready -> {
                    val dubbings = state.value.dubbings
                    val selected = dubbings.firstOrNull { it.key == selectedKey } ?: dubbings.firstOrNull()
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DubbingList(dubbings, selected) { dubbing ->
                            selectedKey = dubbing.key
                            // Keep watching the same episode; if this dubbing lacks it, take the closest one.
                            episodeNumber = closestEpisode(dubbing.episodes, episodeNumber)?.number
                            graph.settings.update { it.copy(dubbingByAnime = it.dubbingByAnime + (anime.key to dubbing.key)) }
                        }
                    }
                    if (selected != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                                if (!selecting) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Txt("Серии · ${selected.episodes.size}", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
                                        if (selected.isKodik) QuietChip("Скачать", AniMixIcons.Download) { picked = emptySet(); selecting = true }
                                    }
                                } else {
                                    DownloadPicker(
                                        dubbing = selected,
                                        quality = settings.downloadQuality,
                                        picked = picked.size,
                                        onAll = { picked = if (picked.size == selected.episodes.size) emptySet() else selected.episodes.map { it.number }.toSet() },
                                        onCancel = { selecting = false },
                                        onDownload = {
                                            requestDownload(selected, selected.episodes.filter { it.number in picked })
                                            selecting = false
                                        },
                                    )
                                }
                                downloadMessage?.let { Txt(it, AniMixTheme.type.caption, modifier = Modifier.padding(top = 6.dp)) }
                            }
                        }
                        val saved = downloads.filter { it.anime.key == anime.key && it.dubbingKey == selected.key && it.status == app.animix.data.downloads.DownloadStatus.Completed }.map { it.episode.number }.toSet()
                        items(selected.episodes, key = { "${selected.key}:${it.number}" }) { episode ->
                            EpisodeCell(
                                episode,
                                selected = if (selecting) episode.number in picked else episode.number == episodeNumber,
                                watched = episode.isWatched(entry),
                                started = (entry?.episodePositions?.get(episode.number) ?: 0) > 0,
                                saved = episode.number in saved,
                            ) {
                                if (selecting) picked = if (episode.number in picked) picked - episode.number else picked + episode.number
                                else episodeNumber = episode.number
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Provider resolution stays behind the native player and the AniMix controls. */
@Composable
private fun PlayerArea(
    graph: AppGraph,
    anime: Anime,
    episode: Episode?,
    dubbing: Dubbing?,
    stream: Load<ResolvedStream>,
    onRetry: () -> Unit,
    next: Episode?,
    onNext: (Episode) -> Unit,
    suggested: Episode?,
    onPlay: (Episode) -> Unit,
    onDownload: (Dubbing, Episode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AniMixTheme.colors
    val uriHandler = LocalUriHandler.current
    val settings by graph.settings.state.collectAsState()
    val library by graph.library.state.collectAsState()
    // Survives the switch to the next episode, so autoplay stays on the big screen.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (episode != null && dubbing?.isKodik == true && stream is Load.Ready) {
      Column(modifier.fillMaxWidth()) {
        androidx.compose.runtime.key(dubbing.key, episode.number) {
            AniMixPlayer(
                stream.value, episode, settings,
                title = "Серия ${episode.number}",
                subtitle = listOf(anime.title, dubbing.studio).joinToString(" · "),
                skips = { duration -> graph.skips.plan(anime, episode, duration) },
                initialPositionMs = library.entry(anime)?.episodePositions?.get(episode.number) ?: 0L,
                onProgress = { graph.library.saveEpisodePosition(anime, episode.number, it) },
                onCompleted = {
                    graph.library.saveEpisodePosition(anime, episode.number, 0)
                    episode.number.toDoubleOrNull()?.toInt()?.let { number ->
                        graph.library.setEpisodesWatched(anime, maxOf(number, graph.library.state.value.entry(anime)?.episodesWatched ?: 0))
                    }
                },
                nextLabel = next?.let { "Серия ${it.number}" },
                onNext = next?.let { { onNext(it) } },
                onRefresh = onRetry,
                onDownload = { _ -> onDownload(dubbing, episode) },
                onQualityChange = { quality -> graph.settings.update { it.copy(preferredQuality = quality) } },
                fullscreen = fullscreen,
                onFullscreenChange = { fullscreen = it },
            )
        }
      }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).rim(AniMixTheme.shapes.large, accent = false)) {
        RemoteImage(anime.posterUrl, Modifier.fillMaxSize().blur(30.dp), shape = RectangleShape)
        Box(Modifier.fillMaxSize().background(Color(0x80000000)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (episode == null) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(56.dp).pressScale(interaction, pressed = 0.92f).clip(CircleShape).background(colors.accentGradient)
                        .clickable(interaction, indication = null, enabled = suggested != null) { suggested?.let(onPlay) },
                    contentAlignment = Alignment.Center,
                ) { Icon(AniMixIcons.Play, null, Modifier.size(22.dp), tint = colors.onAccent) }
                Spacer(Modifier.height(10.dp))
                val position = suggested?.let { library.entry(anime)?.episodePositions?.get(it.number) } ?: 0L
                Txt(
                    when {
                        suggested == null -> "Выберите озвучку и серию"
                        position > 0 -> "Продолжить серию ${suggested.number} с ${position / 60_000}:${(position / 1000 % 60).toString().padStart(2, '0')}"
                        else -> "Смотреть серию ${suggested.number}"
                    },
                    AniMixTheme.type.caption,
                    color = Color.White,
                )
            } else if (dubbing?.isKodik != true) {
                Txt("Этот источник можно открыть в браузере", AniMixTheme.type.caption, color = Color.White)
                Txt(
                    "Открыть источник",
                    AniMixTheme.type.label,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 8.dp).clip(AniMixTheme.shapes.pill).clickable { uriHandler.openUri(episode.iframeUrl) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else when (stream) {
                Load.Loading -> {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(30.dp), color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Txt("Получаем видео…", AniMixTheme.type.caption, color = Color.White)
                }
                is Load.Failed -> {
                    Txt(stream.error.userMessage(), AniMixTheme.type.caption, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                    Txt(
                        "Повторить",
                        AniMixTheme.type.label,
                        color = colors.accent,
                        modifier = Modifier.padding(top = 8.dp).clip(AniMixTheme.shapes.pill).clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                is Load.Ready -> Unit
            }
        }
    }
}

/** A small outlined chip for secondary actions next to a section title. */
@Composable
private fun QuietChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.clip(AniMixTheme.shapes.pill)
            .then(if (accent && enabled) Modifier.background(colors.accentGradient) else Modifier.background(colors.chip).rim(AniMixTheme.shapes.pill, accent = false))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = when {
            accent && enabled -> colors.onAccent
            !enabled -> colors.textMuted
            else -> colors.textPrimary
        }
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (accent || !enabled) content else colors.accent)
            Spacer(Modifier.width(6.dp))
        }
        Txt(text, AniMixTheme.type.label, color = content, maxLines = 1)
    }
}

/** Picking episodes to download: tap numbers, or take the whole season of the chosen dubbing. */
@Composable
private fun DownloadPicker(
    dubbing: Dubbing,
    quality: String?,
    picked: Int,
    onAll: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(if (picked == 0) "Выберите серии" else "Выбрано: $picked", AniMixTheme.type.heading, maxLines = 1)
                Txt(listOfNotNull(dubbing.studio, quality).joinToString(" · "), AniMixTheme.type.caption, maxLines = 1)
            }
            QuietChip("Отмена", null, onClick = onCancel)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietChip(if (picked == dubbing.episodes.size) "Снять выбор" else "Весь сезон · ${dubbing.episodes.size}", AniMixIcons.Check, onClick = onAll)
            QuietChip(if (picked > 0) "Скачать $picked" else "Скачать", AniMixIcons.Download, accent = true, enabled = picked > 0, onClick = onDownload)
        }
    }
}

/** The dubbing choice as a calm list: studio, player and how many episodes it has. */
@Composable
private fun DubbingList(dubbings: List<Dubbing>, selected: Dubbing?, onSelect: (Dubbing) -> Unit) {
    val colors = AniMixTheme.colors
    if (dubbings.isEmpty()) {
        Txt("Серий пока нет", AniMixTheme.type.caption)
        return
    }
    var open by remember { mutableStateOf(false) }
    val shown = if (open || selected == null) dubbings else listOf(selected)
    Column {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("Озвучка", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        if (dubbings.size > 1) {
            Txt(
                if (open) "Свернуть" else "Сменить · ${dubbings.size}",
                AniMixTheme.type.label,
                color = colors.accent,
                modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    Panel(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        shown.forEachIndexed { index, dubbing ->
            val isSelected = dubbing.key == selected?.key
            val background by animateColorAsState(if (isSelected) colors.accentSoft else Color.Transparent)
            Row(
                Modifier.fillMaxWidth().background(background).clickable {
                    onSelect(dubbing)
                    open = false
                }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AniMixIcons.Mic, null, Modifier.size(18.dp), tint = if (isSelected) colors.accent else colors.textMuted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(dubbing.studio, AniMixTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                    Txt("${dubbing.player} · ${dubbing.episodes.size} ${episodesWord(dubbing.episodes.size)}", AniMixTheme.type.micro, maxLines = 1)
                }
                if (isSelected) Icon(AniMixIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
            }
            if (index < shown.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 46.dp).height(1.dp).background(colors.outline.copy(alpha = 0.5f)))
        }
    }
    }
}

/** An episode number; watched ones are dimmed with a check, started ones carry a thin accent line. */
@Composable
private fun EpisodeCell(episode: Episode, selected: Boolean, watched: Boolean, started: Boolean, saved: Boolean, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(if (selected) colors.accentSoft else colors.chip)
    Box(
        Modifier.height(48.dp).pressScale(interaction).clip(AniMixTheme.shapes.medium).background(background)
            .rim(AniMixTheme.shapes.medium, accent = selected)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            episode.number,
            AniMixTheme.type.bodyStrong,
            color = when {
                selected -> colors.accentText
                watched -> colors.textMuted
                else -> colors.textPrimary
            },
        )
        if (watched && !selected) {
            Icon(AniMixIcons.Check, "Просмотрено", Modifier.align(Alignment.TopEnd).padding(5.dp).size(11.dp), tint = colors.accent)
        }
        if (saved) {
            Icon(AniMixIcons.Download, "Скачано", Modifier.align(Alignment.TopStart).padding(5.dp).size(11.dp), tint = colors.textMuted)
        }
        if (started && !watched) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(16.dp).height(2.dp).clip(AniMixTheme.shapes.pill).background(colors.accent))
        }
    }
}
