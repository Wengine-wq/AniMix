package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.ThemeMode
import app.animix.data.library.stats
import app.animix.data.library.level
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.navigation.Tab
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val stats = remember(library) { library.stats() }
    val level = remember(library, achievements) { library.level(achievements.count { it.unlocked }) }
    var editing by remember { mutableStateOf(false) }
    if (editing) ProfileNameSheet(library.profile.name, onDismiss = { editing = false }) { name ->
        graph.library.renameProfile(name); editing = false
    }
    ScreenColumn(contentPadding) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Профиль", type.title, modifier = Modifier.weight(1f))
            IconCircle(AniMixIcons.Sliders, { navigator.push(Route.Settings) }, contentDescription = "Настройки")
        }
        Spacer(Modifier.height(24.dp))
        // The person first: avatar, name and level with its thin experience line right under the name.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                val initial = library.profile.name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
                if (initial != null) Txt(initial, type.title, color = colors.onAccent)
                else Icon(AniMixIcons.User, null, Modifier.size(30.dp), tint = colors.onAccent)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(library.profile.name, type.title, maxLines = 2, modifier = Modifier.weight(1f, fill = false).clickable { editing = true })
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { editing = true }, contentAlignment = Alignment.Center) {
                        Icon(AniMixIcons.Edit, "Изменить имя", Modifier.size(16.dp), tint = colors.textMuted)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Уровень ${level.level}", type.label, color = colors.accentText, modifier = Modifier.weight(1f))
                    Txt("${level.progressXp} / ${level.nextLevelXp} XP", type.micro)
                }
                ProgressLine(level.fraction, Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            ProfileStat(hours(stats.totalMinutes), "часов", Modifier.weight(1f))
            ProfileStat(stats.episodes.toString(), "серий", Modifier.weight(1f))
            ProfileStat(stats.completed.toString(), "тайтлов", Modifier.weight(1f))
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Активность", type.heading, modifier = Modifier.weight(1f))
            if (stats.streak > 0) {
                Icon(AniMixIcons.Flame, null, Modifier.size(16.dp), tint = colors.accent)
                Spacer(Modifier.width(6.dp))
                Txt("${stats.streak} ${daysWord(stats.streak)} подряд", type.caption)
            } else Txt("14 дней", type.caption)
        }
        Spacer(Modifier.height(16.dp))
        if (stats.lastDays.all { it == 0 }) {
            // Nothing to chart yet: one quiet line instead of a row of empty bars.
            Txt("Здесь появятся ваши просмотры. Минута сегодня начнёт серию дней.", type.caption)
        } else {
            ActivityChart(stats.lastDays)
            if (stats.bestStreak > 1) Txt("Рекорд: ${stats.bestStreak} ${daysWord(stats.bestStreak)} подряд", type.micro, modifier = Modifier.padding(top = 8.dp))
        }
        if (stats.topGenres.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Txt("Любимые жанры", type.heading)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.topGenres.forEach { (genre, _) ->
                    Txt(genre, type.label, color = colors.textSecondary,
                        modifier = Modifier.clip(AniMixTheme.shapes.pill).background(colors.chip)
                            .rim(AniMixTheme.shapes.pill, accent = false).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Txt("Коллекция", type.heading)
        Spacer(Modifier.height(12.dp))
        Panel {
            NavRow("Мои списки", AniMixIcons.Bookmark, { navigator.select(Tab.Lists) }, trailing = library.listed.toString())
            Divider()
            NavRow("История просмотров", AniMixIcons.History, { navigator.push(Route.History) })
            Divider()
            NavRow("Достижения", AniMixIcons.Trophy, { navigator.push(Route.Achievements) },
                trailing = "${achievements.count { it.unlocked }} / ${achievements.size}")
        }
        Spacer(Modifier.height(12.dp))
        Panel { NavRow("Поддержка", AniMixIcons.Help, { navigator.push(Route.Support) }) }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Txt(value, AniMixTheme.type.title)
        Txt(label, AniMixTheme.type.caption, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun hours(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes < 600 -> "${minutes / 60},${minutes % 60 * 10 / 60}"
    else -> "${minutes / 60}"
}

private fun daysWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

@Composable
private fun ActivityChart(minutes: List<Int>) {
    val colors = AniMixTheme.colors
    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            minutes.forEachIndexed { index, value ->
                Box(Modifier.weight(1f).fillMaxHeight((value.toFloat() / max).coerceAtLeast(0.04f))
                    .clip(AniMixTheme.shapes.small).background(when {
                        index == minutes.lastIndex && value > 0 -> colors.accent
                        value > 0 -> colors.accent.copy(alpha = 0.35f)
                        else -> colors.outline
                    }))
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Txt("13 дней назад", AniMixTheme.type.micro, modifier = Modifier.weight(1f))
            Txt(if ((minutes.lastOrNull() ?: 0) > 0) "Сегодня · ${minutes.last()} мин" else "Сегодня", AniMixTheme.type.micro)
        }
    }
}

/** Settings are grouped into categories; each opens its own page. */
private enum class SettingsCategory(val title: String, val icon: ImageVector) {
    Appearance("Внешний вид", AniMixIcons.Palette),
    Playback("Воспроизведение", AniMixIcons.Play),
    Lists("Списки", AniMixIcons.Bookmark),
    Downloads("Загрузки", AniMixIcons.Download),
    Import("Импорт списков", AniMixIcons.Share),
    Network("Соединение", AniMixIcons.Wifi),
    Performance("Производительность", AniMixIcons.Battery),
    Data("Данные", AniMixIcons.Refresh),
}

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val settings by graph.settings.state.collectAsState()
    ScreenColumn(contentPadding) {
        PageHeader("Настройки", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        Panel {
            SettingsCategory.entries.forEachIndexed { index, category ->
                val summary = when (category) {
                    SettingsCategory.Appearance -> settings.themeMode.label
                    SettingsCategory.Playback -> if (settings.autoNextEpisode) "Автопереход вкл." else "Автопереход выкл."
                    SettingsCategory.Lists -> if (settings.favoritesLast) "Избранное в конце" else "Избранное первым"
                    SettingsCategory.Downloads -> settings.downloadQuality ?: "Не выбрано"
                    SettingsCategory.Import -> "Shikimori, Anixart"
                    SettingsCategory.Network -> if (settings.smartConnection) "Умное" else "Обычное"
                    SettingsCategory.Performance -> if (settings.batterySaver) "Экономия" else "Обычный режим"
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
        PageHeader(category.title, { navigator.back() }, Modifier.padding(top = 12.dp))
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
                Divider()
                ToggleRow("Анимации и переходы", settings.animationsAllowed,
                    { value -> graph.settings.update { it.copy(animationsEnabled = value) } }, AniMixIcons.Play,
                    subtitle = if (settings.batterySaver) "Отключены режимом экономии батареи" else "Плавные переходы, нажатия и индикаторы",
                    enabled = !settings.batterySaver)
            }
            SettingsCategory.Performance -> Panel {
                ToggleRow("Экономия батареи", settings.batterySaver,
                    { value -> graph.settings.update { it.copy(batterySaver = value) } }, AniMixIcons.Battery,
                    subtitle = "Отключает анимации, размытие и автолистание. Уменьшает фоновую активность")
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
            SettingsCategory.Import -> ImportSection(graph, navigator)
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
private fun ProfileNameSheet(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AniMixSheet(onDismiss, title = "Как вас называть?", subtitle = "Имя в вашем профиле AniMix") {
        NameField(value, onValueChange = { value = it }, onDone = { if (value.isNotBlank()) onDone(value.trim()) })
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Сохранить", { onDone(value.trim()) }, Modifier.fillMaxWidth(), enabled = value.isNotBlank())
    }
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val colors = AniMixTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(32)) },
        singleLine = true,
        textStyle = AniMixTheme.type.heading,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .clip(AniMixTheme.shapes.medium).background(colors.chip)
            .border(1.dp, colors.accent.copy(alpha = 0.4f), AniMixTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 50.dp), color = AniMixTheme.colors.outline, thickness = 1.dp)

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
