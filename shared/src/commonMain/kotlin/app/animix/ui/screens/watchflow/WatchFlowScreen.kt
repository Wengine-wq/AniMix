package app.animix.ui.screens.watchflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.nowMillis
import app.animix.core.localDayIndex
import app.animix.data.downloads.DownloadStatus
import app.animix.data.library.ListStatus
import app.animix.data.playback.ResolvedStream
import app.animix.data.watchflow.*
import app.animix.model.Anime
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.player.AniMixPlayer
import app.animix.ui.screens.detail.resumeEpisode
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun WatchFlowScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues, opened: Anime? = null, openEpisode: String? = null) {
    val state by graph.watchFlow.state.collectAsState()
    LaunchedEffect(Unit) { graph.watchFlow.load() }
    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(30.dp)) }
        state.active -> FlowSessionScreen(graph, navigator, padding, state)
        state.phase == FlowPhase.Finished -> FlowFinishedScreen(graph, navigator, padding, state)
        else -> FlowPlannerScreen(graph, navigator, padding, state, opened, openEpisode)
    }
}

@Composable
private fun FlowPlannerScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues, state: FlowState, opened: Anime?, openEpisode: String?) {
    val library by graph.library.state.collectAsState()
    val downloads by graph.downloads.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val choices = remember(library.entries, downloads, opened) {
        (listOfNotNull(opened) + library.entries.values.filter { it.status == ListStatus.Watching || it.status == ListStatus.OnHold }
            .sortedByDescending { it.updatedAt }.map { it.anime } + downloads.filter { it.status == DownloadStatus.Completed }.map { it.anime })
            .distinctBy { it.key }
    }
    var animeKey by rememberSaveable { mutableStateOf(opened?.key) }
    val anime = choices.firstOrNull { it.key == animeKey } ?: choices.firstOrNull()
    val source = rememberLoad(anime?.key) { if (anime == null) awaitCancellation(); graph.flowPlanner.source(anime) }
    val sourceState = source.value
    val available = (sourceState as? Load.Ready)?.value
    var dubbingKey by rememberSaveable(anime?.key) { mutableStateOf(anime?.let { settings.dubbingByAnime[it.key] }) }
    val dubbing = available?.dubbings?.firstOrNull { it.key == dubbingKey } ?: available?.dubbings?.firstOrNull()
    var from by rememberSaveable(anime?.key, dubbing?.key) {
        mutableStateOf(openEpisode?.takeIf { anime?.key == opened?.key && dubbing?.episodes?.any { episode -> episode.number == it } == true }
            ?: resumeEpisode(dubbing?.episodes.orEmpty(), anime?.let { library.entry(it) })?.number)
    }
    var page by rememberSaveable(anime?.key, dubbing?.key) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var minutes by rememberSaveable { mutableStateOf(state.options.minutes.toString()) }
    val scope = rememberCoroutineScope()
    fun prepare(fit: Boolean) {
        val title = anime ?: return
        val chosen = dubbing ?: return
        val catalog = available ?: return
        val number = from ?: return
        if (busy || minutes.toIntOrNull() !in 1..360) return
        busy = true; problem = null
        scope.launch {
            try {
                val items = graph.flowPlanner.prepare(title, catalog, chosen, number, state.options, state.queue, fit)
                if (items.isEmpty()) {
                    val candidates = chosen.episodes.dropWhile { it.number != number }.let { if (fit) it else it.take(1) }
                    problem = if (candidates.all { episode -> state.queue.any { it.key == "${title.key}|${chosen.key}|${episode.number}" } })
                        "Эти серии уже есть в очереди. Выберите другую серию или тайтл."
                    else "Следующая серия целиком не помещается в оставшееся время. Увеличьте лимит или добавьте её вручную."
                }
                else graph.watchFlow.add(items)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { problem = "Не удалось подготовить очередь. Проверьте соединение и повторите." }
            finally { busy = false }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(GridCells.Adaptive(56.dp), modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    PageHeader("Watch flow", { navigator.back() })
                    Spacer(Modifier.height(8.dp))
                    Txt("Очередь под ваше время — от первой серии до конца сессии.", AniMixTheme.type.caption)
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("У меня есть", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
                        BasicTextField(minutes, { input ->
                            if (!busy) {
                                minutes = input.filter(Char::isDigit).take(3)
                                minutes.toIntOrNull()?.takeIf { it in 1..360 }?.let { graph.watchFlow.options(state.options.copy(minutes = it)) }
                            }
                        }, singleLine = true, textStyle = AniMixTheme.type.heading.copy(color = AniMixTheme.colors.textPrimary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(AniMixTheme.colors.accent),
                            modifier = Modifier.width(76.dp).clip(AniMixTheme.shapes.medium).background(AniMixTheme.colors.chip).padding(12.dp))
                        Spacer(Modifier.width(8.dp))
                        Txt("минут", AniMixTheme.type.caption)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (minutes.toIntOrNull() !in 1..360) {
                        Txt("Укажите от 1 до 360 минут", AniMixTheme.type.micro, color = AniMixTheme.colors.danger)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 55, 90, 120).forEach { value -> Chip("$value", state.options.minutes == value, {
                            if (!busy) { minutes = value.toString(); graph.watchFlow.options(state.options.copy(minutes = value)) }
                        }) }
                    }
                    Spacer(Modifier.height(20.dp))
                    FlowPlanSummary(state)
                    Spacer(Modifier.height(12.dp))
                }
            }
            items(state.queue, key = { "queue:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                FlowQueueRow(item, state.options, onRemove = { if (!busy) graph.watchFlow.remove(item.key) },
                    onUp = state.queue.indexOf(item).takeIf { it > 0 }?.let { { if (!busy) graph.watchFlow.move(item.key, -1) } },
                    onDown = state.queue.indexOf(item).takeIf { it < state.queue.lastIndex }?.let { { if (!busy) graph.watchFlow.move(item.key, 1) } })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (state.queue.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        PrimaryButton("Начать сессию", { graph.watchFlow.start() }, Modifier.fillMaxWidth(), icon = AniMixIcons.Play,
                            enabled = !busy && minutes.toIntOrNull() in 1..360)
                        Spacer(Modifier.height(24.dp))
                    }
                    Panel {
                        ToggleRow("Пропускать опенинги", state.options.skipOpenings,
                            { graph.watchFlow.options(state.options.copy(skipOpenings = it)) }, AniMixIcons.SkipAhead, enabled = !busy)
                        ToggleRow("Пропускать эндинги", state.options.skipEndings,
                            { graph.watchFlow.options(state.options.copy(skipEndings = it)) }, AniMixIcons.SkipAhead, enabled = !busy)
                        ToggleRow("Автопереход внутри сессии", state.options.autoNext,
                            { graph.watchFlow.options(state.options.copy(autoNext = it)) }, AniMixIcons.Next, enabled = !busy)
                    }
                    Spacer(Modifier.height(10.dp))
                    Txt("Таймер учитывает паузы и загрузку. Опенинги исключаем только при наличии разметки. Лимит — от 1 до 360 минут.", AniMixTheme.type.micro)
                    Spacer(Modifier.height(24.dp))
                    if (choices.isEmpty()) {
                        MessageState("Добавьте тайтл в очередь", message = "Выберите аниме из «Смотрю» или откройте Watch flow со страницы просмотра.",
                            icon = AniMixIcons.Clock, actionLabel = "Открыть каталог", onAction = { navigator.openLink("animix://catalog") })
                    } else {
                        SelectionList("Тайтл", choices.map { SelectionOption(it.key, it.title, "Для очереди Watch flow") }, anime?.key, AniMixIcons.Play) {
                            if (!busy) { animeKey = it; problem = null }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (val loaded = sourceState) {
                            Load.Loading -> Shimmer(Modifier.fillMaxWidth().height(56.dp), AniMixTheme.shapes.medium)
                            is Load.Failed -> MessageState("Не удалось получить серии", message = loaded.error.userMessage(), icon = AniMixIcons.Refresh,
                                actionLabel = "Повторить", onAction = source::refresh)
                            is Load.Ready -> {
                                if (loaded.value.dubbings.isEmpty()) Txt("Для этой сессии нужен встроенный плеер Kodik или скачанные серии.", AniMixTheme.type.caption)
                                else SelectionList("Озвучка", loaded.value.dubbings.map {
                                    SelectionOption(it.key, it.studio, "${it.episodes.size} ${episodesWord(it.episodes.size)} · ${if (it.offlineOnly) "скачано" else "Kodik"}")
                                }, dubbing?.key, AniMixIcons.Mic) { if (!busy) dubbingKey = it }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (state.queue.isEmpty()) PrimaryButton(if (busy) "Готовим очередь…" else "Подобрать на ${state.options.minutes} минут",
                            { prepare(true) }, Modifier.fillMaxWidth(), icon = AniMixIcons.Clock, enabled = !busy && dubbing != null && from != null)
                        else SecondaryButton(if (busy) "Готовим очередь…" else "Добавить в оставшееся время", { prepare(true) }, Modifier.fillMaxWidth(), icon = AniMixIcons.Clock)
                        problem?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt("Начать с серии", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
                            if (dubbing != null && from != null) Chip("Добавить $from", false, { prepare(false) }, icon = AniMixIcons.Play)
                        }
                        Spacer(Modifier.height(6.dp))
                        Txt("Можно собрать очередь из разных тайтлов. Порядок серий сохраняется.", AniMixTheme.type.micro)
                    }
                }
            }
            val episodes = dubbing?.episodes.orEmpty()
            items(episodes.drop(page * 60).take(60), key = { "episode:${dubbing?.key}:${it.number}" }) { episode ->
                EpisodeCardSurface(episode.number == from, { from = episode.number }, Modifier.height(48.dp), enabled = !busy) {
                    Txt(episode.number, AniMixTheme.type.label, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (episodes.size > 60) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) Chip("Назад", false, { page-- }) else Spacer(Modifier.width(1.dp))
                    Txt("${page * 60 + 1}–${minOf((page + 1) * 60, episodes.size)} из ${episodes.size}", AniMixTheme.type.micro)
                    if ((page + 1) * 60 < episodes.size) Chip("Далее", false, { page++ }) else Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}

@Composable
private fun FlowPlanSummary(state: FlowState) {
    val duration = state.queue.sumOf { flowDuration(it, state.options) }
    if (state.queue.isEmpty()) {
        Txt("${state.options.minutes} минут для аниме", AniMixTheme.type.heading)
        Txt("Выберите тайтл — AniMix подберёт следующие серии по порядку.", AniMixTheme.type.caption, modifier = Modifier.padding(top = 6.dp))
        return
    }
    val approximate = if (state.queue.any { it.estimated }) "≈ " else ""
    val now by produceState(nowMillis()) {
        while (isActive) { delay(60_000L - nowMillis().mod(60_000L)); value = nowMillis() }
    }
    Txt("${state.queue.size} ${episodesWord(state.queue.size)} · $approximate${flowLength(duration)}", AniMixTheme.type.heading)
    Spacer(Modifier.height(6.dp))
    val finish = now + minOf(duration, state.options.limitMs)
    Txt("${if (approximate.isNotEmpty()) "Около" else "Завершим в"} ${flowFinishLabel(finish)}", AniMixTheme.type.bodyStrong,
        color = AniMixTheme.colors.accentText)
    Spacer(Modifier.height(12.dp))
    FlowTimeline(state.queue, state.options)
    Spacer(Modifier.height(8.dp))
    Txt(if (duration > state.options.limitMs) "Очередь длиннее лимита. В ${flowFinishLabel(now + state.options.limitMs)} плеер остановится, даже посередине серии."
        else "Запас ${flowLength(state.options.limitMs - duration)}. ${if (approximate.isNotEmpty()) "Прогноз уточнится по реальной длине видео." else "Сессия закончится раньше лимита, когда завершится очередь."}", AniMixTheme.type.micro)
}

@Composable
private fun FlowTimeline(queue: List<FlowEpisode>, options: FlowOptions) {
    val colors = AniMixTheme.colors
    Canvas(Modifier.fillMaxWidth().height(6.dp).clip(AniMixTheme.shapes.pill)) {
        drawRect(colors.outline)
        var left = 0f
        queue.forEachIndexed { index, item ->
            val width = size.width * flowDuration(item, options).toFloat() / options.limitMs
            drawRect(colors.accent.copy(alpha = if (index % 2 == 0) 1f else 0.55f), Offset(left, 0f), Size((width - 2.dp.toPx()).coerceAtLeast(0f), size.height))
            left += width
        }
    }
}

@Composable
private fun FlowQueueRow(item: FlowEpisode, options: FlowOptions, status: String? = null,
    onRemove: (() -> Unit)? = null, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    EpisodeCardSurface(selected = status == "Сейчас", onClick = null) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(item.anime.posterUrl, Modifier.size(width = 40.dp, height = 56.dp), shape = AniMixTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(item.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                    Txt("Серия ${item.episode.number} · ${item.studio}", AniMixTheme.type.micro, maxLines = 2)
                    Txt(listOfNotNull(status, "${if (item.estimated) "≈ " else ""}${flowLength(flowDuration(item, options))}",
                        "Скачано".takeIf { item.downloadId != null }).joinToString(" · "), AniMixTheme.type.micro)
                }
            }
            if (onRemove != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (onUp != null) Chip("Выше", false, onUp)
                    if (onDown != null) { Spacer(Modifier.width(8.dp)); Chip("Ниже", false, onDown) }
                    Spacer(Modifier.width(8.dp))
                    IconCircle(AniMixIcons.Trash, onRemove, contentDescription = "Убрать серию ${item.episode.number} из сессии")
                }
            }
        }
    }
}

@Composable
private fun FlowSessionScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues, state: FlowState) {
    val item = state.current ?: return
    val downloads by graph.downloads.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val offline = downloads.firstOrNull { it.id == item.downloadId }
    val uri = offline?.let { graph.downloads.offlineUri(it) }
    val stream = rememberLoad(item.key to uri) { force ->
        if (uri != null) ResolvedStream(mapOf(offline.quality to uri), emptyMap()) else graph.kodik.resolve(item.episode.iframeUrl, force)
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { graph.watchFlow.pause() } }
    FlowColumn(padding) {
        PageHeader("Watch flow", { navigator.back() })
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Осталось ${flowCountdown(state.remainingMs)}", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
            Txt("Лимит ${state.deadlineAt?.let(::flowFinishLabel)}", AniMixTheme.type.micro)
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(1f - state.remainingMs.toFloat() / state.options.limitMs)
        Spacer(Modifier.height(8.dp))
        val content = state.remainingContentMs
        Txt("${if (state.estimated) "Примерно" else "По очереди"} до ${flowFinishLabel(nowMillis() + minOf(content, state.remainingMs))}. " +
            if (content > state.remainingMs) "На лимите остановим воспроизведение." else "Паузы сдвигают прогноз, но не лимит.", AniMixTheme.type.caption)
        Spacer(Modifier.height(16.dp))
        when (val loaded = stream.value) {
            Load.Loading -> Shimmer(Modifier.fillMaxWidth().aspectRatio(16f / 9f), AniMixTheme.shapes.large)
            is Load.Failed -> MessageState("Не удалось открыть видео", message = loaded.error.userMessage(), icon = AniMixIcons.Refresh,
                actionLabel = "Повторить", onAction = stream::refresh)
            is Load.Ready -> key(item.key) {
                val plan = flowSkipPlan(item)
                AniMixPlayer(loaded.value, item.episode.copy(opening = plan.opening, ending = plan.ending),
                    settings.copy(skipOpenings = state.options.skipOpenings, skipEndings = state.options.skipEndings,
                        autoNextAsked = true, autoNextEpisode = false),
                    title = "${item.anime.title} · Серия ${item.episode.number}",
                    subtitle = "Watch flow · ${item.studio} · осталось ${flowCountdown(state.remainingMs)}",
                    skips = { duration ->
                        val fetched = graph.skips.plan(item.anime, item.episode, duration)
                        flowSkipPlan(item.copy(episode = item.episode.copy(opening = fetched.opening, ending = fetched.ending)),
                            duration?.times(1000)?.toLong() ?: item.durationMs)
                    },
                    initialPositionMs = state.positionMs,
                    onProgress = { graph.library.saveEpisodePosition(item.anime, item.episode.number, it) },
                    onCompleted = { graph.library.saveEpisodePosition(item.anime, item.episode.number, 0) },
                    nextLabel = state.queue.getOrNull(state.index + 1)?.let { "Далее" },
                    onNext = state.queue.getOrNull(state.index + 1)?.let { { graph.watchFlow.next() } },
                    onRefresh = stream::refresh, playbackAllowed = state.phase == FlowPhase.Running,
                    onPlayerBound = graph.watchFlow::bind,
                    onPlaybackRequest = graph.watchFlow::resume,
                    onSnapshot = { snapshot, skips -> graph.watchFlow.snapshot(item.key, snapshot, skips) },
                    onEpisodeEnded = { graph.watchFlow.completed(item.key) },
                    onPlayback = { graph.library.recordPlayback(item.anime, item.episode.number, it.credit, it.durationMs, it.opening, it.ending, it.reachedEnd) },
                    onQualityChange = { quality -> graph.settings.update { it.copy(preferredQuality = quality) } },
                    fullscreen = fullscreen, onFullscreenChange = { fullscreen = it },
                    onFillScreenChange = { value -> graph.settings.update { it.copy(fillScreen = value) } },
                    maxStartQuality = graph.connection.playbackCap(),
                    onApproachingEnd = {
                        val next = state.queue.getOrNull(state.index + 1)
                        if (graph.connection.prefetch && state.options.autoNext && next != null && next.downloadId == null)
                            graph.scope.launch { runCatching { graph.kodik.resolve(next.episode.iframeUrl) } }
                    })
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (state.awaitingNext) "Следующая серия" else if (state.phase == FlowPhase.Paused) "Продолжить" else "Пауза сессии", false,
                { if (state.phase == FlowPhase.Paused) graph.watchFlow.resume() else graph.watchFlow.pause() }, icon = if (state.phase == FlowPhase.Paused) AniMixIcons.Play else AniMixIcons.Pause)
            Chip("Завершить", false, graph.watchFlow::stop, icon = AniMixIcons.Close)
        }
        Spacer(Modifier.height(24.dp))
        Txt("Очередь · ${state.index + 1} из ${state.queue.size}", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        state.queue.forEachIndexed { index, queued ->
            FlowQueueRow(queued, state.options, status = when {
                queued.key in state.finishedKeys -> "Завершена"
                index < state.index -> "Пропущена"
                index == state.index -> "Сейчас"
                else -> "Далее"
            })
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FlowFinishedScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues, state: FlowState) {
    FlowColumn(padding) {
        PageHeader("Watch flow", { navigator.back() })
        Spacer(Modifier.height(32.dp))
        Txt("Сессия завершена", AniMixTheme.type.title)
        Spacer(Modifier.height(12.dp))
        Txt(when (state.finish) {
            FlowFinish.TimeLimit -> "Время вышло. Воспроизведение остановлено, позиция сохранена."
            FlowFinish.QueueCompleted -> "Все серии в очереди закончились."
            FlowFinish.NextDoesNotFit -> "Следующая серия целиком не помещается в оставшееся время. Сохранили её для новой сессии."
            else -> "Воспроизведение остановлено. Незавершённые серии останутся в очереди."
        }, AniMixTheme.type.body)
        Spacer(Modifier.height(24.dp))
        Txt("Завершено в сессии: ${state.finishedKeys.size} ${episodesWord(state.finishedKeys.size)}", AniMixTheme.type.heading)
        Spacer(Modifier.height(8.dp))
        Txt("Достижения учитываются отдельно, по подтверждённому просмотру в плеере.", AniMixTheme.type.micro)
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Подготовить новую сессию", graph.watchFlow::reset, Modifier.fillMaxWidth(), icon = AniMixIcons.Clock)
    }
}

@Composable
private fun FlowColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(padding).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = 24.dp), content = content)
    }
}

internal fun flowLength(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    val minutes = seconds / 60
    return if (seconds % 60 == 0L) "$minutes мин" else "$minutes мин ${seconds % 60} с"
}

private fun flowCountdown(ms: Long): String = ((ms.coerceAtLeast(0) + 999) / 1000).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
private fun flowFinishLabel(time: Long): String = flowTimeLabel(time) + if (localDayIndex(time) > localDayIndex()) " завтра" else ""
