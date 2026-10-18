package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.importer.AnixartList
import app.animix.data.importer.AnixartProfile
import app.animix.data.importer.ImportState
import app.animix.data.importer.OAuthBrowser
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

/** Settings → «Импорт списков»: Shikimori (sign-in required) and public Anixart lists. */
@Composable
internal fun ImportSection(graph: AppGraph, navigator: Navigator) {
    LaunchedEffect(Unit) { graph.shikimoriAccount.load() }
    Column {
        Txt("Shikimori", AniMixTheme.type.heading)
        Spacer(Modifier.height(4.dp))
        Txt("Вход обязателен: так импорт видит все ваши списки, включая скрытые.", AniMixTheme.type.caption)
        Spacer(Modifier.height(12.dp))
        ShikimoriImport(graph)
        Spacer(Modifier.height(32.dp))
        Txt("Anixart", AniMixTheme.type.heading)
        Spacer(Modifier.height(4.dp))
        Txt("По нику, без пароля. Видны только открытые списки профиля.", AniMixTheme.type.caption)
        Spacer(Modifier.height(12.dp))
        AnixartImport(graph, navigator)
        Spacer(Modifier.height(20.dp))
        Txt(
            "Импорт не стирает ваше: статус и оценка с этого устройства сохраняются, просмотренных серий становится только больше. Награды за просмотр импорт не выдаёт.",
            AniMixTheme.type.micro,
        )
    }
}

@Composable
private fun ShikimoriImport(graph: AppGraph) {
    val colors = AniMixTheme.colors
    val user by graph.shikimoriAccount.user.collectAsState()
    val state by graph.imports.shikimoriImport.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    login?.let { expected ->
        OAuthBrowser(
            url = graph.shikimoriAccount.authorizeUrl(expected),
            redirectPrefix = graph.shikimoriAccount.redirectUri,
            onRedirect = { url ->
                login = null
                scope.launch {
                    problem = try { graph.shikimoriAccount.complete(url, expected); null }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (error: Exception) { error.message ?: "Не удалось войти в Shikimori." }
                }
            },
            onCancel = { login = null },
        )
    }
    val account = user
    if (account == null) {
        PrimaryButton("Войти через Shikimori", { problem = null; login = graph.shikimoriAccount.newState() }, Modifier.fillMaxWidth(), icon = AniMixIcons.User)
    } else {
        Panel {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(account.avatar, Modifier.size(44.dp), shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(account.nickname, AniMixTheme.type.bodyStrong, maxLines = 1)
                    Txt("Аккаунт Shikimori подключён", AniMixTheme.type.micro)
                }
                Chip("Выйти", false, { scope.launch { graph.shikimoriAccount.signOut() } })
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (state is ImportState.Running) "Импортируем…" else "Импортировать списки",
            { graph.imports.importShikimori() },
            Modifier.fillMaxWidth(),
            icon = AniMixIcons.Download,
            enabled = state !is ImportState.Running,
        )
    }
    problem?.let { Txt(it, AniMixTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 10.dp)) }
    ImportStatus(state, onMissing = {})
}

@Composable
private fun AnixartImport(graph: AppGraph, navigator: Navigator) {
    val colors = AniMixTheme.colors
    val state by graph.imports.anixartImport.collectAsState()
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf<List<AnixartProfile>?>(null) }
    var chosen by remember { mutableStateOf<AnixartProfile?>(null) }
    var counts by remember { mutableStateOf<Map<AnixartList, Int>>(emptyMap()) }
    var lists by remember { mutableStateOf(AnixartList.entries.toSet()) }
    var searching by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun search() {
        val query = nickname.trim()
        if (query.length < 2) return
        searching = true; problem = null; chosen = null
        scope.launch {
            try { profiles = graph.anixart.searchProfiles(query) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) { problem = error.message ?: "Anixart не отвечает." }
            searching = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = nickname,
            onValueChange = { nickname = it.take(40) },
            singleLine = true,
            textStyle = AniMixTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.weight(1f).height(48.dp).clip(AniMixTheme.shapes.medium).background(colors.chip)
                .rim(AniMixTheme.shapes.medium, accent = false),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (nickname.isEmpty()) Txt("Ник в Anixart", AniMixTheme.type.body, color = colors.textMuted)
                    field()
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        IconCircle(AniMixIcons.Search, ::search, size = 48.dp, contentDescription = "Найти профиль")
    }
    if (searching) LinearLoading(Modifier.padding(top = 12.dp))
    problem?.let { Txt(it, AniMixTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 10.dp)) }
    val found = profiles
    val profile = chosen
    if (profile == null && found != null) {
        Spacer(Modifier.height(12.dp))
        if (found.isEmpty()) Txt("Профиль не найден. Проверьте ник.", AniMixTheme.type.caption)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found.take(6).forEach { candidate ->
                Row(
                    Modifier.fillMaxWidth().clip(AniMixTheme.shapes.medium).background(colors.chip)
                        .clickable {
                            chosen = candidate
                            counts = emptyMap()
                            scope.launch { counts = runCatching { graph.anixart.counts(candidate.id) }.getOrDefault(emptyMap()) }
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteImage(candidate.avatar, Modifier.size(36.dp), shape = CircleShape)
                    Spacer(Modifier.width(12.dp))
                    Txt(candidate.login, AniMixTheme.type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
                }
            }
        }
    }
    if (profile != null) {
        Spacer(Modifier.height(12.dp))
        Panel {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(profile.avatar, Modifier.size(40.dp), shape = CircleShape)
                    Spacer(Modifier.width(12.dp))
                    Txt(profile.login, AniMixTheme.type.bodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
                    Chip("Другой", false, { chosen = null })
                }
                Spacer(Modifier.height(12.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnixartList.entries.forEach { list ->
                        val on = list in lists
                        val count = counts[list]
                        Chip(if (count != null) "${list.status.label} · $count" else list.status.label, on, {
                            lists = if (on) lists - list else lists + list
                        }, icon = if (on) AniMixIcons.Check else null)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val total = counts.filterKeys { it in lists }.values.sum()
        PrimaryButton(
            when {
                state is ImportState.Running -> "Импортируем…"
                counts.isEmpty() -> "Импортировать"
                else -> "Импортировать $total ${titlesWord(total)}"
            },
            { graph.imports.importAnixart(profile, lists) },
            Modifier.fillMaxWidth(),
            icon = AniMixIcons.Download,
            enabled = state !is ImportState.Running && lists.isNotEmpty(),
        )
    }
    ImportStatus(state, onMissing = { navigator.push(Route.Search(it)) })
}

@Composable
private fun ImportStatus(state: ImportState, onMissing: (String) -> Unit) {
    val colors = AniMixTheme.colors
    when (state) {
        ImportState.Idle -> Unit
        is ImportState.Running -> Column(Modifier.padding(top = 12.dp)) {
            Txt(if (state.total > 0) "${state.label} · ${state.done} из ${state.total}" else state.label, AniMixTheme.type.caption)
            Spacer(Modifier.height(8.dp))
            if (state.total > 0) ProgressLine(state.done.toFloat() / state.total) else LinearLoading()
        }
        is ImportState.Failed -> Txt(state.message, AniMixTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 12.dp))
        is ImportState.Finished -> Column(Modifier.padding(top = 12.dp)) {
            val found = state.total - state.missing.size
            Txt("Готово: найдено $found из ${state.total}. Добавлено ${state.added}, обновлено ${state.updated}.", AniMixTheme.type.bodyStrong)
            if (state.missing.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Txt("Не нашлись автоматически · нажмите, чтобы найти вручную", AniMixTheme.type.caption)
                Spacer(Modifier.height(8.dp))
                Panel {
                    state.missing.take(40).forEachIndexed { index, release ->
                        Row(Modifier.fillMaxWidth().clickable { onMissing(release.displayTitle) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Txt(release.displayTitle, AniMixTheme.type.bodyStrong, maxLines = 2)
                                Txt(listOfNotNull(release.titleOriginal.takeIf { it != release.displayTitle }, release.year?.toString(), release.list.status.label).joinToString(" · "), AniMixTheme.type.micro, maxLines = 1)
                            }
                            Icon(AniMixIcons.Search, null, Modifier.size(16.dp), tint = colors.textMuted)
                        }
                        if (index < minOf(state.missing.size, 40) - 1) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(colors.outline))
                    }
                }
            }
        }
    }
}

@Composable
private fun LinearLoading(modifier: Modifier = Modifier) {
    androidx.compose.material3.LinearProgressIndicator(
        modifier.fillMaxWidth().height(4.dp).clip(AniMixTheme.shapes.pill),
        color = AniMixTheme.colors.accent,
        trackColor = AniMixTheme.colors.outline,
    )
}

private fun titlesWord(n: Int): String = when {
    n % 100 in 11..14 -> "тайтлов"
    n % 10 == 1 -> "тайтл"
    n % 10 in 2..4 -> "тайтла"
    else -> "тайтлов"
}
