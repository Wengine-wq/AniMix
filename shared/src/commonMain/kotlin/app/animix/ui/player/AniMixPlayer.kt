package app.animix.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.animix.core.Settings
import app.animix.data.playback.*
import app.animix.model.Episode
import app.animix.model.SkipRange
import app.animix.ui.components.Txt
import app.animix.ui.theme.AniMixTheme

internal fun skipTarget(positionMs: Long, range: SkipRange?): Long? = range?.takeIf {
    it.start >= 0 && it.length > 0 && positionMs >= it.start * 1000L && positionMs < it.end * 1000L
}?.let { it.end * 1000L }

private fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The same controls on both OSes; only the decoder and video surface are native. */
@Composable
fun AniMixPlayer(
    stream: ResolvedStream,
    episode: Episode,
    settings: Settings,
    initialPositionMs: Long = 0,
    onProgress: (Long) -> Unit = {},
    onCompleted: () -> Unit = {},
    onNext: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onDownload: ((String) -> Unit)? = null,
) {
    val backend = remember { runCatching { createNativePlayer() } }
    val player = backend.getOrNull()
    if (player == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            Txt("Не удалось запустить видеодвижок. Проверьте целостность сборки.", AniMixTheme.type.caption, color = Color.White, modifier = Modifier.padding(20.dp))
        }
        return
    }
    val state by player.state.collectAsState()
    var quality by remember { mutableStateOf(stream.qualities.keys.first()) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var openingSkipped by remember { mutableStateOf(false) }
    var endingSkipped by remember { mutableStateOf(false) }
    val progressCallback by rememberUpdatedState(onProgress)
    val available = quality.takeIf { it in stream.qualities } ?: stream.qualities.keys.first()
    LaunchedEffect(stream, available, reload) {
        val snapshot = player.state.value
        val position = snapshot.positionMs.takeIf { snapshot.ready || it > 0 } ?: initialPositionMs
        player.load(stream.qualities.getValue(available), stream.headers, position, if (snapshot.ready) snapshot.playing else true)
    }
    DisposableEffect(player) {
        onDispose { progressCallback(if (player.state.value.ended) 0 else player.state.value.positionMs); player.release() }
    }
    LaunchedEffect(state.positionMs / 5000, state.ready) {
        if (state.positionMs > 0 && state.ready) progressCallback(state.positionMs)
    }
    LaunchedEffect(state.positionMs / 250, state.playing, settings.skipOpenings, settings.skipEndings) {
        if (state.playing) {
            val opening = if (settings.skipOpenings && !openingSkipped) skipTarget(state.positionMs, episode.opening) else null
            val ending = if (settings.skipEndings && !endingSkipped) skipTarget(state.positionMs, episode.ending) else null
            if (opening != null) { openingSkipped = true; player.seekTo(opening) }
            else if (ending != null) { endingSkipped = true; player.seekTo(ending) }
        }
    }
    LaunchedEffect(state.ended) {
        if (state.ended) { onCompleted(); if (settings.autoNextEpisode) onNext?.invoke() }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(AniMixTheme.colors.surface).padding(horizontal = 12.dp, vertical = 4.dp)) {
            var seeking by remember { mutableStateOf<Float?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(timeLabel(state.positionMs), AniMixTheme.type.micro)
                Slider(
                    value = seeking ?: if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { player.seekTo((it * state.durationMs).toLong()) }; seeking = null },
                    enabled = state.durationMs > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Txt(timeLabel(state.durationMs), AniMixTheme.type.micro)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { player.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) }) { Text("−10 с") }
                TextButton(onClick = { if (state.playing) player.pause() else player.play() }) { Text(if (state.playing) "Пауза" else "Смотреть") }
                TextButton(onClick = { player.seekTo((state.positionMs + 10_000).coerceAtMost(state.durationMs.coerceAtLeast(0))) }) { Text("+10 с") }
                if (onNext != null) TextButton(onClick = { player.pause(); onNext() }) { Text("Следующая") }
                TextButton(onClick = { fullscreen = !fullscreen }) { Text(if (fullscreen) "Свернуть" else "На весь экран") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                stream.qualities.keys.forEach { label ->
                    TextButton(onClick = { quality = label }) { Text(label, color = if (available == label) AniMixTheme.colors.accent else AniMixTheme.colors.textSecondary) }
                }
                val opening = skipTarget(state.positionMs, episode.opening)
                val ending = skipTarget(state.positionMs, episode.ending)
                if (opening != null) TextButton(onClick = { openingSkipped = true; player.seekTo(opening) }) { Text("Пропустить опенинг") }
                if (ending != null) TextButton(onClick = { endingSkipped = true; player.seekTo(ending) }) { Text("Пропустить эндинг") }
                if (onDownload != null) TextButton(onClick = { onDownload(available) }) { Text("Скачать серию") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { player.setVolume(if (state.volume > 0) 0f else 1f) }) { Text(if (state.volume > 0) "Звук" else "Без звука") }
                Slider(value = state.volume, onValueChange = player::setVolume, modifier = Modifier.width(120.dp))
                if (state.buffering) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
            if (state.error != null) {
                Txt(state.error!!, AniMixTheme.type.caption)
                TextButton(onClick = { if (onRefresh != null) onRefresh() else reload++ }) { Text(if (onRefresh != null) "Обновить ссылку" else "Повторить") }
            }
        }
    }
    if (fullscreen) PlayerFullscreen(onDismiss = { fullscreen = false }) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            NativeVideoSurface(player, Modifier.fillMaxWidth().weight(1f))
            controls()
        }
    } else Column {
        NativeVideoSurface(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        controls()
    }
}
