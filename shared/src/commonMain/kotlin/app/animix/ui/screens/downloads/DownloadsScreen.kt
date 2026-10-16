package app.animix.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import app.animix.data.downloads.DownloadItem
import app.animix.data.library.LibraryEntry
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.downloads.DownloadStatus
import app.animix.data.playback.ResolvedStream
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.player.AniMixPlayer
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

@Composable fun DownloadsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val downloads by graph.downloads.state.collectAsState()
    val notice by graph.downloads.notice.collectAsState()
    val library by graph.library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block(); error = null } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error = "Не удалось выполнить действие. Проверьте свободное место." }
    } }
    // Titles keep the order they were first queued in; episodes go by number.
    val titles = downloads.groupBy { it.anime.key }.values.map { group -> group.sortedBy { it.episode.sortKey } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp,
        start = 20.dp, end = 20.dp,
    ), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Txt("Загрузки", AniMixTheme.type.title)
            Spacer(Modifier.height(14.dp))
            if (downloads.isNotEmpty()) QueueCard(downloads, onPauseAll = { action { graph.downloads.pauseAll() } }, onResumeAll = { action { graph.downloads.resumeAll() } })
            (error ?: notice)?.let { Txt(it, AniMixTheme.type.caption, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (downloads.isEmpty()) item {
            MessageState("Серии всегда под рукой", message = "Откройте аниме и нажмите «Скачать»: можно выбрать озвучку, серии или весь сезон", icon = AniMixIcons.Download)
        }
        items(titles, key = { it.first().anime.key }) { episodes ->
            val anime = episodes.first().anime
            val busy = episodes.any { it.active || it.status == DownloadStatus.Queued }
            val open = expanded[anime.key] ?: busy
            TitleCard(
                items = episodes,
                entry = library.entry(anime),
                open = open,
                onToggle = { expanded[anime.key] = !open },
                onPlay = { navigator.push(Route.Offline(it.id)) },
                onPause = { item -> action { graph.downloads.pause(item.id) } },
                onResume = { item -> action { graph.downloads.resume(item.id) } },
                onRemove = { item -> action { graph.downloads.remove(item.id) } },
                onRemoveAll = { action { episodes.forEach { graph.downloads.remove(it.id) } } },
            )
        }
    }
}

private fun megabytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
    val tenths = bytes * 10 / (1024L * 1024 * 1024)
    "${tenths / 10},${tenths % 10} ГБ"
} else "${bytes / 1024 / 1024} МБ"

/** A share of the work: parts for an active episode, 1 for a finished one. */
private fun DownloadItem.share(): Float = when {
    status == DownloadStatus.Completed -> 1f
    totalParts > 0 -> (completedParts.toFloat() / totalParts).coerceIn(0f, 1f)
    else -> 0f
}

/** The whole queue at a glance, with the controls that act on all of it. */
@Composable
private fun QueueCard(items: List<DownloadItem>, onPauseAll: () -> Unit, onResumeAll: () -> Unit) {
    val colors = AniMixTheme.colors
    val done = items.count { it.status == DownloadStatus.Completed }
    val running = items.any { it.active || it.status == DownloadStatus.Queued }
    val failed = items.count { it.status == DownloadStatus.Failed }
    Panel {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(items.sumOf { it.share().toDouble() }.toFloat() / items.size, Modifier.size(46.dp), stroke = 4.dp) {
                Txt("$done/${items.size}", AniMixTheme.type.micro)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(
                    when {
                        running -> "Вся очередь · качается"
                        done == items.size -> "Всё скачано"
                        else -> "Вся очередь · на паузе"
                    },
                    AniMixTheme.type.bodyStrong,
                )
                Txt(
                    listOfNotNull(megabytes(items.sumOf { it.downloadedBytes }), "ошибок: $failed".takeIf { failed > 0 }).joinToString(" · "),
                    AniMixTheme.type.caption,
                )
            }
            if (done < items.size) {
                IconCircle(if (running) AniMixIcons.Pause else AniMixIcons.Play, onClick = if (running) onPauseAll else onResumeAll, active = !running)
            }
        }
    }
}

@Composable
private fun TitleCard(
    items: List<DownloadItem>,
    entry: LibraryEntry?,
    open: Boolean,
    onToggle: () -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
    onRemoveAll: () -> Unit,
) {
    val colors = AniMixTheme.colors
    val anime = items.first().anime
    val done = items.count { it.status == DownloadStatus.Completed }
    Panel(Modifier.animateContentSize()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(anime.posterUrl, Modifier.width(54.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                    Txt(
                        "${items.first().studio} · $done из ${items.size} ${episodesWord(items.size)} · ${megabytes(items.sumOf { it.downloadedBytes })}",
                        AniMixTheme.type.micro,
                        maxLines = 1,
                    )
                    if (done < items.size) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                            Box(Modifier.fillMaxWidth(items.sumOf { it.share().toDouble() }.toFloat() / items.size).fillMaxHeight().background(colors.accentGradient))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(if (open) AniMixIcons.ChevronDown else AniMixIcons.ChevronRight, if (open) "Свернуть" else "Развернуть", Modifier.size(18.dp), tint = colors.textMuted)
            }
            if (open) {
                items.forEach { item ->
                    EpisodeRow(item, entry, onPlay, onPause, onResume, onRemove)
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = onRemoveAll).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AniMixIcons.Trash, null, Modifier.size(16.dp), tint = colors.textMuted)
                    Spacer(Modifier.width(10.dp))
                    Txt("Удалить все серии тайтла", AniMixTheme.type.label, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    item: DownloadItem,
    entry: LibraryEntry?,
    onPlay: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
) {
    val colors = AniMixTheme.colors
    val watched = entry != null && item.episode.sortKey <= entry.episodesWatched
    val position = entry?.episodePositions?.get(item.episode.number)
    val ready = item.status == DownloadStatus.Completed
    Row(
        Modifier.fillMaxWidth().clickable(enabled = ready) { onPlay(item) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            when {
                ready -> Box(
                    Modifier.size(34.dp).clip(CircleShape).background(if (watched) colors.chip else colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    if (watched) Icon(AniMixIcons.Check, "Просмотрено", Modifier.size(16.dp), tint = colors.accent)
                    else Icon(AniMixIcons.Play, "Смотреть", Modifier.size(14.dp), tint = colors.accent)
                }
                item.status == DownloadStatus.Failed -> Icon(AniMixIcons.Retry, null, Modifier.size(18.dp), tint = colors.danger)
                else -> ProgressRing(item.share(), Modifier.size(32.dp), stroke = 3.dp, indeterminate = item.status == DownloadStatus.Resolving || (item.active && item.totalParts == 0)) {
                    if (item.status == DownloadStatus.Paused || item.status == DownloadStatus.Queued) {
                        Icon(if (item.status == DownloadStatus.Paused) AniMixIcons.Pause else AniMixIcons.Clock, null, Modifier.size(12.dp), tint = colors.textMuted)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt("Серия ${item.episode.number}", AniMixTheme.type.bodyStrong, color = if (watched) colors.textSecondary else colors.textPrimary)
            val detail = when (item.status) {
                DownloadStatus.Completed -> listOfNotNull(item.quality, megabytes(item.downloadedBytes), "просмотрено".takeIf { watched }).joinToString(" · ")
                DownloadStatus.Failed -> item.error ?: "Не удалось скачать"
                DownloadStatus.Queued -> "В очереди"
                DownloadStatus.Resolving -> "Готовим ссылку"
                DownloadStatus.Assembling -> "Собираем файл"
                DownloadStatus.Paused -> "Пауза · ${megabytes(item.downloadedBytes)}"
                DownloadStatus.Downloading -> "${(item.share() * 100).toInt()} % · ${megabytes(item.downloadedBytes)}"
            }
            Txt(detail, AniMixTheme.type.micro, color = if (item.status == DownloadStatus.Failed) colors.danger else colors.textSecondary, maxLines = 2)
            if (ready && !watched && position != null && position > 0) {
                val duration = item.episode.durationSeconds?.takeIf { it > 0 }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                    Box(Modifier.fillMaxWidth(duration?.let { (position / 1000f / it).coerceIn(0.05f, 1f) } ?: 0.25f).fillMaxHeight().background(colors.accent))
                }
            }
        }
        when {
            item.active || item.status == DownloadStatus.Queued -> SmallIcon(AniMixIcons.Pause, "Пауза") { onPause(item) }
            item.status == DownloadStatus.Paused || item.status == DownloadStatus.Failed -> SmallIcon(AniMixIcons.Retry, "Продолжить") { onResume(item) }
        }
        SmallIcon(AniMixIcons.Trash, "Удалить") { onRemove(item) }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(18.dp), tint = AniMixTheme.colors.textSecondary)
    }
}

/** A thin accent ring; [content] sits in the middle. */
@Composable
private fun ProgressRing(
    progress: Float,
    modifier: Modifier,
    stroke: androidx.compose.ui.unit.Dp,
    indeterminate: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val colors = AniMixTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        if (indeterminate) {
            CircularProgressIndicator(Modifier.fillMaxSize(), color = colors.accent, strokeWidth = stroke, trackColor = colors.outline)
        } else {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), color = colors.accent, strokeWidth = stroke, trackColor = colors.outline)
        }
        content()
    }
}

@Composable fun OfflineWatchScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val downloads by graph.downloads.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val library by graph.library.state.collectAsState()
    // The next downloaded episode plays on this screen, so fullscreen and autoplay carry over.
    var currentId by androidx.compose.runtime.saveable.rememberSaveable(id) { mutableStateOf(id) }
    var fullscreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val item = downloads.firstOrNull { it.id == currentId }
    val uri = item?.let { graph.downloads.offlineUri(it) }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(AniMixIcons.Back, { navigator.back() })
            Spacer(Modifier.width(12.dp))
            Txt(item?.let { "${it.anime.title} · Серия ${it.episode.number}" } ?: "Офлайн-просмотр", AniMixTheme.type.heading, maxLines = 2)
        }
        Spacer(Modifier.height(16.dp))
        if (item == null || uri == null) MessageState("Файл недоступен", message = "Скачайте серию заново на вкладке «Загрузки»", icon = AniMixIcons.Download)
        else {
            val next = downloads.filter { it.status == DownloadStatus.Completed && it.anime.key == item.anime.key && it.dubbingKey == item.dubbingKey && it.quality == item.quality && it.episode.sortKey > item.episode.sortKey }
                .minByOrNull { it.episode.sortKey }
            val stream = remember(uri) { ResolvedStream(mapOf(item.quality to uri), emptyMap()) }
            key(item.id) {
                AniMixPlayer(stream, item.episode, settings,
                    title = "Серия ${item.episode.number}",
                    subtitle = listOf(item.anime.title, item.studio, "без интернета").joinToString(" · "),
                    // Skip times stored when the episode was watched or queued work offline too.
                    skips = { duration -> graph.skips.plan(item.anime, item.episode, duration) },
                    initialPositionMs = library.entry(item.anime)?.episodePositions?.get(item.episode.number) ?: 0,
                    onProgress = { graph.library.saveEpisodePosition(item.anime, item.episode.number, it) },
                    onCompleted = {
                        graph.library.saveEpisodePosition(item.anime, item.episode.number, 0)
                        item.episode.number.toDoubleOrNull()?.toInt()?.let { graph.library.setEpisodesWatched(item.anime, maxOf(it, graph.library.state.value.entry(item.anime)?.episodesWatched ?: 0)) }
                    },
                    nextLabel = next?.let { "Серия ${it.episode.number}" },
                    onNext = next?.let { { currentId = it.id } },
                    fullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    onAutoNextChoice = { yes -> graph.settings.update { it.copy(autoNextEpisode = yes, autoNextAsked = true) } },
                    onFillScreenChange = { value -> graph.settings.update { it.copy(fillScreen = value) } },
                    onWatchTime = graph.library::addWatchTime,
                    maxStartQuality = graph.connection.playbackCap(),
                )
            }
        }
    }
}
