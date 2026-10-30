package app.animix.ui.screens.watchflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
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
import app.animix.data.library.isEpisodeWatched
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
import app.animix.ui.theme.LocalUiPerformance
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    // Any catalog title can join the queue: searched ones come first, then «Смотрю», «Отложено» and downloads.
    var searched by remember { mutableStateOf<List<Anime>>(emptyList()) }
    val choices = remember(library.entries, downloads, opened, searched) {
        (listOfNotNull(opened) + searched + library.entries.values.filter { it.status == ListStatus.Watching || it.status == ListStatus.OnHold }
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
    // Open the grid on the page of the starting episode: episode 500 is not on the first page.
    var page by rememberSaveable(anime?.key, dubbing?.key) {
        mutableIntStateOf((dubbing?.episodes?.indexOfFirst { it.number == from } ?: -1).coerceAtLeast(0) / 24)
    }
    var editing by rememberSaveable { mutableStateOf(state.queue.isEmpty()) }
    var optionsOpen by remember { mutableStateOf(false) }
    var chooseTitle by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var minutes by rememberSaveable { mutableStateOf(state.options.minutes.toString()) }
    var gridScrollRequest by remember { mutableIntStateOf(0) }
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
                else { graph.watchFlow.add(items); editing = false; gridScrollRequest++ }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { problem = "Не удалось подготовить очередь. Проверьте соединение и повторите." }
            finally { busy = false }
        }
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(editing, gridScrollRequest) { grid.scrollToItem(0) }
    val performance = LocalUiPerformance.current
    val reorder = rememberReorderableLazyGridState(grid,
        scrollThresholdPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) { from, to ->
        val fromKey = (from.key as? String)?.takeIf { it.startsWith("queue:") }?.removePrefix("queue:")
        val toKey = (to.key as? String)?.takeIf { it.startsWith("queue:") }?.removePrefix("queue:")
        if (!busy && fromKey != null && toKey != null) graph.watchFlow.moveTo(fromKey, toKey)
    }
    if (optionsOpen) AniMixSheet({ optionsOpen = false }, title = "Параметры сессии", subtitle = "Действуют только в Watch flow") {
        ToggleRow("Пропускать опенинги", state.options.skipOpenings,
            { graph.watchFlow.options(state.options.copy(skipOpenings = it)) }, AniMixIcons.SkipAhead, enabled = !busy)
        ToggleRow("Пропускать эндинги", state.options.skipEndings,
            { graph.watchFlow.options(state.options.copy(skipEndings = it)) }, AniMixIcons.SkipAhead, enabled = !busy)
        ToggleRow("Следующая серия автоматически", state.options.autoNext,
            { graph.watchFlow.options(state.options.copy(autoNext = it)) }, AniMixIcons.Next, enabled = !busy)
        Txt("Таймер включает паузы и загрузку. На лимите видео остановится. Пропуски учитываем только по известной разметке.", AniMixTheme.type.caption,
            modifier = Modifier.padding(top = 12.dp))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(GridCells.Adaptive(56.dp), state = grid, modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PageHeader("Watch flow", { navigator.back() }) }
                        IconCircle(AniMixIcons.Sliders, { optionsOpen = true }, contentDescription = "Параметры сессии")
                    }
                    Spacer(Modifier.height(8.dp))
                    Txt("Выберите время и серии — проверите очередь перед запуском.", AniMixTheme.type.caption)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Подбор", editing, { if (!busy) editing = true })
                        Chip("Очередь · ${state.queue.size}", !editing, { if (!busy) editing = false })
                    }
                    Spacer(Modifier.height(24.dp))
                    if (editing) {
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
                    } else Txt("Лимит ${state.options.minutes} мин · изменить в подборе", AniMixTheme.type.caption)
                    Spacer(Modifier.height(20.dp))
                    if (!editing) FlowPlanSummary(state)
                    Spacer(Modifier.height(12.dp))
                }
            }
            items(if (editing) emptyList() else state.queue, key = { "queue:${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
                ReorderableItem(reorder, key = "queue:${item.key}", enabled = !busy,
                    animateItemModifier = if (performance.animations) Modifier.animateItem() else Modifier) { dragging ->
                    FlowQueueRow(item, state.options, dragging = dragging,
                        dragHandle = {
                            Box(Modifier.size(44.dp).draggableHandle(enabled = !busy)
                                .semantics { contentDescription = "Перетащить серию ${item.episode.number} в очереди" },
                                contentAlignment = Alignment.Center) {
                                Icon(AniMixIcons.Grip, null, Modifier.size(20.dp), tint = AniMixTheme.colors.textMuted)
                            }
                        },
                        onRemove = { if (!busy) graph.watchFlow.remove(item.key) },
                        onUp = state.queue.indexOf(item).takeIf { it > 0 }?.let { { if (!busy) graph.watchFlow.move(item.key, -1) } },
                        onDown = state.queue.indexOf(item).takeIf { it < state.queue.lastIndex }?.let { { if (!busy) graph.watchFlow.move(item.key, 1) } })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (!editing) {
                        if (state.queue.isNotEmpty()) {
                            PrimaryButton("Начать просмотр", { graph.watchFlow.start() }, Modifier.fillMaxWidth(), icon = AniMixIcons.Play,
                                enabled = !busy && minutes.toIntOrNull() in 1..360)
                            Spacer(Modifier.height(12.dp))
                        }
                        SecondaryButton("Добавить серии", { editing = true }, Modifier.fillMaxWidth(), icon = AniMixIcons.Search)
                    }
                    if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Что смотрим", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
                        Chip(if (chooseTitle) "Закрыть поиск" else "Найти тайтл", false, { chooseTitle = !chooseTitle }, icon = AniMixIcons.Search)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (chooseTitle || choices.isEmpty()) {
                        FlowTitleSearch(graph) { picked ->
                            searched = (listOf(picked) + searched).distinctBy { it.key }.take(6)
                            animeKey = picked.key; chooseTitle = false; problem = null
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (choices.isEmpty()) {
                        MessageState("Добавьте тайтл в очередь", message = "Найдите аниме выше или выберите его в каталоге.",
                            icon = AniMixIcons.Clock, actionLabel = "Открыть каталог", onAction = { navigator.openLink("animix://catalog") })
                    } else {
                        SelectionList("Тайтл", choices.map { SelectionOption(it.key, it.title, listOfNotNull(it.kind.label, it.year?.toString()).joinToString(" · ")) }, anime?.key, AniMixIcons.Play) {
                            if (!busy) { animeKey = it; problem = null }
                        }
                        Spacer(Modifier.height(12.dp))
                        when (val loaded = sourceState) {
                            Load.Loading -> SelectionPlaceholder()
                            is Load.Failed -> MessageState("Не удалось получить серии", message = loaded.error.userMessage(), icon = AniMixIcons.Refresh,
                                actionLabel = "Повторить", onAction = source::refresh)
                            is Load.Ready -> {
                                if (loaded.value.dubbings.isEmpty()) Txt("Для этой сессии нужен встроенный плеер Kodik или скачанные серии.", AniMixTheme.type.caption)
                                else SelectionList("Озвучка", loaded.value.dubbings.map {
                                    SelectionOption(it.key, it.studio, "${it.episodes.size} ${episodesWord(it.episodes.size)} · ${if (it.offlineOnly) "только скачанные" else "онлайн"}")
                                }, dubbing?.key, AniMixIcons.Mic) { if (!busy) dubbingKey = it }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Txt("Начать с серии", AniMixTheme.type.subheading)
                        Txt("Подбор добавит следующие серии по порядку в свободное время.", AniMixTheme.type.micro, modifier = Modifier.padding(top = 4.dp))
                    }
                    }
                }
            }
            val episodes = if (editing) dubbing?.episodes.orEmpty() else emptyList()
            items(episodes.drop(page * 24).take(24), key = { "episode:${dubbing?.key}:${it.number}" }) { episode ->
                // The same cell as «Смотреть»: watched, started and downloaded episodes are visible while planning.
                val entry = anime?.let { library.entry(it) }
                val saved = downloads.any { it.anime.key == anime?.key && it.dubbingKey == dubbing?.key &&
                    it.episode.number == episode.number && it.status == DownloadStatus.Completed }
                app.animix.ui.screens.detail.EpisodeCell(episode, episode.number == from, entry?.isEpisodeWatched(episode.number) == true,
                    app.animix.ui.screens.detail.watchProgress(entry, episode), saved, enabled = !busy) { from = episode.number }
            }
            if (episodes.size > 24) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) Chip("Назад", false, { page-- }) else Spacer(Modifier.width(1.dp))
                    Txt("${page * 24 + 1}–${minOf((page + 1) * 24, episodes.size)} из ${episodes.size}", AniMixTheme.type.micro)
                    if ((page + 1) * 24 < episodes.size) Chip("Далее", false, { page++ }) else Spacer(Modifier.width(1.dp))
                }
            }
            if (editing && dubbing != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 12.dp)) {
                    PrimaryButton(if (busy) "Подбираем серии…" else "Подобрать серии", { prepare(true) }, Modifier.fillMaxWidth(),
                        icon = AniMixIcons.Clock, enabled = !busy && from != null && minutes.toIntOrNull() in 1..360)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Добавить только серию ${from ?: "—"}", { prepare(false) }, Modifier.fillMaxWidth(),
                        enabled = !busy && from != null && minutes.toIntOrNull() in 1..360)
                    problem?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
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
    onRemove: (() -> Unit)? = null, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null, dragging: Boolean = false) {
    var actionsOpen by remember { mutableStateOf(false) }
    if (actionsOpen) AniMixSheet({ actionsOpen = false }, title = "Серия ${item.episode.number}", subtitle = item.anime.title) {
        onUp?.let { action -> NavRow("Переместить выше", AniMixIcons.ChevronUp, { actionsOpen = false; action() }) }
        onDown?.let { action -> NavRow("Переместить ниже", AniMixIcons.ChevronDown, { actionsOpen = false; action() }) }
        onRemove?.let { action -> NavRow("Убрать из очереди", AniMixIcons.Trash, { actionsOpen = false; action() }) }
    }
    EpisodeCardSurface(selected = status == "Сейчас" || dragging, onClick = null) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle?.invoke()
                RemoteImage(item.anime.posterUrl, Modifier.size(width = 40.dp, height = 56.dp), shape = AniMixTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(item.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                    Txt("Серия ${item.episode.number} · ${item.studio}", AniMixTheme.type.micro, maxLines = 2)
                    Txt(listOfNotNull(status, "${if (item.estimated) "≈ " else ""}${flowLength(flowDuration(item, options))}",
                        "Скачано".takeIf { item.downloadId != null }).joinToString(" · "), AniMixTheme.type.micro)
                }
                if (onRemove != null) IconCircle(AniMixIcons.More, { actionsOpen = true },
                    contentDescription = "Действия с серией ${item.episode.number}")
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
            Txt("До ${state.deadlineAt?.let(::flowFinishLabel)}", AniMixTheme.type.micro)
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(1f - state.remainingMs.toFloat() / state.options.limitMs)
        Spacer(Modifier.height(8.dp))
        Txt(if (state.phase == FlowPhase.Paused) "На паузе · таймер продолжает идти" else "На лимите просмотр остановится автоматически", AniMixTheme.type.caption)
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
                    onPlayerBound = { player ->
                        val flow=graph.watchFlow.bind(player)
                        val social=graph.social.bind(player,item.anime,item.episode.number,item.dubbingKey){graph.settings.state.value.preferredQuality?:"Авто"}
                        val release: () -> Unit = { flow();social() }
                        release
                    },
                    horizon = { app.animix.ui.screens.profile.HorizonButton(graph) },
                    onPlaybackRequest = graph.watchFlow::resume,
                    onSnapshot = { snapshot, skips -> graph.watchFlow.snapshot(item.key, snapshot, skips) },
                    onEpisodeEnded = { graph.watchFlow.completed(item.key) },
                    onPlayback = { graph.library.recordPlayback(item.anime, item.episode.number, it.credit, it.durationMs, it.opening, it.ending, it.reachedEnd,
                        app.animix.data.library.PlaybackContext(item.studio, offline = item.downloadId != null, platform = app.animix.core.Platform.name)) },
                    onSkip = graph.library::recordSkip,
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
        if (state.finish == FlowFinish.QueueCompleted) {
            AniMixMascot(Modifier.size(width = 136.dp, height = 176.dp).align(Alignment.CenterHorizontally), pose = MascotPose.Happy)
            Spacer(Modifier.height(20.dp))
        }
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
        PrimaryButton("Новая сессия", graph.watchFlow::reset, Modifier.fillMaxWidth(), icon = AniMixIcons.Clock)
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

/** Finds any title in the catalog to add to the queue, online. */
@Composable
private fun FlowTitleSearch(graph: AppGraph, onPick: (Anime) -> Unit) {
    val colors = AniMixTheme.colors
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Anime>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        val text = query.trim()
        if (text.length < 2 || searching) return
        searching = true
        scope.launch {
            results = try { graph.anime.catalog(app.animix.data.shikimori.CatalogQuery(search = text, limit = 8)) }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
            searching = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(query, { query = it.take(24) }, singleLine = true,
            textStyle = AniMixTheme.type.body.copy(color = colors.textPrimary), cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
            modifier = Modifier.weight(1f).height(48.dp).clip(AniMixTheme.shapes.medium).background(colors.chip),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Txt("Найти тайтл для очереди", AniMixTheme.type.body, color = colors.textMuted)
                    field()
                }
            })
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Search, ::search, size = 48.dp, contentDescription = "Найти тайтл")
    }
    if (searching) Shimmer(Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp), AniMixTheme.shapes.medium)
    results?.let { found ->
        Spacer(Modifier.height(10.dp))
        if (found.isEmpty()) Txt("Ничего не нашлось", AniMixTheme.type.caption)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            found.take(5).forEach { anime ->
                Row(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).background(colors.chip)
                    .clickable { onPick(anime); results = null; query = "" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(anime.posterUrl, Modifier.width(36.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(anime.title, AniMixTheme.type.bodyStrong, maxLines = 1)
                        Txt(listOfNotNull(anime.kind.label, anime.year?.toString()).joinToString(" · "), AniMixTheme.type.micro)
                    }
                    Icon(AniMixIcons.Check, null, Modifier.size(16.dp), tint = colors.textMuted)
                }
            }
        }
    }
}
