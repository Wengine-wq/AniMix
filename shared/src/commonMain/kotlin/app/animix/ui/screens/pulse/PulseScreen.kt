package app.animix.ui.screens.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import app.animix.model.NewsItem
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.dayToDate
import app.animix.data.pulse.*
import app.animix.model.Anime
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.*
import app.animix.ui.screens.profile.ScreenColumn
import app.animix.ui.theme.AniMixTheme

/** The quiet entry on the home screen: the week in one line, one tap to the digest. */
@Composable
fun PulseEntry(graph: AppGraph, onRead: () -> Unit) {
    val colors = AniMixTheme.colors
    val library by graph.library.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val week = remember(library) { digestFrom(library).week }
    // Read once, the issue leaves the home screen until Monday; it stays in the profile menu.
    if (settings.pulseReadWeek == week.start.toString()) return
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxWidth().pressScale(interaction)
        .clip(AniMixTheme.shapes.large).background(colors.accentSoft).liquidSheen(0.6f).rim(AniMixTheme.shapes.large, accent = true)
        .clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onRead)
        .focusOutline(interaction, AniMixTheme.shapes.large).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AniMixIcons.Pulse, null, Modifier.size(24.dp), tint = colors.accentText)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("AniPulse · ваша аниме-неделя", AniMixTheme.type.subheading, maxLines = 1)
            Txt(if (week.minutes > 0) "${minutesLabel(week.minutes)} и ${week.episodes} ${episodesWord(week.episodes)} на этой неделе"
                else "Главные новости недели и ваши новые серии", AniMixTheme.type.caption, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.ChevronRight, "Открыть AniPulse", Modifier.size(18.dp), tint = colors.accentText)
    }
}

/**
 * AniPulse — an issue to read, not a set of blocks: an opening paragraph about the week, the main story
 * with its picture, a few more stories told in two or three sentences, then what waits in your lists and
 * what to watch, and the long view last. Written on the device from the sources' own words and your
 * lists; no AI and nothing about you leaves the device.
 */
@Composable
fun PulseScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val loaded = rememberLoad(Unit) { graph.pulse.build() }
    // The counting part is ready at once; the catalog and the news fill in the stories when they answer.
    val digest = (loaded.value as? Load.Ready)?.value ?: remember(library) { digestFrom(library) }
    val loading = loaded.value is Load.Loading
    LaunchedEffect(digest.week.start) {
        val week = digest.week.start.toString()
        if (graph.settings.state.value.pulseReadWeek != week) graph.settings.update { it.copy(pulseReadWeek = week) }
    }
    val mine = library.entries.keys
    val issue = remember(digest, mine) { issueFrom(digest, mine, app.animix.core.TrustedClock.now()) }
    ScreenColumn(padding) {
        PageHeader("AniPulse", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        Masthead(digest, issue, loading)

        // The stories: one main story in full, a few more in short, the whole feed on request.
        if (issue.lead != null || loading) {
            Section("Главное за неделю", AniMixIcons.Sparkle)
            val lead = issue.lead
            if (lead == null) LoadingIndicator(Modifier.size(22.dp)) else LeadStory(lead, navigator)
        }
        if (issue.stories.isNotEmpty()) {
            Section("Ещё о неделе", AniMixIcons.Layers)
            Panel {
                issue.stories.forEachIndexed { index, story ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(AniMixTheme.colors.outline))
                    StoryRow(story, navigator)
                }
            }
        }
        if (issue.rest.isNotEmpty()) RestOfFeed(issue.rest, mine, navigator)

        // Then the viewer's own week: what is waiting, what was watched, what to watch next.
        if (digest.fresh.isNotEmpty()) {
            Section("Новые серии в ваших списках", AniMixIcons.Bell)
            Panel {
                digest.fresh.take(6).forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(AniMixTheme.colors.outline))
                    FreshRow(item) { navigator.push(Route.Watch(item.anime, (item.watched + 1).toString())) }
                }
            }
        }
        if (digest.almostDone.isNotEmpty()) {
            Section("Почти досмотрели", AniMixIcons.Check)
            Shelf(digest.almostDone.map { it.entry.anime to if (it.entry.anime.kind == app.animix.model.AnimeKind.Movie) "Фильм не досмотрен" else "Осталось ${it.left} ${episodesWord(it.left)}" }, navigator)
        }
        if (digest.watched.isNotEmpty()) {
            Section("Вы смотрели", AniMixIcons.Play)
            Shelf(digest.watched.map { it.anime to if (it.finished) "Досмотрено" else "+${it.episodes} ${episodesWord(it.episodes)}" }, navigator)
        }
        if (digest.onHold.isNotEmpty()) {
            Section("Ждут на паузе", AniMixIcons.Pause)
            Shelf(digest.onHold.map { it.anime to "Отложено" }, navigator)
        }
        if (digest.ongoingForYou.isNotEmpty()) {
            Section(digest.topGenre?.let { "Онгоинги для вас · $it" } ?: "Онгоинги для вас", AniMixIcons.Flame)
            Shelf(digest.ongoingForYou.map { it to it.score?.takeIf { s -> s > 0 }?.let { s -> "★ ${formatScore(s)}" } }, navigator)
        }
        if (digest.picks.isNotEmpty()) {
            Section("Подборка недели", AniMixIcons.Star)
            Shelf(digest.picks.map { it to it.year?.toString() }, navigator)
        }
        Section("Неделя за неделей", AniMixIcons.Calendar)
        WeeksChart(digest.weeks)
        if (loaded.value is Load.Failed) Txt("Каталог не ответил: новости, новые серии и подборки появятся при подключении.", AniMixTheme.type.caption,
            modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(28.dp))
        Txt("Выпуск собирается на устройстве. Истории — короткий пересказ начала публикаций Shikimori, YummyAnime и GoHa.ru со ссылкой на источник, " +
            "остальное — по вашим спискам, просмотру и каталогу Shikimori. Без ИИ и без отправки ваших данных.", AniMixTheme.type.micro)
    }
}

/**
 * The top of the issue: the week's dates, the mascot greeting it, the opening paragraph and the week
 * in one quiet line of numbers. The paragraph is the heart of the digest; numbers only support it.
 */
@Composable
private fun Masthead(digest: WeeklyDigest, issue: PulseIssue, loading: Boolean) {
    val colors = AniMixTheme.colors
    val week = digest.week
    Column(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large).background(colors.accentSoft).rim(AniMixTheme.shapes.large, accent = true)
        .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Выпуск · ${weekLabel(week.start)}", AniMixTheme.type.label, color = colors.accentText)
                Spacer(Modifier.height(6.dp))
                Txt("Ваша аниме-неделя", AniMixTheme.type.title)
            }
            // The mascot greets the issue: small, at the edge, never over the text or the numbers.
            AniMixMascot(Modifier.padding(start = 8.dp).size(width = 64.dp, height = 96.dp),
                pose = if (week.minutes > 0) MascotPose.Happy else MascotPose.Welcome)
        }
        Spacer(Modifier.height(10.dp))
        Txt(issue.intro, AniMixTheme.type.body, color = colors.textPrimary, modifier = Modifier.padding(end = 8.dp))
        if (loading && issue.lead == null) Txt("Собираем новости недели…", AniMixTheme.type.caption, modifier = Modifier.padding(top = 8.dp))
        // The week in one line: time, episodes, days, and the change once there is something to compare.
        val change = digest.change?.takeIf { week.minutes > 0 }
        val facts = listOfNotNull(
            minutesLabel(week.minutes).takeIf { week.minutes > 0 },
            "${week.episodes} ${episodesWord(week.episodes)}".takeIf { week.episodes > 0 },
            "${week.activeDays} ${daysWord(week.activeDays)} с просмотром".takeIf { week.activeDays > 0 },
            change?.let { if (it >= 0) "+$it% к прошлой" else "$it% к прошлой" },
            "${digest.streak} ${daysWord(digest.streak)} подряд".takeIf { digest.streak > 1 },
        )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.accent.copy(alpha = 0.18f)))
            Spacer(Modifier.height(12.dp))
            Txt(facts.joinToString(" · "), AniMixTheme.type.label, color = colors.textPrimary, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** The main story in full: its picture, what and where, the headline, two or three sentences and the ways to read on. */
@Composable
private fun LeadStory(story: PulseStory, navigator: Navigator) {
    val item = story.item
    val poster = item.anime?.posterUrl.takeIf { item.imageUrl == null }
    Column(Modifier.fillMaxWidth()) {
        if (item.imageUrl != null) {
            RemoteImage(item.imageUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = AniMixTheme.shapes.large)
            Spacer(Modifier.height(14.dp))
        }
        // News without a picture of its own shows the poster of its anime beside the text: a portrait
        // poster is never stretched into a wide frame, and the story is never a bare block of text.
        Row(verticalAlignment = Alignment.Top) {
            if (poster != null) {
                RemoteImage(poster, Modifier.width(96.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.medium)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                StoryMeta(story)
                Spacer(Modifier.height(4.dp))
                Txt(item.title, AniMixTheme.type.heading)
                if (story.summary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Txt(story.summary, AniMixTheme.type.body, color = AniMixTheme.colors.textSecondary, maxLines = if (poster != null) 5 else Int.MAX_VALUE)
                }
            }
        }
        NewsActions(item, navigator)
    }
}

/** A shorter story: what and where, the headline and its summary; the picture at the side. A tap shows the ways to read on. */
@Composable
private fun StoryRow(story: PulseStory, navigator: Navigator) {
    var open by remember(story.item.id) { mutableStateOf(false) }
    val item = story.item
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                StoryMeta(story)
                Spacer(Modifier.height(4.dp))
                Txt(item.title, AniMixTheme.type.bodyStrong, maxLines = if (open) Int.MAX_VALUE else 3)
                if (story.summary.isNotEmpty()) Txt(story.summary, AniMixTheme.type.caption, maxLines = if (open) Int.MAX_VALUE else 3,
                    modifier = Modifier.padding(top = 4.dp))
            }
            if (item.imageUrl != null) {
                Spacer(Modifier.width(12.dp))
                RemoteImage(item.imageUrl, Modifier.size(width = 88.dp, height = 66.dp), shape = AniMixTheme.shapes.small)
            } else item.anime?.posterUrl?.let { poster ->
                Spacer(Modifier.width(12.dp))
                RemoteImage(poster, Modifier.width(56.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
            }
        }
        if (open) NewsActions(item, navigator)
    }
}

/** «Анонс · Из ваших списков · 5 нояб. · GoHa.ru» — the accent only when the story is about your titles. */
@Composable
private fun StoryMeta(story: PulseStory) {
    val item = story.item
    Txt(listOfNotNull(item.kind, "Из ваших списков".takeIf { story.mine }, newsDate(item.createdAt), item.source).joinToString(" · "),
        AniMixTheme.type.micro, color = if (story.mine) AniMixTheme.colors.accentText else AniMixTheme.colors.textSecondary)
}

/**
 * The rest of the week's feed, folded: one link, and the list by kind when opened. A row opens in place
 * to the whole text, with the anime page and the source one tap away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestOfFeed(news: List<NewsItem>, mine: Set<String>, navigator: Navigator) {
    var kind by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var all by remember { mutableStateOf(false) }
    Txt(if (all) "Свернуть остальные новости" else "Остальные новости недели · ${news.size}", AniMixTheme.type.label, color = AniMixTheme.colors.accent,
        modifier = Modifier.padding(top = 12.dp).clip(AniMixTheme.shapes.pill).clickable(role = Role.Button) { all = !all; kind = null }
            .padding(horizontal = 4.dp, vertical = 10.dp))
    if (!all) return
    val kinds = listOf("Анонс", "Трейлер", "Премьера", "Новость").filter { k -> news.any { it.kind == k } }
    if (kinds.size > 1) {
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Всё · ${news.size}", kind == null, { kind = null })
            kinds.forEach { k -> Chip(plural(k), kind == k, { kind = k }) }
        }
        Spacer(Modifier.height(12.dp))
    }
    Panel {
        news.filter { kind == null || it.kind == kind }.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 88.dp).height(1.dp).background(AniMixTheme.colors.outline))
            NewsRow(item, item.anime?.key in mine, expanded == item.id, { expanded = if (expanded == item.id) null else item.id }, navigator)
        }
    }
}

private fun plural(kind: String) = when (kind) { "Анонс" -> "Анонсы"; "Трейлер" -> "Трейлеры"; "Премьера" -> "Премьеры"; else -> "Новости" }

@Composable
private fun NewsRow(item: NewsItem, mine: Boolean, open: Boolean, onToggle: () -> Unit, navigator: Navigator) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Without a picture the kind of news stands in for it, never an empty grey square.
            if (item.imageUrl ?: item.anime?.posterUrl != null) RemoteImage(item.imageUrl ?: item.anime?.posterUrl, Modifier.size(64.dp), shape = AniMixTheme.shapes.small)
            else Box(Modifier.size(64.dp).clip(AniMixTheme.shapes.small).background(AniMixTheme.colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(when (item.kind) { "Анонс" -> AniMixIcons.Sparkle; "Трейлер" -> AniMixIcons.Play; "Премьера" -> AniMixIcons.Calendar; else -> AniMixIcons.Info },
                    null, Modifier.size(24.dp), tint = AniMixTheme.colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(listOfNotNull(item.kind, "Из ваших списков".takeIf { mine }, newsDate(item.createdAt), item.source).joinToString(" · "),
                    AniMixTheme.type.micro, color = if (mine) AniMixTheme.colors.accentText else AniMixTheme.colors.textSecondary)
                Spacer(Modifier.height(2.dp))
                Txt(item.title, AniMixTheme.type.bodyStrong, maxLines = if (open) Int.MAX_VALUE else 2)
                Txt(item.text, AniMixTheme.type.caption, maxLines = if (open) Int.MAX_VALUE else 2, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (open) Box(Modifier.padding(start = 76.dp)) { NewsActions(item, navigator) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsActions(item: NewsItem, navigator: Navigator) {
    val uri = LocalUriHandler.current
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.anime?.let { anime -> Chip("Страница аниме", false, { navigator.push(Route.Detail(anime)) }, icon = AniMixIcons.Play) }
        Chip("Читать на ${item.source}", false, { runCatching { uri.openUri(item.url) } }, icon = AniMixIcons.Link)
    }
}

/** «1 нояб.» from Shikimori's ISO time. */
private fun newsDate(iso: String): String? {
    val month = iso.substring(5, 7).toIntOrNull() ?: return null
    val day = iso.substring(8, 10).toIntOrNull() ?: return null
    return "$day ${Months.getOrNull(month - 1) ?: return null}"
}

/** Eight weeks as bars, the current one in the accent; labels under the first and the last. */
@Composable
private fun WeeksChart(weeks: List<WeekStats>) {
    val colors = AniMixTheme.colors
    val max = weeks.maxOf { it.minutes }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        weeks.forEachIndexed { index, week ->
            Box(Modifier.weight(1f).fillMaxHeight((week.minutes.toFloat() / max).coerceAtLeast(0.05f)).clip(AniMixTheme.shapes.small)
                .background(when {
                    index == weeks.lastIndex -> colors.accent
                    week.minutes > 0 -> colors.accent.copy(alpha = 0.35f)
                    else -> colors.outline
                }))
        }
    }
    Row(Modifier.padding(top = 8.dp)) {
        Txt("8 недель назад", AniMixTheme.type.micro, modifier = Modifier.weight(1f))
        Txt("Эта неделя", AniMixTheme.type.micro)
    }
}

@Composable
private fun FreshRow(item: FreshEpisodes, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(item.anime.posterUrl, Modifier.width(44.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(item.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
            Txt(listOfNotNull(
                if (item.unseen == 1) "Новая серия ${item.available}" else "${item.unseen} ${episodesWord(item.unseen)} ждут",
                item.nextEpisodeAt?.let(::nextLabel),
            ).joinToString(" · "), AniMixTheme.type.caption, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.Play, "Смотреть", Modifier.size(18.dp), tint = AniMixTheme.colors.accent)
    }
}

@Composable
private fun Section(title: String, icon: ImageVector) {
    Spacer(Modifier.height(32.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = AniMixTheme.colors.accentText)
        Spacer(Modifier.width(10.dp))
        Txt(title, AniMixTheme.type.heading)
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Shelf(titles: List<Pair<Anime, String?>>, navigator: Navigator) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.first.key }) { (anime, caption) ->
            AnimeCard(anime, { navigator.push(Route.Detail(anime)) }, Modifier.width(128.dp), caption = caption, mark = false)
        }
    }
}

private val Months = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")

/** «27 окт. – 2 нояб.» */
private fun weekLabel(start: Long): String {
    val a = dayToDate(start); val b = dayToDate(start + 6)
    return "${a.day} ${Months[a.month - 1]} – ${b.day} ${Months[b.month - 1]}"
}

/** «следующая 5 нояб.» from Shikimori's ISO date of the next episode. */
private fun nextLabel(iso: String): String? {
    val month = iso.substring(5, 7).toIntOrNull() ?: return null
    val day = iso.substring(8, 10).toIntOrNull() ?: return null
    return "следующая $day ${Months.getOrNull(month - 1) ?: return null}"
}

private fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

private fun daysWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"; n % 10 == 1 -> "день"; n % 10 in 2..4 -> "дня"; else -> "дней"
}
