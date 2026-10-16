package app.animix.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
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
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block(); error = null } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error = "Не удалось выполнить действие. Проверьте свободное место." }
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
        top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp,
        start = 20.dp, end = 20.dp,
    ), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Txt("Загрузки", AniMixTheme.type.title)
            Txt("Готово: ${downloads.count { it.status == DownloadStatus.Completed }} · В очереди: ${downloads.count { it.status != DownloadStatus.Completed }}", AniMixTheme.type.caption)
            Row {
                TextButton(onClick = { action { graph.downloads.pauseAll() } }) { Txt("Приостановить всё", AniMixTheme.type.label) }
                TextButton(onClick = { action { graph.downloads.resumeAll() } }) { Txt("Продолжить всё", AniMixTheme.type.label) }
            }
            (error ?: notice)?.let { Txt(it, AniMixTheme.type.caption) }
        }
        if (downloads.isEmpty()) item { MessageState("Серии всегда под рукой", message = "Выберите озвучку и скачайте серию или целый сезон на экране просмотра", icon = AniMixIcons.Download) }
        items(downloads, key = { it.id }) { item ->
            Panel {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Txt(item.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                    Txt("Серия ${item.episode.number} · ${item.studio} · ${item.quality}", AniMixTheme.type.caption)
                    val status = when (item.status) {
                        DownloadStatus.Queued -> "В очереди"
                        DownloadStatus.Resolving -> "Получаем ссылку"
                        DownloadStatus.Downloading -> "Скачиваем · ${item.completedParts}/${item.totalParts} частей"
                        DownloadStatus.Assembling -> "Собираем MP4"
                        DownloadStatus.Paused -> "На паузе"
                        DownloadStatus.Failed -> item.error ?: "Не удалось скачать"
                        DownloadStatus.Completed -> "Доступно без интернета"
                    }
                    Txt("$status · ${item.downloadedBytes / 1024 / 1024} МБ", AniMixTheme.type.caption)
                    if (item.active) {
                        Spacer(Modifier.height(8.dp))
                        if (item.totalParts > 0) LinearProgressIndicator(progress = { (item.completedParts.toFloat() / item.totalParts).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row {
                        if (item.status == DownloadStatus.Completed) TextButton(onClick = { navigator.push(Route.Offline(item.id)) }) { Txt("Смотреть", AniMixTheme.type.label, color = AniMixTheme.colors.accent) }
                        else if (item.active || item.status == DownloadStatus.Queued) TextButton(onClick = { action { graph.downloads.pause(item.id) } }) { Txt("Пауза", AniMixTheme.type.label) }
                        else TextButton(onClick = { action { graph.downloads.resume(item.id) } }) { Txt(if (item.status == DownloadStatus.Failed) "Повторить" else "Продолжить", AniMixTheme.type.label) }
                        TextButton(onClick = { action { graph.downloads.remove(item.id) } }) { Txt("Удалить", AniMixTheme.type.label) }
                    }
                }
            }
        }
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
