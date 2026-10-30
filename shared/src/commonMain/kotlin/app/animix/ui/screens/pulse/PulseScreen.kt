package app.animix.ui.screens.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.pulse.*
import app.animix.model.AnimeStatus
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.*
import app.animix.ui.theme.AniMixTheme

@Composable
fun PulseEntry(graph: AppGraph, onRead: () -> Unit) {
    val colors = AniMixTheme.colors
    val state by graph.pulse.state.collectAsState()
    LaunchedEffect(Unit) { graph.pulse.load() }
    val ready = graph.pulse.current(state.issues)
    val status = when {
        state.busy -> state.stage.ifBlank { "Собираем выпуск" }
        ready != null -> "Выпуск недели готов" + ready.editorial.articles.size.takeIf { it > 0 }?.let { " · материалов: $it" }.orEmpty()
        else -> "Ваша аниме-неделя"
    }
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxWidth()
        .clip(AniMixTheme.shapes.large).background(colors.accentSoft)
        .clickable(role = Role.Button, onClick = onRead).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AniMixIcons.Pulse, null, Modifier.size(24.dp), tint = colors.accentText)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("AniPulse", AniMixTheme.type.subheading)
            Txt(status, AniMixTheme.type.caption, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.ChevronRight, "Читать AniPulse", Modifier.size(18.dp), tint = colors.accentText)
    }
}

@Composable
fun PulseScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
        // A link (animix://anipulse) can still lead here while the experiment is off.
        Box(Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
            app.animix.ui.components.MessageState("AniPulse временно недоступен",
                message = "Выпуски приостановлены до исправления сервиса. Сохранённый архив не удалён.", icon = AniMixIcons.Pulse)
        }
}

/** Retained archive implementation; no navigation entry or generator can invoke it while blocked. */
@Composable private fun ArchivedPulseScreen(graph:AppGraph,navigator:Navigator,padding:PaddingValues){
    val state by graph.pulse.state.collectAsState()
    val library by graph.library.state.collectAsState()
    val interests = remember(library) { pulseInterests(library) }
    LaunchedEffect(Unit) { graph.pulse.load() }
    var selected by remember { mutableStateOf<Long?>(null) }
    val issue = state.issues.firstOrNull { it.window.id == selected } ?: state.issues.firstOrNull()
    val hasCurrent = graph.pulse.current(state.issues) != null
    val weekPlain = state.issues.firstOrNull { it.window.id == pulseWindow().id && !it.aiEdited }
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    fun open(url: String) { if (safePulseUrl(url)) linkError = runCatching { uri.openUri(url) }.isFailure }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                PageHeader("AniPulse", { navigator.back() })
                Spacer(Modifier.height(16.dp))
                Txt("Ваша аниме-неделя", AniMixTheme.type.heading)
                Spacer(Modifier.height(6.dp))
                Txt("Ранний доступ — бесплатно. В будущем — 50 ₽/месяц.", AniMixTheme.type.micro)
                Spacer(Modifier.height(24.dp))
            }
            if (!state.loaded && state.error == null) item { LoadingIndicator(Modifier.size(28.dp)) }
            if (state.busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(state.stage, AniMixTheme.type.bodyStrong)
                        Txt("Можно уйти со страницы — выпуск сохранится", AniMixTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            state.error?.let { error -> item {
                Txt(error, AniMixTheme.type.caption, color = AniMixTheme.colors.danger)
                Spacer(Modifier.height(16.dp))
            } }
            if (!hasCurrent && state.loaded && !state.busy) item {
                if (issue == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AniMixMascot(Modifier.size(width = 100.dp, height = 148.dp), pose = MascotPose.Thinking)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Txt("Аниме меняется.\nВы — в курсе.", AniMixTheme.type.heading)
                            Spacer(Modifier.height(8.dp))
                            Txt("Новые серии, объявления и истории о создании любимых тайтлов — в одном выпуске.", AniMixTheme.type.caption)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                } else if (weekPlain != null) {
                    Txt("Выпуск недели собран по Shikimori, без ИИ-обзора. Его можно собрать заново — например, когда ИИ-обзор станет доступен.", AniMixTheme.type.caption)
                    Spacer(Modifier.height(12.dp))
                } else {
                    Txt("Новая неделя — новый выпуск", AniMixTheme.type.subheading)
                    Spacer(Modifier.height(12.dp))
                }
                if (interests.isEmpty()) {
                    Txt("Добавьте интересные аниме в списки. AniPulse начнёт со «Смотрю», избранного и уже просмотренных тайтлов.", AniMixTheme.type.body)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Выбрать аниме", { navigator.select(Tab.Catalog) }, Modifier.fillMaxWidth(), icon = AniMixIcons.Search)
                } else {
                    Txt("В этом выпуске", AniMixTheme.type.label)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(interests, key = { it.anime.key }) { topic ->
                            Txt(topic.anime.title, AniMixTheme.type.caption, maxLines = 1,
                                modifier = Modifier.widthIn(max = 220.dp).clip(AniMixTheme.shapes.pill)
                                    .background(AniMixTheme.colors.chip).padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Txt("Новости и серии берём из Shikimori. Для ИИ-обзора передадим ИИ-сервису названия этих тайтлов, их списки и число просмотренных серий. Имя, отзывы и другие данные профиля не отправляются.", AniMixTheme.type.micro)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(when { state.error != null -> "Попробовать снова"; weekPlain != null -> "Обновить выпуск"; else -> "Собрать выпуск" },
                        { selected = null; graph.pulse.generate(library) }, Modifier.fillMaxWidth(), icon = AniMixIcons.Pulse)
                    Spacer(Modifier.height(8.dp))
                    Txt("Один выпуск на неделю. Сохранённое можно читать без сети.", AniMixTheme.type.micro)
                }
                Spacer(Modifier.height(28.dp))
            }
            if (state.issues.size > 1) item {
                Txt("Выпуски", AniMixTheme.type.label)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.issues, key = { it.window.id }) { old ->
                        Chip(pulseDateLabel(old.createdAt), old.window.id == issue?.window?.id, { selected = old.window.id })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            if (linkError) item { Txt("Не удалось открыть ссылку на источник", AniMixTheme.type.caption) }
            if (issue != null) {
                item {
                    Txt("${pulseDateLabel(issue.window.from)} — ${pulseDateLabel(issue.window.until)}", AniMixTheme.type.label, color = AniMixTheme.colors.accentText)
                    Spacer(Modifier.height(12.dp))
                    Txt(issue.editorial.headline, AniMixTheme.type.title)
                    Spacer(Modifier.height(12.dp))
                    Txt(issue.editorial.intro, AniMixTheme.type.body)
                    issue.note?.let { note ->
                        Spacer(Modifier.height(8.dp))
                        Txt("Собрано по данным Shikimori: $note.", AniMixTheme.type.micro)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                val airing = issue.titles.filter { it.anime.status == AnimeStatus.Ongoing || it.weekEpisodes.isNotEmpty() }
                if (airing.isNotEmpty()) {
                    item {
                        PulseHeading("По вашим спискам")
                        Txt("Состояние на ${pulseDateLabel(issue.createdAt)}. Число доступных серий не означает, что все они вышли за неделю.", AniMixTheme.type.micro)
                        Spacer(Modifier.height(12.dp))
                    }
                    items(airing, key = { "release:${issue.window.id}:${it.anime.key}" }) { topic ->
                        Row(Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).clickable { navigator.push(Route.Detail(topic.anime)) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemoteImage(topic.anime.posterUrl, Modifier.size(width = 42.dp, height = 60.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(topic.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
                                val delta = topic.previousAired?.let { (topic.anime.episodesAired - it).takeIf { n -> n > 0 } }
                                val week = topic.weekEpisodes.takeIf { it.isNotEmpty() }?.let { " · за неделю +${it.size}" }
                                val aired = topic.anime.episodesAired
                                Txt((if (aired > 0) "Вышло серий: $aired" + (topic.anime.episodes.takeIf { it > 0 }?.let { " из $it" } ?: "") else "Число серий уточняется") +
                                    (if (!topic.fresh) " · сохранённые данные" else week ?: delta?.let { " · +$it с прошлого выпуска" }.orEmpty()), AniMixTheme.type.caption)
                                if (topic.fresh) topic.nextEpisodeAt?.let { date ->
                                    runCatching { kotlin.time.Instant.parse(date).toEpochMilliseconds() }.getOrNull()?.let { time ->
                                        Txt("Следующая серия: ${pulseDateLabel(time)}", AniMixTheme.type.micro, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                            Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = AniMixTheme.colors.textMuted)
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                for ((category, label) in listOf("news" to "Главное за неделю", "fact" to "За кадром", "next" to "На следующей неделе")) {
                    val articles = issue.editorial.articles.filter { it.category == category }
                    if (articles.isNotEmpty()) {
                        item { PulseHeading(label) }
                        itemsIndexed(articles, key = { index, _ -> "$category:${issue.window.id}:$index" }) { _, article ->
                            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                                article.date?.let { Txt(it.split('-').let { parts -> "${parts[2]}.${parts[1]}.${parts[0]}" }, AniMixTheme.type.micro,
                                    modifier = Modifier.padding(bottom = 6.dp)) }
                                Txt(article.title, AniMixTheme.type.subheading)
                                Spacer(Modifier.height(8.dp))
                                Txt(article.text, AniMixTheme.type.body)
                                Spacer(Modifier.height(8.dp))
                                article.sourceIds.mapNotNull { id -> issue.sources.firstOrNull { it.id == id } }.forEach { source ->
                                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(source.url) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(AniMixIcons.Share, null, Modifier.size(14.dp), tint = AniMixTheme.colors.accentText)
                                        Spacer(Modifier.width(8.dp))
                                        Txt(source.title.ifBlank { io.ktor.http.Url(source.url).host }, AniMixTheme.type.caption,
                                            color = AniMixTheme.colors.accentText, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(color = AniMixTheme.colors.outline)
                    Spacer(Modifier.height(16.dp))
                    Txt((if (issue.aiEdited && issue.note == null) "Подготовлено ИИ по найденным источникам и новостям Shikimori. Важные даты и объявления проверяйте по ссылкам."
                        else "Собрано по данным Shikimori.") + " Выпуск сохранён ${pulseDateLabel(issue.createdAt)}.", AniMixTheme.type.micro)
                }
            }
        }
    }
}

@Composable
private fun PulseHeading(text: String) {
    Txt(text, AniMixTheme.type.heading)
    Spacer(Modifier.height(16.dp))
}
