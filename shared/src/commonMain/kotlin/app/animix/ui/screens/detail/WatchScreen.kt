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
import androidx.compose.ui.unit.dp
import app.animix.Ambient
import app.animix.AppGraph
import app.animix.data.yummy.WatchSourceNotFound
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
    val remembered = settings.dubbingByAnime[anime.key]
    var selectedKey by rememberSaveable(anime.key) { mutableStateOf(remembered) }
    var episodeNumber by rememberSaveable(anime.key) { mutableStateOf<String?>(null) }

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
                        PlayerArea(anime, episodeNumber, Modifier.widthIn(max = 860.dp))
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

/** 16:9 area where the player goes; until then it shows the art and what is selected. */
@Composable
private fun PlayerArea(anime: Anime, episodeNumber: String?, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AniMixTheme.shapes.large).rim(AniMixTheme.shapes.large, accent = false)) {
        RemoteImage(anime.posterUrl, Modifier.fillMaxSize().blur(30.dp), shape = RectangleShape)
        Box(Modifier.fillMaxSize().background(Color(0x80000000)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(colors.accentGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(AniMixIcons.Play, null, Modifier.size(22.dp), tint = colors.onAccent) }
            Spacer(Modifier.height(10.dp))
            Txt(
                if (episodeNumber == null) "Выберите озвучку и серию" else "Плеер подключается на следующем шаге",
                AniMixTheme.type.caption,
                color = Color(0xE6FFFFFF),
            )
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
