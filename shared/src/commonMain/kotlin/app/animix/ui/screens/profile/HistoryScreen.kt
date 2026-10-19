package app.animix.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.Platform
import app.animix.data.AppRelease
import app.animix.data.library.LibraryEntry
import app.animix.ui.components.IconCircle
import app.animix.ui.components.MessageState
import app.animix.ui.components.NavRow
import app.animix.ui.components.Panel
import app.animix.ui.components.RemoteImage
import app.animix.ui.components.Txt
import app.animix.core.formatDate
import app.animix.ui.components.pressScale
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** What you watched last, newest first: titles with progress, an open episode or a "Смотрю" status. */
@Composable
fun HistoryScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val entries = library.entries.values
        .filter { it.episodePositions.isNotEmpty() || it.episodesWatched > 0 }
        .sortedByDescending { it.updatedAt }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 820.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                    Spacer(Modifier.width(12.dp))
                    Txt("История просмотров", AniMixTheme.type.title)
                }
            }
            if (entries.isEmpty()) {
                item {
                    MessageState(
                        "Здесь появятся просмотренные серии",
                        message = "Начните смотреть что-нибудь, и мы запомним, где вы остановились",
                        icon = AniMixIcons.History,
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    )
                }
            } else {
                items(entries, key = { it.anime.key }) { entry ->
                    HistoryRow(entry) { navigator.push(Route.Watch(entry.anime)) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: LibraryEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val lastEpisode = entry.episodePositions.keys.maxByOrNull { it.toDoubleOrNull() ?: 0.0 }
    val line = when {
        lastEpisode != null -> "Серия $lastEpisode · остановились на ${clock(entry.episodePositions.getValue(lastEpisode))}"
        else -> "Просмотрено серий: ${entry.episodesWatched}"
    }
    Panel(Modifier.pressScale(interaction).clickable(interaction, indication = null, onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(entry.anime.posterUrl, Modifier.width(56.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(entry.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Txt(line, AniMixTheme.type.caption, maxLines = 1)
                Txt(formatDate(entry.updatedAt / 1000), AniMixTheme.type.micro)
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Version, where the data lives, and housekeeping. */
@Composable
fun SupportScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth()
                .padding(contentPadding).padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
                Spacer(Modifier.width(12.dp))
                Txt("Поддержка", AniMixTheme.type.title)
            }
            Spacer(Modifier.height(24.dp))
            Panel {
                Column(Modifier.padding(16.dp)) {
                    Txt(AppRelease.label, AniMixTheme.type.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    Txt("${Platform.name} · данные: ${Platform.dataDir}", AniMixTheme.type.caption)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel {
                NavRow("Очистить кэш каталога", AniMixIcons.Refresh, onClick = { scope.launch { graph.clearCache() } })
            }
        }
    }
}
