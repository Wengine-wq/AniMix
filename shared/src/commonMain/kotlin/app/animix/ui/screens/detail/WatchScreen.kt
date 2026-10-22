package app.animix.ui.screens.detail

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
import app.animix.data.library.isEpisodeWatched
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
import app.animix.ui.components.SelectionList
import app.animix.ui.components.SelectionOption
import app.animix.ui.components.EpisodeCardSurface
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.SelectionPlaceholder
import app.animix.ui.components.Shimmer
import app.animix.ui.components.Txt
import app.animix.ui.components.episodesWord
import app.animix.ui.components.pressScale
import app.animix.ui.components.rememberLoad
import app.animix.ui.components.rim
import app.animix.ui.components.userMessage
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import app.animix.ui.theme.LocalUiPerformance

/** The episode to offer first: the one in progress, else the first unwatched, else the last one. */
internal fun resumeEpisode(episodes: List<Episode>, entry: LibraryEntry?): Episode? {
    if (episodes.isEmpty()) return null
    val positions = entry?.episodePositions.orEmpty()
    episodes.lastOrNull { (positions[it.number] ?: 0) > 0 }?.let { return it }
    return episodes.firstOrNull { entry?.isEpisodeWatched(it.number) != true } ?: episodes.last()
}

/** The same episode in another dubbing, or the closest one it has. */
internal fun closestEpisode(episodes: List<Episode>, number: String?): Episode? {
    if (number == null) return null
    episodes.firstOrNull { it.number == number }?.let { return it }
    val wanted = number.toDoubleOrNull() ?: return episodes.firstOrNull()
    return episodes.minByOrNull { kotlin.math.abs(it.sortKey - wanted) }
}

private fun Episode.isWatched(entry: LibraryEntry?): Boolean = entry?.isEpisodeWatched(number) == true

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
    // Умное соединение: the offered episode is resolved ahead, so «Продолжить» starts at once.
    androidx.compose.runtime.LaunchedEffect(suggested?.iframeUrl, selectedDubbing?.isKodik) {
        val url = suggested?.iframeUrl
        if (url != null && selectedDubbing?.isKodik == true && graph.connection.prefetch) graph.scope.launch { runCatching { graph.kodik.resolve(url) } }
    }
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
                        IconCircle(AniMixIcons.Clock, { navigator.push(Route.WatchFlow(anime, episodeNumber)) }, contentDescription = "Подготовить Watch flow")
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
                Load.Loading -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { SelectionPlaceholder() }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Txt("Серии", AniMixTheme.type.heading, Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    items(15) { Shimmer(Modifier.fillMaxWidth().height(48.dp), AniMixTheme.shapes.medium) }
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
                                Txt("Серии · ${selected.episodes.size}", AniMixTheme.type.heading)
                                downloadMessage?.let { Txt(it, AniMixTheme.type.caption, modifier = Modifier.padding(top = 6.dp)) }
                            }
                        }
                        val saved = downloads.filter { it.anime.key == anime.key && it.dubbingKey == selected.key && it.status == app.animix.data.downloads.DownloadStatus.Completed }.map { it.episode.number }.toSet()
                        items(selected.episodes, key = { "${selected.key}:${it.number}" }) { episode ->
                            EpisodeCell(
                                episode,
                                selected = episode.number == episodeNumber,
                                watched = episode.isWatched(entry),
                                progress = watchProgress(entry, episode),
                                saved = episode.number in saved,
                            ) { episodeNumber = episode.number }
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
                },
                nextLabel = next?.let { "Серия ${it.number}" },
                onNext = next?.let { { onNext(it) } },
                onRefresh = onRetry,
                onDownload = { _ -> onDownload(dubbing, episode) },
                onQualityChange = { quality -> graph.settings.update { it.copy(preferredQuality = quality) } },
                fullscreen = fullscreen,
                onFullscreenChange = { fullscreen = it },
                onAutoNextChoice = { yes ->
                    graph.settings.update { it.copy(autoNextEpisode = yes, autoNextAsked = true) }
                    if (yes) graph.achievements.settingEnabled("autoNextEpisode")
                },
                onFillScreenChange = { value -> graph.settings.update { it.copy(fillScreen = value) } },
                onPlayback = { update -> graph.library.recordPlayback(anime, episode.number, update.credit,
                    update.durationMs, update.opening, update.ending, update.reachedEnd,
                    app.animix.data.library.PlaybackContext(dubbing.studio, offline = false, platform = app.animix.core.Platform.name)) },
                onSkip = graph.library::recordSkip,
                maxStartQuality = graph.connection.playbackCap(),
                onApproachingEnd = {
                    // Умное соединение: resolve the next episode now, so autoplay starts without waiting.
                    if (graph.connection.prefetch && next != null) graph.scope.launch { runCatching { graph.kodik.resolve(next.iframeUrl) } }
                },
            )
        }
      }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).rim(AniMixTheme.shapes.large, accent = false)) {
        if (!LocalUiPerformance.current.batterySaver) RemoteImage(anime.posterUrl, Modifier.fillMaxSize().blur(30.dp), shape = RectangleShape)
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
                    app.animix.ui.components.LoadingIndicator(Modifier.size(30.dp), color = colors.accent)
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

/** The dubbing choice as a calm list: studio, player and how many episodes it has. */
@Composable
private fun DubbingList(dubbings: List<Dubbing>, selected: Dubbing?, onSelect: (Dubbing) -> Unit) {
    if (dubbings.isEmpty()) {
        Txt("Серий пока нет", AniMixTheme.type.caption)
        return
    }
    SelectionList("Озвучка", dubbings.map { SelectionOption(it.key, it.studio, "${it.player} · ${it.episodes.size} ${episodesWord(it.episodes.size)}") },
        selected?.key, AniMixIcons.Mic) { key -> dubbings.firstOrNull { it.key == key }?.let(onSelect) }
}

/** An episode number; watched ones are dimmed with a check, started ones show how far they got. */
@Composable
private fun EpisodeCell(episode: Episode, selected: Boolean, watched: Boolean, progress: Float?, saved: Boolean, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    EpisodeCardSurface(selected, onClick, Modifier.height(48.dp)) {
        Txt(
            episode.number,
            AniMixTheme.type.bodyStrong,
            modifier = Modifier.align(Alignment.Center),
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
        if (progress != null && !watched) {
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp).padding(bottom = 6.dp).fillMaxWidth().height(2.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent))
            }
        }
    }
}

/** How far into [episode] the viewer got, 0..1; null when it was not started. Unknown lengths show a short line. */
internal fun watchProgress(entry: LibraryEntry?, episode: Episode): Float? {
    val position = entry?.episodePositions?.get(episode.number)?.takeIf { it > 0 } ?: return null
    val duration = episode.durationSeconds?.takeIf { it > 0 } ?: return 0.25f
    return (position / 1000f / duration).coerceIn(0.05f, 1f)
}
