package app.animix.ui.screens.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.TextButton
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

/**
 * «Смотреть»: the player area, the dubbing choice and the episode list,
 * kept off the anime page so it stays about the anime.
 */
@Composable
fun WatchScreen(graph: AppGraph, navigator: Navigator, opened: Anime, contentPadding: PaddingValues) {
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
    val scope = rememberCoroutineScope()
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    val remembered = settings.dubbingByAnime[anime.key]
    var selectedKey by rememberSaveable(anime.key) { mutableStateOf(remembered) }
    var episodeNumber by rememberSaveable(anime.key) { mutableStateOf<String?>(null) }
    val dubbings = (source.value as? Load.Ready)?.value?.dubbings.orEmpty()
    val selectedDubbing = dubbings.firstOrNull { it.key == selectedKey } ?: dubbings.firstOrNull()
    val selectedEpisode = selectedDubbing?.episodes?.firstOrNull { it.number == episodeNumber }
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
                            Txt(episodeNumber?.let { "Серия $it" } ?: "Выберите серию", AniMixTheme.type.caption)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PlayerArea(graph, anime, selectedEpisode, selectedDubbing, stream.value, stream::refresh,
                            onNext = selectedDubbing?.episodes?.let { episodes ->
                                val index = episodes.indexOfFirst { it.number == episodeNumber }
                                episodes.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { next -> { episodeNumber = next.number } }
                            },
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
                            graph.settings.update { it.copy(dubbingByAnime = it.dubbingByAnime + (anime.key to dubbing.key)) }
                        }
                        if (selected?.isKodik == true) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                listOf("720p", "1080p").forEach { quality ->
                                    TextButton(onClick = { scope.launch {
                                        try {
                                            graph.downloads.enqueueSeason(anime, selected, quality)
                                            downloadMessage = "Сезон добавлен в очередь · ${selected.studio} · $quality"
                                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                        catch (_: Exception) { downloadMessage = "Не удалось добавить сезон в очередь" }
                                    } }) { Txt("Скачать сезон · $quality", AniMixTheme.type.label, color = AniMixTheme.colors.accent) }
                                }
                            }
                            downloadMessage?.let { Txt(it, AniMixTheme.type.caption) }
                        }
                    }
                    if (selected != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Txt(
                                "Серии · ${selected.episodes.size}",
                                AniMixTheme.type.heading,
                                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                            )
                        }
                        items(selected.episodes, key = { "${selected.key}:${it.number}" }) { episode ->
                            EpisodeCell(episode, selected = episode.number == episodeNumber) { episodeNumber = episode.number }
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
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AniMixTheme.colors
    val uriHandler = LocalUriHandler.current
    val settings by graph.settings.state.collectAsState()
    val library by graph.library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var downloadNotice by remember { mutableStateOf<String?>(null) }
    if (episode != null && dubbing?.isKodik == true && stream is Load.Ready) {
      Column(modifier.fillMaxWidth()) {
        androidx.compose.runtime.key(dubbing.key, episode.number) {
            AniMixPlayer(
                stream.value, episode, settings,
                initialPositionMs = library.entry(anime)?.episodePositions?.get(episode.number) ?: 0L,
                onProgress = { graph.library.saveEpisodePosition(anime, episode.number, it) },
                onCompleted = {
                    graph.library.saveEpisodePosition(anime, episode.number, 0)
                    episode.number.toIntOrNull()?.let { number -> graph.library.setEpisodesWatched(anime, maxOf(number, library.entry(anime)?.episodesWatched ?: 0)) }
                },
                onNext = onNext,
                onRefresh = onRetry,
                onDownload = { quality -> scope.launch {
                    try { graph.downloads.enqueueEpisode(anime, dubbing, episode, quality); downloadNotice = "Серия добавлена в загрузки" }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { downloadNotice = "Не удалось добавить серию в очередь" }
                } },
            )
        }
        downloadNotice?.let { Txt(it, AniMixTheme.type.caption) }
      }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).rim(AniMixTheme.shapes.large, accent = false)) {
        RemoteImage(anime.posterUrl, Modifier.fillMaxSize().blur(30.dp), shape = RectangleShape)
        Box(Modifier.fillMaxSize().background(Color(0x80000000)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (episode == null) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(colors.accentGradient),
                    contentAlignment = Alignment.Center,
                ) { Icon(AniMixIcons.Play, null, Modifier.size(22.dp), tint = colors.onAccent) }
                Spacer(Modifier.height(10.dp))
                Txt("Выберите озвучку и серию", AniMixTheme.type.caption, color = Color.White)
            } else if (dubbing?.isKodik != true) {
                Txt("Этот источник можно открыть в браузере", AniMixTheme.type.caption, color = Color.White)
                TextButton(onClick = { uriHandler.openUri(episode.iframeUrl) }) {
                    Txt("Открыть источник", AniMixTheme.type.label, color = colors.accent)
                }
            } else when (stream) {
                Load.Loading -> {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(30.dp), color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Txt("Получаем видео…", AniMixTheme.type.caption, color = Color.White)
                }
                is Load.Failed -> {
                    Txt(stream.error.userMessage(), AniMixTheme.type.caption, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = onRetry) { Txt("Повторить", AniMixTheme.type.label, color = colors.accent) }
                }
                is Load.Ready -> Unit
            }
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
                    Txt(dubbing.studio, AniMixTheme.type.bodyStrong, color = if (isSelected) colors.textPrimary else colors.textPrimary, maxLines = 1)
                    Txt("${dubbing.player} · ${dubbing.episodes.size} ${episodesWord(dubbing.episodes.size)}", AniMixTheme.type.micro, maxLines = 1)
                }
                if (isSelected) Icon(AniMixIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
            }
            if (index < shown.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 46.dp).height(1.dp).background(colors.outline.copy(alpha = 0.5f)))
        }
    }
    }
}

@Composable
private fun EpisodeCell(episode: Episode, selected: Boolean, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val background by animateColorAsState(if (selected) colors.accentSoft else colors.chip)
    Box(
        Modifier.height(48.dp).pressScale(interaction).clip(AniMixTheme.shapes.medium).background(background)
            .rim(AniMixTheme.shapes.medium, accent = selected)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(episode.number, AniMixTheme.type.bodyStrong, color = if (selected) colors.accentText else colors.textPrimary)
    }
}
