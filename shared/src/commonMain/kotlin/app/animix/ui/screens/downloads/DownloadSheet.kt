package app.animix.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.downloads.DownloadStatus
import app.animix.data.downloads.rememberDownloadPermissionRequest
import app.animix.data.yummy.WatchSourceNotFound
import app.animix.model.Anime
import app.animix.model.Dubbing
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

/**
 * Downloads from the anime page: the dubbing first, then episodes or the whole season.
 * The quality is asked here once and kept in settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadSheet(graph: AppGraph, anime: Anime, onQueued: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AniMixTheme.colors
    val settings by graph.settings.state.collectAsState()
    val downloads by graph.downloads.state.collectAsState()
    val source = rememberLoad(anime.key) { force -> graph.yummy.watchSource(anime, force) }
    val askPermission = rememberDownloadPermissionRequest()
    var dubbingKey by remember { mutableStateOf(settings.dubbingByAnime[anime.key]) }
    var chooseDubbing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var quality by remember { mutableStateOf(settings.downloadQuality) }

    AniMixSheet(onDismiss, title = "Скачать", subtitle = anime.title) {
        when (val state = source.value) {
            Load.Loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { Shimmer(Modifier.fillMaxWidth().height(48.dp), AniMixTheme.shapes.medium) }
            }
            is Load.Failed -> MessageState(
                if (state.error is WatchSourceNotFound) "Пока нечего скачивать" else "Не удалось загрузить серии",
                message = state.error.userMessage(),
                icon = AniMixIcons.Download,
                actionLabel = "Повторить",
                onAction = source::refresh,
            )
            is Load.Ready -> {
                val dubbings = state.value.dubbings.filter { it.isKodik }
                if (dubbings.isEmpty()) {
                    MessageState("Скачать нельзя", message = "У этого тайтла нет источника, который можно сохранить", icon = AniMixIcons.Download)
                    return@AniMixSheet
                }
                val dubbing = dubbings.firstOrNull { it.key == dubbingKey } ?: dubbings.first()
                val queued = downloads.filter { it.anime.key == anime.key && it.dubbingKey == dubbing.key }
                    .associate { it.episode.number to (it.status == DownloadStatus.Completed) }
                Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    Txt("Озвучка", AniMixTheme.type.label, color = colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    SheetOption(dubbing.studio, true, { chooseDubbing = !chooseDubbing }, icon = AniMixIcons.Mic, subtitle = "${dubbing.episodes.size} ${episodesWord(dubbing.episodes.size)} · ${if (chooseDubbing) "свернуть" else "сменить"}")
                    if (chooseDubbing) {
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(dubbings.filter { it.key != dubbing.key }, key = { it.key }) { other ->
                                SheetOption(other.studio, false, {
                                    dubbingKey = other.key
                                    picked = emptySet()
                                    chooseDubbing = false
                                }, icon = AniMixIcons.Mic, subtitle = "${other.episodes.size} ${episodesWord(other.episodes.size)}")
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Серии", AniMixTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        val available = dubbing.episodes.filter { it.number !in queued }.map { it.number }.toSet()
                        val all = available.isNotEmpty() && picked.containsAll(available)
                        Chip(if (all) "Снять выбор" else "Весь сезон", selected = all, onClick = { picked = if (all) emptySet() else available }, icon = AniMixIcons.Check)
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dubbing.episodes.forEach { episode ->
                            val saved = queued[episode.number]
                            val selected = episode.number in picked
                            Box(
                                Modifier.size(width = 52.dp, height = 40.dp).clip(AniMixTheme.shapes.medium)
                                    .background(if (selected) colors.accentSoft else colors.chip)
                                    .rim(AniMixTheme.shapes.medium, accent = selected)
                                    .clickable(enabled = saved == null) { picked = if (selected) picked - episode.number else picked + episode.number },
                                contentAlignment = Alignment.Center,
                            ) {
                                Txt(episode.number, AniMixTheme.type.bodyStrong, color = when {
                                    saved != null -> colors.textMuted
                                    selected -> colors.accentText
                                    else -> colors.textPrimary
                                })
                                if (saved != null) Icon(if (saved) AniMixIcons.Check else AniMixIcons.Download, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(10.dp), tint = colors.accent)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (settings.downloadQuality == null) {
                        Txt("Качество · запомним, изменить можно в настройках", AniMixTheme.type.label, color = colors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        DownloadQualityOptions(quality) { quality = it }
                    } else {
                        Txt("Качество ${settings.downloadQuality} · меняется в настройках", AniMixTheme.type.caption)
                    }
                    Spacer(Modifier.height(18.dp))
                    val chosen = dubbing.episodes.filter { it.number in picked }
                    val ready = chosen.isNotEmpty() && quality != null
                    PrimaryButton(
                        when {
                            chosen.isEmpty() -> "Выберите серии"
                            quality == null -> "Выберите качество"
                            else -> "Скачать ${chosen.size} ${episodesWord(chosen.size)}"
                        },
                        enabled = ready,
                        icon = AniMixIcons.Download,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val q = quality ?: return@PrimaryButton
                            askPermission()
                            graph.settings.update { it.copy(downloadQuality = q, dubbingByAnime = it.dubbingByAnime + (anime.key to dubbing.key)) }
                            // The app scope: the sheet closes right away and must not cancel the enqueue.
                            graph.scope.launch {
                                val message = try {
                                    graph.downloads.enqueueEpisodes(anime, dubbing, chosen, q)
                                    graph.skips.prefetch(anime, chosen)
                                    "${chosen.size} ${episodesWord(chosen.size)} в загрузках · ${dubbing.studio} · $q"
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { "Не удалось добавить в загрузки" }
                                onQueued(message)
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
