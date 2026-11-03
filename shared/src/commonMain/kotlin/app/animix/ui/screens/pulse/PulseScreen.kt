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
    val week = remember(library) { digestFrom(library).week }
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxWidth()
        .clip(AniMixTheme.shapes.large).background(colors.accentSoft)
        .clickable(role = Role.Button, onClick = onRead).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AniMixIcons.Pulse, null, Modifier.size(24.dp), tint = colors.accentText)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("AniPulse · ваша аниме-неделя", AniMixTheme.type.subheading, maxLines = 1)
            Txt(if (week.minutes > 0) "${minutesLabel(week.minutes)} и ${week.episodes} ${episodesWord(week.episodes)} на этой неделе"
                else "Новые серии, почти досмотренное и подборка недели", AniMixTheme.type.caption, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.ChevronRight, "Открыть AniPulse", Modifier.size(18.dp), tint = colors.accentText)
    }
}

/**
 * AniPulse: the week in numbers, the week-by-week chart, new episodes of «Смотрю», what is almost
 * finished, what waits on hold, ongoing titles in your genres and the week's picks. Counted on the
 * device from your lists and the catalog; no AI and nothing written for you by a service.
 */
@Composable
fun PulseScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val loaded = rememberLoad(Unit) { graph.pulse.build() }
    // The counting part is ready at once; the catalog fills in new episodes and shelves when it answers.
    val digest = (loaded.value as? Load.Ready)?.value ?: remember(library) { digestFrom(library) }
    val loading = loaded.value is Load.Loading
    ScreenColumn(padding) {
        PageHeader("AniPulse", { navigator.back() }, Modifier.padding(top = 12.dp))
        Txt(weekLabel(digest.week.start), AniMixTheme.type.caption, modifier = Modifier.padding(top = 4.dp, start = 56.dp))
        Spacer(Modifier.height(20.dp))
        WeekHero(digest)
        // The magazine part first: what happened in anime this week, about the viewer's titles first.
        if (digest.news.isNotEmpty() || loading) {
            Section("Главное за неделю", AniMixIcons.Sparkle)
            if (digest.news.isEmpty()) LoadingIndicator(Modifier.size(22.dp)) else NewsBlock(digest.news, library.entries.keys, navigator)
        }
        Spacer(Modifier.height(32.dp))
        Txt("Неделя за неделей", AniMixTheme.type.heading)
        Spacer(Modifier.height(14.dp))
        WeeksChart(digest.weeks)

        if (digest.fresh.isNotEmpty() || loading) {
            Section("Новые серии", AniMixIcons.Bell)
            if (digest.fresh.isEmpty()) LoadingIndicator(Modifier.size(22.dp))
            else Panel {
                digest.fresh.take(6).forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(1.dp).background(AniMixTheme.colors.outline))
                    FreshRow(item) { navigator.push(Route.Watch(item.anime, (item.watched + 1).toString())) }
                }
            }
        }
        if (digest.almostDone.isNotEmpty()) {
            Section("Почти досмотрели", AniMixIcons.Check)
            Shelf(digest.almostDone.map { it.entry.anime to "Осталось ${it.left} ${episodesWord(it.left)}" }, navigator)
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
            Section("Подборка недели", AniMixIcons.Sparkle)
            Shelf(digest.picks.map { it to it.year?.toString() }, navigator)
        }
        if (loaded.value is Load.Failed) Txt("Каталог не ответил: новые серии и подборки появятся при подключении.", AniMixTheme.type.caption,
            modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(28.dp))
        Txt("AniPulse собирается на устройстве по вашим спискам, просмотру, каталогу и новостям Shikimori. Без ИИ и без отправки ваших данных.",
            AniMixTheme.type.micro)
    }
}

/**
 * The week's news: chips by kind, the first item as a large card with its picture, the rest as rows.
 * A row opens in place to the whole text, with the anime page and the source one tap away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsBlock(news: List<NewsItem>, mine: Set<String>, navigator: Navigator) {
    var kind by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var all by remember { mutableStateOf(false) }
    val kinds = listOf("Анонс", "Трейлер", "Премьера", "Новость").filter { k -> news.any { it.kind == k } }
    val shown = news.filter { kind == null || it.kind == kind }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Всё · ${news.size}", kind == null, { kind = null })
        kinds.forEach { k -> Chip(plural(k), kind == k, { kind = k }) }
    }
    Spacer(Modifier.height(14.dp))
    val lead = shown.firstOrNull() ?: return
    NewsLead(lead, lead.anime?.key in mine, expanded == lead.id, { expanded = if (expanded == lead.id) null else lead.id }, navigator)
    val rest = shown.drop(1)
    if (rest.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Panel {
            (if (all) rest else rest.take(6)).forEachIndexed { index, item ->
                if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 88.dp).height(1.dp).background(AniMixTheme.colors.outline))
                NewsRow(item, item.anime?.key in mine, expanded == item.id, { expanded = if (expanded == item.id) null else item.id }, navigator)
            }
        }
        if (rest.size > 6) Txt(if (all) "Свернуть" else "Ещё ${rest.size - 6}", AniMixTheme.type.label, color = AniMixTheme.colors.accent,
            modifier = Modifier.padding(top = 8.dp).clip(AniMixTheme.shapes.pill).clickable(role = Role.Button) { all = !all }.padding(horizontal = 8.dp, vertical = 8.dp))
    }
}

private fun plural(kind: String) = when (kind) { "Анонс" -> "Анонсы"; "Трейлер" -> "Трейлеры"; "Премьера" -> "Премьеры"; else -> "Новости" }

/** The lead story: its picture under a scrim with the kind and title on it, the beginning of the text below. */
@Composable
private fun NewsLead(item: NewsItem, mine: Boolean, open: Boolean, onToggle: () -> Unit, navigator: Navigator) {
    val colors = AniMixTheme.colors
    Column(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large).background(colors.surface.copy(alpha = 0.72f))
        .rim(AniMixTheme.shapes.large, accent = false).clickable(role = Role.Button, onClick = onToggle)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f)) {
            RemoteImage(item.imageUrl, Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape)
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color(0xCC0B0B0E)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Txt(listOfNotNull(item.kind, "Из ваших списков".takeIf { mine }, newsDate(item.createdAt)).joinToString(" · "),
                    AniMixTheme.type.micro, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(4.dp))
                Txt(item.title, AniMixTheme.type.subheading, color = androidx.compose.ui.graphics.Color.White, maxLines = 3)
            }
        }
        Column(Modifier.padding(16.dp)) {
            Txt(item.text, AniMixTheme.type.caption, maxLines = if (open) Int.MAX_VALUE else 3)
            if (open) NewsActions(item, navigator)
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, mine: Boolean, open: Boolean, onToggle: () -> Unit, navigator: Navigator) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            // Without a picture the kind of news stands in for it, never an empty grey square.
            if (item.imageUrl != null) RemoteImage(item.imageUrl, Modifier.size(64.dp), shape = AniMixTheme.shapes.small)
            else Box(Modifier.size(64.dp).clip(AniMixTheme.shapes.small).background(AniMixTheme.colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(when (item.kind) { "Анонс" -> AniMixIcons.Sparkle; "Трейлер" -> AniMixIcons.Play; "Премьера" -> AniMixIcons.Calendar; else -> AniMixIcons.Info },
                    null, Modifier.size(24.dp), tint = AniMixTheme.colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(listOfNotNull(item.kind, "Из ваших списков".takeIf { mine }, newsDate(item.createdAt)).joinToString(" · "),
                    AniMixTheme.type.micro, color = if (mine) AniMixTheme.colors.accentText else AniMixTheme.colors.textSecondary)
                Spacer(Modifier.height(2.dp))
                Txt(item.title, AniMixTheme.type.bodyStrong, maxLines = if (open) Int.MAX_VALUE else 2)
                Txt(item.text, AniMixTheme.type.caption, maxLines = if (open) Int.MAX_VALUE else 2, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (open) Box(Modifier.padding(start = 76.dp)) { NewsActions(item, navigator) }
    }
}

@Composable
private fun NewsActions(item: NewsItem, navigator: Navigator) {
    val uri = LocalUriHandler.current
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.anime?.let { anime -> Chip("Страница аниме", false, { navigator.push(Route.Detail(anime)) }, icon = AniMixIcons.Play) }
        Chip("Источник", false, { runCatching { uri.openUri(item.url) } }, icon = AniMixIcons.Link)
    }
}

/** «1 нояб.» from Shikimori's ISO time. */
private fun newsDate(iso: String): String? {
    val month = iso.substring(5, 7).toIntOrNull() ?: return null
    val day = iso.substring(8, 10).toIntOrNull() ?: return null
    return "$day ${Months.getOrNull(month - 1) ?: return null}"
}

/** The week in large type with the mascot greeting it, the change against last week in words. */
@Composable
private fun WeekHero(digest: WeeklyDigest) {
    val colors = AniMixTheme.colors
    val week = digest.week
    Box(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large).background(colors.accentSoft).rim(AniMixTheme.shapes.large, accent = true)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Ваша неделя", AniMixTheme.type.label, color = colors.accentText)
                Spacer(Modifier.height(6.dp))
                Txt(if (week.minutes > 0) minutesLabel(week.minutes) else "Пока тихо", AniMixTheme.type.display, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(listOfNotNull(
                    "${week.episodes} ${episodesWord(week.episodes)} досмотрено",
                    "${week.activeDays} ${daysWord(week.activeDays)} с просмотром".takeIf { week.activeDays > 0 },
                ).joinToString(" · "), AniMixTheme.type.caption)
                // A change is shown once this week has something to compare; a quiet Monday is not «−100%».
                val change = digest.change?.takeIf { week.minutes > 0 }
                val streak = digest.streak
                if (change != null || streak > 1) {
                    Spacer(Modifier.height(10.dp))
                    Txt(listOfNotNull(
                        change?.let { if (it >= 0) "+$it% к прошлой неделе" else "$it% к прошлой неделе" },
                        "$streak ${daysWord(streak)} подряд".takeIf { streak > 1 },
                    ).joinToString(" · "), AniMixTheme.type.label, color = colors.textPrimary)
                }
            }
            // The mascot greets the week: small, at the edge, never over the numbers.
            AniMixMascot(Modifier.padding(start = 8.dp, end = 12.dp).size(width = 92.dp, height = 140.dp),
                pose = if (week.minutes > 0) MascotPose.Happy else MascotPose.Welcome)
        }
    }
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
            AnimeCard(anime, { navigator.push(Route.Detail(anime)) }, Modifier.width(128.dp), caption = caption)
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
