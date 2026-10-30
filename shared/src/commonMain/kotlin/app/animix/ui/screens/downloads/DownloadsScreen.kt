package app.animix.ui.screens.downloads

import app.animix.ui.theme.animateMotionFloatAsState as animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.animix.Ambient
import app.animix.AppGraph
import app.animix.data.downloads.DownloadItem
import app.animix.data.downloads.DownloadStatus
import app.animix.data.library.LibraryEntry
import app.animix.data.library.isEpisodeWatched
import app.animix.data.downloads.rememberDownloadPermissionRequest
import app.animix.data.playback.ResolvedStream
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.player.AniMixPlayer
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val downloads by graph.downloads.state.collectAsState()
    val notice by graph.downloads.notice.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val titles = downloads.groupBy { it.anime.key }.values.toList()
    val running = downloads.any { it.active || it.status == DownloadStatus.Queued }
    val done = downloads.count { it.status == DownloadStatus.Completed }
    val fontScale = LocalDensity.current.fontScale
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block(); error = null } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error = "Не удалось изменить очередь. Попробуйте ещё раз." }
    } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (fontScale > 1.15f) 180.dp else 150.dp),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("Загрузки", AniMixTheme.type.title)
                            Txt(if (downloads.isEmpty()) "Смотрите без интернета" else
                                "Готово $done из ${downloads.size} · ${megabytes(downloads.sumOf { it.downloadedBytes })}",
                                AniMixTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
                        }
                        // Acts on the whole queue, so it says so in words, not only with an icon.
                        if (done < downloads.size) Chip(if (running) "Пауза всего" else "Продолжить всё", false,
                            { action { if (running) graph.downloads.pauseAll() else graph.downloads.resumeAll() } },
                            icon = if (running) AniMixIcons.Pause else AniMixIcons.Play)
                    }
                    (error ?: notice)?.let { Txt(it, AniMixTheme.type.caption, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
            if (titles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                MascotMessage("Серии всегда под рукой",
                    message = "На странице аниме откройте «Ещё → Загрузки» и сохраните серии для просмотра без сети.",
                    pose = MascotPose.Relaxed,
                    onExplore = { navigator.select(app.animix.ui.navigation.Tab.Catalog) })
            }
            items(titles, key = { it.first().anime.key }) { episodes ->
                DownloadTitleCard(episodes) { navigator.push(Route.Downloads(episodes.first().anime)) }
            }
        }
    }
}

/**
 * One page per title, opened from the anime page and from «Загрузки»: the same structure as
 * «Смотреть» — dubbing choice, then quality, then the episode grid. Episodes not yet saved
 * are picked right in the grid; saved ones show their state and actions.
 */
@Composable
fun TitleDownloadsScreen(graph: AppGraph, navigator: Navigator, opened: app.animix.model.Anime, contentPadding: PaddingValues, add: Boolean = false) {
    val downloads by graph.downloads.state.collectAsState()
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val notice by graph.downloads.notice.collectAsState()
    val scope = rememberCoroutineScope()
    val askPermission = rememberDownloadPermissionRequest()
    val items = downloads.filter { it.anime.key == opened.key }
    // A link knows only the id: take the title from saved episodes or load it.
    val detail = rememberLoad(opened.key to opened.title.isEmpty()) {
        if (opened.title.isNotEmpty() || items.isNotEmpty()) kotlinx.coroutines.awaitCancellation()
        graph.anime.detail(opened)
    }
    val anime = opened.takeIf { it.title.isNotEmpty() } ?: items.firstOrNull()?.anime ?: (detail.value as? Load.Ready)?.value?.anime ?: opened
    Ambient(anime.posterUrl)
    // Offline the page still works from what is saved; the source only adds what can be downloaded.
    // The saved page needs no network at all; only adding episodes asks the source.
    val source = rememberLoad(Triple(anime.key, anime.title.isNotEmpty(), add)) { force ->
        if (anime.title.isEmpty() || !add) kotlinx.coroutines.awaitCancellation()
        graph.yummy.watchSource(anime, force)
    }
    val available = (source.value as? Load.Ready)?.value?.dubbings.orEmpty().filter { it.isKodik }
    val savedKeys = items.map { it.dubbingKey }.distinct()
    val dubbingKeys = (available.map { it.key } + savedKeys).distinct()
    var selectedKey by rememberSaveable(opened.key) { mutableStateOf<String?>(null) }
    val dubbingKey = selectedKey?.takeIf { it in dubbingKeys }
        ?: savedKeys.firstOrNull()
        ?: settings.dubbingByAnime[anime.key]?.takeIf { it in dubbingKeys }
        ?: dubbingKeys.firstOrNull()
    val dubbing = available.firstOrNull { it.key == dubbingKey }
    val saved = items.filter { it.dubbingKey == dubbingKey }.associateBy { it.episode.number }
    val episodes = (dubbing?.episodes?.map { it.number } ?: saved.keys.toList())
        .let { numbers -> (numbers + saved.keys).distinct() }
        .sortedBy { number -> number.toDoubleOrNull() ?: Double.MAX_VALUE }
    var picked by remember(opened.key, dubbingKey) { mutableStateOf(emptySet<String>()) }
    val pickable = episodes.filter { it !in saved && dubbing != null }
    val quality = settings.downloadQuality
    var confirmRemoval by rememberSaveable(opened.key) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf<String?>(null) }
    val group = saved.values.toList()
    val running = group.any { it.active || it.status == DownloadStatus.Queued }
    val resumable = group.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
    val colors = AniMixTheme.colors
    val fontScale = LocalDensity.current.fontScale
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block(); error = null } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error = "Не удалось выполнить действие. Проверьте свободное место." }
    } }
    fun download() {
        val target = dubbing ?: return
        val q = quality ?: return
        val chosen = target.episodes.filter { it.number in picked }
        if (chosen.isEmpty()) return
        askPermission()
        graph.settings.update { it.copy(dubbingByAnime = it.dubbingByAnime + (anime.key to target.key)) }
        picked = emptySet()
        action {
            graph.downloads.enqueueEpisodes(anime, target, chosen, q)
            graph.skips.prefetch(anime, chosen)
            queued = "${chosen.size} ${episodesWord(chosen.size)} добавлено в очередь"
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 900.dp) 10 else if (maxWidth >= 600.dp) 8 else 5
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(AniMixIcons.Back, { navigator.back() }, contentDescription = "Назад")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(anime.title, AniMixTheme.type.heading, maxLines = 2)
                            Txt(
                                if (add) "Скачать серии для просмотра без интернета"
                                else if (items.isEmpty()) "Серий в загрузках нет"
                                else "В загрузках ${items.count { it.status == DownloadStatus.Completed }} из ${items.size} · ${megabytes(items.sumOf { it.downloadedBytes })}",
                                AniMixTheme.type.caption,
                            )
                        }
                        if (items.isNotEmpty()) IconCircle(AniMixIcons.Trash, { confirmRemoval = !confirmRemoval }, contentDescription = "Удалить тайтл из загрузок")
                    }
                    if (confirmRemoval && items.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Txt("Удалить все ${items.size} ${episodesWord(items.size)} «${anime.title}»?", AniMixTheme.type.bodyStrong)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Отмена", false, { confirmRemoval = false }, modifier = Modifier.heightIn(min = 44.dp))
                            Chip("Удалить", true, { confirmRemoval = false; action { items.forEach { graph.downloads.remove(it.id) } } },
                                modifier = Modifier.heightIn(min = 44.dp), icon = AniMixIcons.Trash)
                        }
                    }
                    // Offer to continue right away, before any choices.
                    if (!add) continueOffline(items, library.entry(anime))?.let { next ->
                        Spacer(Modifier.height(20.dp))
                        ContinueOffline(next, library.entry(anime)?.episodePositions?.get(next.episode.number) ?: 0) { navigator.push(Route.Offline(next.id)) }
                    }
                    if (add && dubbingKeys.isEmpty() && source.value is Load.Loading) {
                        Spacer(Modifier.height(24.dp))
                        SelectionPlaceholder()
                    }
                    if (add && dubbingKeys.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        SelectionList("Озвучка", dubbingKeys.map { key ->
                            val option = available.firstOrNull { it.key == key }
                            val count = items.count { it.dubbingKey == key }
                            val studio = option?.studio ?: items.first { it.dubbingKey == key }.studio
                            val total = option?.episodes?.size
                            SelectionOption(key, studio, listOfNotNull(
                                total?.let { "$it ${episodesWord(it)}" },
                                "скачано $count".takeIf { count > 0 },
                            ).joinToString(" · "))
                        }, dubbingKey, AniMixIcons.Mic) { selectedKey = it }
                    }
                    if (add && dubbing != null) {
                        Spacer(Modifier.height(24.dp))
                        Txt("Качество", AniMixTheme.type.heading)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadQualities.forEach { (label, _) ->
                                Chip(label, selected = quality == label, onClick = { graph.settings.update { it.copy(downloadQuality = label) } })
                            }
                        }
                        Txt(
                            if (quality == null) "Выберите один раз, запомним для следующих загрузок" else "Запоминается для всех загрузок, меняется и в настройках",
                            AniMixTheme.type.micro, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                        )
                    }
                    (error ?: notice ?: queued)?.let {
                        Txt(it, AniMixTheme.type.caption, color = if (error != null) colors.danger else colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            val wideSpan: (Int) -> Int = { line -> if (line >= 8) line / 2 else line }
            if (!add) {
                val groups = items.groupBy { it.studio to it.quality }
                if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    MessageState("Серий в загрузках нет", message = "Скачайте их со страницы аниме", icon = AniMixIcons.Download,
                        actionLabel = "Скачать серии", onAction = { navigator.push(Route.Downloads(anime, add = true)) })
                }
                groups.entries.forEachIndexed { index, (variant, group) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Txt(variant.first, AniMixTheme.type.heading, maxLines = 1)
                                Txt("${variant.second} · ${group.size} ${episodesWord(group.size)}", AniMixTheme.type.caption)
                            }
                            if (index == 0 && (running || resumable)) Chip(if (running) "Пауза загрузок" else "Докачать", false, {
                                action { items.filter { if (running) it.active || it.status == DownloadStatus.Queued
                                    else it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
                                    .forEach { if (running) graph.downloads.pause(it.id) else graph.downloads.resume(it.id) } }
                            }, icon = if (running) AniMixIcons.Pause else AniMixIcons.Play)
                        }
                    }
                    items(group.sortedBy { it.episode.sortKey }, key = { it.id }, span = { GridItemSpan(wideSpan(maxLineSpan)) }) { item ->
                        DownloadEpisodeCard(item, library.entry(anime),
                            onPlay = { navigator.push(Route.Offline(item.id)) },
                            onPause = { action { graph.downloads.pause(item.id) } },
                            onResume = { action { graph.downloads.resume(item.id) } },
                            onRemove = { action { graph.downloads.remove(item.id) } })
                    }
                }
                if (items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        SecondaryButton("Скачать ещё серии", { navigator.push(Route.Downloads(anime, add = true)) }, icon = AniMixIcons.Download)
                    }
                }
            } else when {
                episodes.isNotEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Txt("Серии · ${episodes.size}", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
                                if (pickable.isNotEmpty()) {
                                    val all = picked.containsAll(pickable)
                                    Txt(if (all) "Снять выбор" else "Выбрать все", AniMixTheme.type.label, color = colors.accent,
                                        modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable { picked = if (all) emptySet() else pickable.toSet() }
                                            .padding(horizontal = 8.dp, vertical = 10.dp))
                                }
                            }
                            if (pickable.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Row {
                                    PrimaryButton(
                                        when {
                                            picked.isEmpty() -> "Скачать"
                                            quality == null -> "Выберите качество"
                                            else -> "Скачать ${picked.size}"
                                        },
                                        onClick = ::download,
                                        enabled = picked.isNotEmpty() && quality != null,
                                        icon = AniMixIcons.Download,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    items(episodes, key = { "$dubbingKey:$it" }, span = { GridItemSpan(wideSpan(maxLineSpan)) }) { number ->
                        val item = saved[number]
                        val entry = library.entry(anime)
                        if (item != null) {
                            DownloadEpisodeCard(item, entry,
                                onPlay = { navigator.push(Route.Offline(item.id)) },
                                onPause = { action { graph.downloads.pause(item.id) } },
                                onResume = { action { graph.downloads.resume(item.id) } },
                                onRemove = { action { graph.downloads.remove(item.id) } })
                        } else {
                            val on = number in picked
                            PickEpisodeRow(number, on, watched = entry?.isEpisodeWatched(number) == true, enabled = dubbing != null) {
                                picked = if (on) picked - number else picked + number
                            }
                        }
                    }
                }
                source.value is Load.Loading -> items(15) { Shimmer(Modifier.fillMaxWidth().height(48.dp), AniMixTheme.shapes.medium) }
                else -> item(span = { GridItemSpan(maxLineSpan) }) {
                    val failure = source.value as? Load.Failed
                    MessageState(
                        if (failure?.error is app.animix.data.yummy.WatchSourceNotFound) "Пока нечего скачивать" else "Не удалось загрузить серии",
                        message = failure?.error?.userMessage() ?: "У этого тайтла нет источника, который можно сохранить",
                        icon = AniMixIcons.Download,
                        actionLabel = if (failure != null) "Повторить" else null,
                        onAction = if (failure != null) source::refresh else null,
                    )
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
    val tenths = bytes * 10 / (1024L * 1024 * 1024)
    "${tenths / 10},${tenths % 10} ГБ"
} else "${bytes / 1024 / 1024} МБ"

private fun DownloadItem.share(): Float = when {
    status == DownloadStatus.Completed -> 1f
    totalParts > 0 -> (completedParts.toFloat() / totalParts).coerceIn(0f, 0.99f)
    else -> 0f
}

private fun List<DownloadItem>.overallProgress(): Float = if (isEmpty()) 0f else (sumOf { it.share().toDouble() } / size).toFloat()

private fun List<DownloadItem>.queueLabel(): String = when {
    all { it.status == DownloadStatus.Completed } -> "Скачано"
    any { it.active } -> if (any { it.status == DownloadStatus.Assembling }) "Сборка файла" else "Скачивается"
    any { it.status == DownloadStatus.Queued } -> "В очереди"
    any { it.status == DownloadStatus.Failed } -> "Есть ошибки"
    else -> "На паузе"
}

@Composable
private fun DownloadTitleCard(items: List<DownloadItem>, onClick: () -> Unit) {
    val anime = items.first().anime
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val progress = items.overallProgress()
    val failed = items.any { it.status == DownloadStatus.Failed } && items.none { it.active || it.status == DownloadStatus.Queued }
    Column(Modifier.pressScale(interaction).clickable(interaction, indication = null, role = Role.Button, onClick = onClick)) {
        Box {
            RemoteImage(anime.posterUrl, Modifier.fillMaxWidth().aspectRatio(0.7f), shape = AniMixTheme.shapes.large)
            Txt("${(progress * 100).toInt()}%", AniMixTheme.type.micro, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(AniMixTheme.shapes.pill)
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Txt(anime.title, AniMixTheme.type.label, maxLines = 2, modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 2.dp))
        Spacer(Modifier.height(4.dp))
        Txt(items.queueLabel(), AniMixTheme.type.micro, color = if (failed) colors.danger else colors.textSecondary,
            modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(8.dp))
        DownloadProgress(progress)
        Spacer(Modifier.height(6.dp))
        Txt("${items.count { it.status == DownloadStatus.Completed }} из ${items.size} · ${megabytes(items.sumOf { it.downloadedBytes })}",
            AniMixTheme.type.micro, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun DownloadProgress(progress: Float, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val animated by animateFloatAsState(progress, tween(AniMixMotion.Standard))
    Box(modifier.fillMaxWidth().height(4.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
        Box(Modifier.fillMaxWidth(animated.coerceIn(0f, 1f)).fillMaxHeight().clip(AniMixTheme.shapes.pill).background(colors.accent))
    }
}

@Composable
private fun DownloadEpisodeCard(item: DownloadItem, entry: LibraryEntry?, onPlay: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
    val colors = AniMixTheme.colors
    val ready = item.status == DownloadStatus.Completed
    val watched = entry?.isEpisodeWatched(item.episode.number) == true
    val position = entry?.episodePositions?.get(item.episode.number) ?: 0
    val busy = item.active || item.status == DownloadStatus.Queued
    // One row per saved episode: number, state with progress, then the actions for this episode only.
    EpisodeCardSurface(selected = item.active, onClick = onPlay, enabled = ready) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeBadge(item.episode.number, watched = watched, highlight = ready && !watched)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(when (item.status) {
                        DownloadStatus.Completed -> if (watched) "Просмотрено" else if (position > 0) "Продолжить просмотр" else "Готово к просмотру"
                        DownloadStatus.Failed -> "Ошибка загрузки"
                        DownloadStatus.Queued -> "В очереди"
                        DownloadStatus.Resolving -> "Готовим ссылку"
                        DownloadStatus.Assembling -> "Собираем файл"
                        DownloadStatus.Paused -> "На паузе · ${(item.share() * 100).toInt()}%"
                        DownloadStatus.Downloading -> "Скачивается · ${(item.share() * 100).toInt()}%"
                    }, AniMixTheme.type.label, color = when { item.status == DownloadStatus.Failed -> colors.danger; watched -> colors.textSecondary; else -> colors.textPrimary }, maxLines = 1)
                }
                Txt(
                    if (item.status == DownloadStatus.Failed) item.error ?: "Попробуйте ещё раз" else "${item.quality} · ${megabytes(item.downloadedBytes)}",
                    AniMixTheme.type.micro, maxLines = 2, modifier = Modifier.padding(top = 2.dp),
                )
                if (!ready) DownloadProgress(item.share(), Modifier.padding(top = 8.dp))
                // How far the viewer got in a saved, unfinished episode.
                val duration = item.episode.durationSeconds?.takeIf { it > 0 }
                if (ready && !watched && position > 0 && duration != null) DownloadProgress((position / 1000f / duration).coerceIn(0.05f, 1f), Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.width(4.dp))
            when {
                ready -> RowIcon(AniMixIcons.Play, "Смотреть серию ${item.episode.number}", accent = true, onClick = onPlay)
                busy -> RowIcon(AniMixIcons.Pause, "Приостановить серию ${item.episode.number}", onClick = onPause)
                else -> RowIcon(AniMixIcons.Retry, "Продолжить загрузку серии ${item.episode.number}", onClick = onResume)
            }
            RowIcon(AniMixIcons.Trash, "Удалить серию ${item.episode.number}", onClick = onRemove)
        }
    }
}

/** A 44 dp touch target with a small icon, for actions inside an episode row. */
@Composable
private fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, accent: Boolean = false, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (accent) colors.accent else colors.textSecondary)
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
            val next = app.animix.data.downloads.nextDownloadedEpisode(downloads, item) {
                graph.downloads.offlineUri(it) != null
            }
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
                    },
                    nextLabel = next?.let { "Серия ${it.episode.number}" },
                    onNext = next?.let { { currentId = it.id } },
                    fullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    onAutoNextChoice = { yes ->
                        graph.settings.update { it.copy(autoNextEpisode = yes, autoNextAsked = true) }
                        if (yes) graph.achievements.settingEnabled("autoNextEpisode")
                    },
                    onFillScreenChange = { value -> graph.settings.update { it.copy(fillScreen = value) } },
                    onPlayback = { update -> graph.library.recordPlayback(item.anime, item.episode.number, update.credit,
                        update.durationMs, update.opening, update.ending, update.reachedEnd,
                        app.animix.data.library.PlaybackContext(item.studio, offline = true, platform = app.animix.core.Platform.name)) },
                    onSkip = graph.library::recordSkip,
                    maxStartQuality = graph.connection.playbackCap(),
                )
            }
        }
    }
}

/** The episode number; a watched one gets a clear check mark in the corner. */
@Composable
private fun EpisodeBadge(number: String, watched: Boolean, highlight: Boolean) {
    val colors = AniMixTheme.colors
    Box(Modifier.size(44.dp)) {
        Box(Modifier.size(40.dp).align(Alignment.TopStart).clip(AniMixTheme.shapes.small)
            .background(if (highlight) colors.accentSoft else colors.surfaceRaised), contentAlignment = Alignment.Center) {
            Txt(number, AniMixTheme.type.bodyStrong, maxLines = 1,
                color = when { highlight -> colors.accentText; watched -> colors.textMuted; else -> colors.textSecondary })
        }
        if (watched) {
            Box(Modifier.size(18.dp).align(Alignment.BottomEnd).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center) {
                Icon(AniMixIcons.Check, "Просмотрено", Modifier.size(11.dp), tint = colors.onAccent)
            }
        }
    }
}

/** An episode that is not saved yet: tap to add it to the download selection. */
@Composable
private fun PickEpisodeRow(number: String, picked: Boolean, watched: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = AniMixTheme.colors
    EpisodeCardSurface(selected = picked, onClick = onToggle, enabled = enabled) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeBadge(number, watched = watched, highlight = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (picked) "Будет скачана" else "Не скачана", AniMixTheme.type.label, color = if (picked) colors.accentText else colors.textSecondary)
                if (watched) Txt("Просмотрено онлайн", AniMixTheme.type.micro, modifier = Modifier.padding(top = 2.dp))
            }
            Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (picked) colors.accent else colors.outline), contentAlignment = Alignment.Center) {
                if (picked) Icon(AniMixIcons.Check, "Выбрана", Modifier.size(14.dp), tint = colors.onAccent)
            }
        }
    }
}

/** «Продолжить»: the saved episode in progress, or the first saved one not watched yet. */
@Composable
private fun ContinueOffline(item: DownloadItem, positionMs: Long, onPlay: () -> Unit) {
    val colors = AniMixTheme.colors
    EpisodeCardSurface(selected = true, onClick = onPlay) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                Icon(AniMixIcons.Play, null, Modifier.size(18.dp), tint = colors.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt("Продолжить · Серия ${item.episode.number}", AniMixTheme.type.bodyStrong, maxLines = 1)
                Txt(
                    if (positionMs > 0) "С ${positionMs / 60_000}:${(positionMs / 1000 % 60).toString().padStart(2, '0')} · без интернета" else "Следующая непросмотренная · без интернета",
                    AniMixTheme.type.micro, maxLines = 1,
                )
            }
        }
    }
}

/** The saved episode to continue: the one in progress, else the first finished download not watched yet. */
internal fun continueOffline(items: List<DownloadItem>, entry: LibraryEntry?): DownloadItem? {
    val ready = items.filter { it.status == DownloadStatus.Completed }.sortedBy { it.episode.sortKey }
    val unwatched = ready.filter { entry?.isEpisodeWatched(it.episode.number) != true }
    return unwatched.lastOrNull { (entry?.episodePositions?.get(it.episode.number) ?: 0) > 0 } ?: unwatched.firstOrNull()
}
