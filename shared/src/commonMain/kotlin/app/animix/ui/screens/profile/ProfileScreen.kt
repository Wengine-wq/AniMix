package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.library.stats
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import app.animix.core.ThemeMode
import app.animix.ui.components.IconCircle
import app.animix.ui.components.NavRow
import app.animix.ui.components.Panel
import app.animix.ui.components.ToggleRow
import app.animix.ui.components.Txt
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.navigation.Tab
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val stats = remember(library) { library.stats() }
    var editing by remember { mutableStateOf(false) }
    ScreenColumn(contentPadding) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                Txt(library.profile.name.take(1).uppercase(), type.title, color = colors.onAccent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (editing) {
                    NameField(library.profile.name) { name -> graph.library.renameProfile(name); editing = false }
                } else {
                    Txt(library.profile.name, type.heading, modifier = Modifier.clickable { editing = true })
                }
                Txt("Локальный профиль · нажмите на имя, чтобы сменить", type.micro)
            }
            IconCircle(AniMixIcons.Sliders, onClick = { navigator.push(Route.Settings) })
        }
        Spacer(Modifier.height(20.dp))
        StreakCard(stats.streak, stats.bestStreak)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(hours(stats.totalMinutes), "Часов", Modifier.weight(1f))
            Stat(stats.episodes.toString(), "Серий", Modifier.weight(1f))
            Stat(stats.completed.toString(), "Тайтлов", Modifier.weight(1f))
            Stat("${achievements.count { it.unlocked }}", "Наград", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        GroupTitle("Активность за две недели")
        Panel { ActivityChart(stats.lastDays, Modifier.padding(16.dp)) }
        if (stats.topGenres.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            GroupTitle("Любимые жанры")
            Panel { GenreBars(stats.topGenres, Modifier.padding(16.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        Panel {
            NavRow("Списки", AniMixIcons.Bookmark, onClick = { navigator.select(Tab.Lists) }, trailing = library.listed.takeIf { it > 0 }?.toString())
            Divider()
            NavRow("История просмотров", AniMixIcons.History, onClick = { navigator.push(Route.History) })
            Divider()
            NavRow("Достижения", AniMixIcons.Trophy, onClick = { navigator.push(Route.Achievements) }, trailing = "${achievements.count { it.unlocked }} / ${achievements.size}")
        }
        Spacer(Modifier.height(14.dp))
        Panel {
            NavRow("Настройки", AniMixIcons.Sliders, onClick = { navigator.push(Route.Settings) })
            Divider()
            NavRow("Поддержка", AniMixIcons.Help, onClick = { navigator.push(Route.Support) })
        }
    }
}

private fun hours(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes < 600 -> "${minutes / 60},${(minutes % 60 * 10 / 60).coerceAtLeast(if (minutes < 60) 1 else 0)}"
    else -> "${minutes / 60}"
}

private fun daysWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

@Composable
private fun StreakCard(streak: Int, best: Int) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(AniMixTheme.shapes.large).background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), AniMixTheme.shapes.large).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
            Icon(AniMixIcons.Flame, null, Modifier.size(22.dp), tint = colors.onAccent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(if (streak > 0) "$streak ${daysWord(streak)} подряд" else "Стрик не начат", AniMixTheme.type.heading)
            Txt(
                if (streak > 0) "Лучший стрик: $best ${daysWord(best)}" else "Смотрите хотя бы минуту в день, чтобы начать",
                AniMixTheme.type.caption,
            )
        }
    }
}

/** Minutes per day as soft bars; today is the last, accented one. */
@Composable
private fun ActivityChart(minutes: List<Int>, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            minutes.forEachIndexed { index, value ->
                val today = index == minutes.lastIndex
                Box(
                    Modifier.weight(1f).fillMaxHeight((value.toFloat() / max).coerceAtLeast(0.04f))
                        .clip(AniMixTheme.shapes.small)
                        .background(if (today) colors.accent else if (value > 0) colors.accent.copy(alpha = 0.45f) else colors.outline),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Txt("2 недели назад", AniMixTheme.type.micro, modifier = Modifier.weight(1f))
            Txt(if (minutes.last() > 0) "Сегодня · ${minutes.last()} мин" else "Сегодня", AniMixTheme.type.micro)
        }
    }
}

@Composable
private fun GenreBars(genres: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val max = genres.maxOf { it.second }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        genres.forEach { (genre, weight) ->
            Column {
                Txt(genre, AniMixTheme.type.label)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(AniMixTheme.shapes.pill).background(colors.outline)) {
                    Box(Modifier.fillMaxWidth(weight.toFloat() / max).fillMaxHeight().clip(AniMixTheme.shapes.pill).background(colors.accentGradient))
                }
            }
        }
    }
}

/** Settings are grouped into categories; each opens its own page. */
private enum class SettingsCategory(val title: String, val icon: ImageVector) {
    Appearance("Внешний вид", AniMixIcons.Palette),
    Playback("Воспроизведение", AniMixIcons.Play),
    Lists("Списки", AniMixIcons.Bookmark),
    Downloads("Загрузки", AniMixIcons.Download),
    Network("Соединение", AniMixIcons.Wifi),
    Data("Данные", AniMixIcons.Refresh),
}

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val settings by graph.settings.state.collectAsState()
    ScreenColumn(contentPadding) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
            Spacer(Modifier.width(12.dp))
            Txt("Настройки", AniMixTheme.type.title)
        }
        Spacer(Modifier.height(24.dp))
        Panel {
            SettingsCategory.entries.forEachIndexed { index, category ->
                val summary = when (category) {
                    SettingsCategory.Appearance -> settings.themeMode.label
                    SettingsCategory.Playback -> if (settings.autoNextEpisode) "Автопереход вкл." else "Автопереход выкл."
                    SettingsCategory.Lists -> if (settings.favoritesLast) "Избранное в конце" else "Избранное первым"
                    SettingsCategory.Downloads -> settings.downloadQuality ?: "Не выбрано"
                    SettingsCategory.Network -> if (settings.smartConnection) "Умное" else "Обычное"
                    SettingsCategory.Data -> null
                }
                NavRow(category.title, category.icon, onClick = { navigator.push(Route.SettingsSection(category.name)) }, trailing = summary)
                if (index < SettingsCategory.entries.lastIndex) Divider()
            }
        }
        Spacer(Modifier.height(24.dp))
        Txt("AniMix 3.0 New Horizons", AniMixTheme.type.caption, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun SettingsSectionScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues, id: String) {
    val settings by graph.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val category = SettingsCategory.entries.firstOrNull { it.name == id } ?: SettingsCategory.Appearance
    var cacheCleared by remember { mutableStateOf(false) }
    ScreenColumn(contentPadding) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
            Spacer(Modifier.width(12.dp))
            Txt(category.title, AniMixTheme.type.title)
        }
        Spacer(Modifier.height(24.dp))
        when (category) {
            SettingsCategory.Appearance -> Panel {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val icon = when (mode) {
                            ThemeMode.System -> AniMixIcons.Monitor
                            ThemeMode.Dusk -> AniMixIcons.Moon
                            ThemeMode.Dawn -> AniMixIcons.Sun
                        }
                        ThemeOption(mode.label, icon, settings.themeMode == mode, Modifier.weight(1f)) {
                            graph.settings.update { it.copy(themeMode = mode) }
                            graph.achievements.themeChosen(mode.name)
                        }
                    }
                }
            }
            SettingsCategory.Playback -> Panel {
                ToggleRow("Автопереход к следующей серии", settings.autoNextEpisode, { v -> graph.settings.update { it.copy(autoNextEpisode = v, autoNextAsked = true) }; if (v) graph.achievements.settingEnabled("autoNextEpisode") }, AniMixIcons.Play)
                Divider()
                ToggleRow("Пропускать опенинги", settings.skipOpenings, { v -> graph.settings.update { it.copy(skipOpenings = v) }; if (v) graph.achievements.settingEnabled("skipOpenings") }, AniMixIcons.Clock)
                Divider()
                ToggleRow("Пропускать эндинги", settings.skipEndings, { v -> graph.settings.update { it.copy(skipEndings = v) }; if (v) graph.achievements.settingEnabled("skipEndings") }, AniMixIcons.Clock)
                Divider()
                ToggleRow("Без рамок на весь экран", settings.fillScreen, { v -> graph.settings.update { it.copy(fillScreen = v) } }, AniMixIcons.Fill, subtitle = "Заполняет экран, обрезая лишь узкие полосы по краям")
            }
            SettingsCategory.Lists -> Panel {
                ToggleRow("Избранное в конце списков", settings.favoritesLast, { v -> graph.settings.update { it.copy(favoritesLast = v) } }, AniMixIcons.Heart, subtitle = "По умолчанию «Избранное» идёт первым")
            }
            SettingsCategory.Downloads -> Panel {
                Column(Modifier.padding(12.dp)) {
                    app.animix.ui.screens.downloads.DownloadQualityOptions(settings.downloadQuality) { quality ->
                        graph.settings.update { it.copy(downloadQuality = quality) }
                    }
                    Txt(
                        if (settings.downloadQuality == null) "Спросим при первой загрузке" else "Если серии нет в этом качестве, скачаем ближайшее ниже",
                        AniMixTheme.type.micro,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                    )
                }
            }
            SettingsCategory.Network -> {
                Panel {
                    ToggleRow(
                        "Умное соединение", settings.smartConnection, { v -> graph.settings.update { it.copy(smartConnection = v) } }, AniMixIcons.Wifi,
                        subtitle = "Заранее готовит следующую серию и список серий, качает части видео параллельно",
                    )
                    Divider()
                    ToggleRow(
                        "Экономия трафика", settings.trafficSaver, { v -> graph.settings.update { it.copy(trafficSaver = v) } }, AniMixIcons.Download,
                        subtitle = "В мобильной сети видео стартует не выше 720p, загрузки идут в два потока",
                    )
                }
                Spacer(Modifier.height(10.dp))
                Txt(
                    if (app.animix.data.isNetworkMetered()) "Сейчас: мобильная или лимитная сеть" else "Сейчас: безлимитная сеть",
                    AniMixTheme.type.micro,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            SettingsCategory.Data -> Panel {
                NavRow(if (cacheCleared) "Кэш каталога очищен" else "Очистить кэш каталога", AniMixIcons.Refresh, onClick = { scope.launch { graph.clearCache(); cacheCleared = true } })
            }
        }
    }
}

@Composable
private fun ScreenColumn(contentPadding: PaddingValues, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(contentPadding).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
private fun NameField(initial: String, onDone: (String) -> Unit) {
    val colors = AniMixTheme.colors
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { value = it.take(32) },
        singleLine = true,
        textStyle = AniMixTheme.type.heading,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(value) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .onFocusChanged { if (!it.isFocused && value != initial) onDone(value) }
            .clip(AniMixTheme.shapes.small).background(colors.surfaceRaised).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun GroupTitle(text: String) {
    Txt(text, AniMixTheme.type.label, color = AniMixTheme.colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 50.dp), color = AniMixTheme.colors.outline, thickness = 1.dp)

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    Column(
        modifier.clip(AniMixTheme.shapes.large).background(colors.surface).border(1.dp, colors.outline, AniMixTheme.shapes.large)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(value, AniMixTheme.type.heading)
        Txt(label, AniMixTheme.type.micro)
    }
}

@Composable
private fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Column(
        modifier.clip(AniMixTheme.shapes.medium)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.outline, AniMixTheme.shapes.medium)
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) colors.accent else colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        Txt(label, AniMixTheme.type.label, color = if (selected) colors.textPrimary else colors.textSecondary)
    }
}
