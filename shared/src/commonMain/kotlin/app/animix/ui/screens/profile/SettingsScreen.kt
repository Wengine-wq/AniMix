package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.ThemeMode
import app.animix.data.AppRelease
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.launch

/** Settings are grouped into categories; each opens its own page. */
private enum class SettingsCategory(val title: String, val icon: ImageVector) {
    Account("Аккаунт", AniMixIcons.User),
    Appearance("Внешний вид", AniMixIcons.Palette),
    Playback("Воспроизведение", AniMixIcons.Play),
    Lists("Списки", AniMixIcons.Bookmark),
    Downloads("Загрузки", AniMixIcons.Download),
    Import("Импорт списков", AniMixIcons.Share),
    Network("Соединение", AniMixIcons.Wifi),
    Performance("Производительность", AniMixIcons.Battery),
    Data("Данные и кэш", AniMixIcons.Refresh),
    Experimental("Экспериментальные функции", AniMixIcons.Flame),
    About("О приложении", AniMixIcons.Info),
}

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val settings by graph.settings.state.collectAsState()
    ScreenColumn(contentPadding) {
        PageHeader("Настройки", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        Txt("Коллекция", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        Panel {
            if (settings.experimentalPulse) {
                NavRow("AniPulse", AniMixIcons.Pulse, { navigator.push(Route.Pulse) }, trailing = "Аниме-неделя")
                Divider()
            }
            NavRow("История просмотров", AniMixIcons.History, { navigator.push(Route.History) })
            Divider()
            NavRow("Достижения", AniMixIcons.Trophy, { navigator.push(Route.Achievements) })
        }
        Spacer(Modifier.height(24.dp))
        Txt("Приложение", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
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
                    SettingsCategory.Data -> "Объём и очистка"
                    SettingsCategory.Experimental -> listOfNotNull("AniPulse".takeIf { settings.experimentalPulse },
                        "Подборки".takeIf { settings.experimentalPicks }).joinToString().ifEmpty { "Выключены" }
                    SettingsCategory.Account -> "Локальный профиль"
                    SettingsCategory.About -> AppRelease.version
                }
                NavRow(category.title, category.icon, onClick = { navigator.push(Route.SettingsSection(category.name)) }, trailing = summary)
                if (index < SettingsCategory.entries.lastIndex) Divider()
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel { NavRow("Поддержка", AniMixIcons.Help, { navigator.push(Route.Support) }) }
        Spacer(Modifier.height(24.dp))
        Txt(AppRelease.label, AniMixTheme.type.caption, modifier = Modifier.align(Alignment.CenterHorizontally), align = TextAlign.Center)
    }
}

@Composable
fun SettingsSectionScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues, id: String) {
    val settings by graph.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val category = SettingsCategory.entries.firstOrNull { it.name == id } ?: SettingsCategory.Appearance
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
            SettingsCategory.About -> AboutSection()
            SettingsCategory.Data -> StorageSection(graph, navigator)
            SettingsCategory.Experimental -> {
                Panel {
                    ToggleRow("AniPulse — аниме-неделя", settings.experimentalPulse,
                        { v -> graph.settings.update { it.copy(experimentalPulse = v) } }, AniMixIcons.Pulse,
                        subtitle = "Еженедельный выпуск по вашим спискам: серии, новости Shikimori и ИИ-обзор со ссылками на источники")
                    Divider()
                    ToggleRow("Подобрано для вас", settings.experimentalPicks,
                        { v -> graph.settings.update { it.copy(experimentalPicks = v) } }, AniMixIcons.Star,
                        subtitle = "Полка на главной: похожее на любимые тайтлы и лучшее в ваших жанрах")
                }
                Txt("Функции в разработке: результаты могут ошибаться, а ИИ-обзору нужна сеть. Выключение скрывает их и ничего не удаляет — архив выпусков сохраняется.",
                    AniMixTheme.type.micro, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                if (settings.experimentalPulse) {
                    Spacer(Modifier.height(12.dp))
                    Panel { NavRow("Открыть AniPulse", AniMixIcons.Pulse, { navigator.push(Route.Pulse) }) }
                }
            }
            SettingsCategory.Account -> AccountSection(graph, navigator)
        }
    }
}

@Composable
internal fun ScreenColumn(contentPadding: PaddingValues, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(contentPadding).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            content = content,
        )
    }
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
