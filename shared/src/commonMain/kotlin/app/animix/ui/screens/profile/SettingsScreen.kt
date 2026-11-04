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
    Privacy("Приватность", AniMixIcons.Lock),
    Appearance("Внешний вид", AniMixIcons.Palette),
    Playback("Воспроизведение", AniMixIcons.Play),
    Notifications("Уведомления", AniMixIcons.Bell),
    Lists("Списки", AniMixIcons.Bookmark),
    Downloads("Загрузки", AniMixIcons.Download),
    Import("Импорт списков", AniMixIcons.Share),
    Network("Соединение", AniMixIcons.Wifi),
    Performance("Производительность", AniMixIcons.Battery),
    Data("Данные и кэш", AniMixIcons.Refresh),
    About("О приложении", AniMixIcons.Info),
}

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val settings by graph.settings.state.collectAsState()
    val account by graph.account.state.collectAsState()
    LaunchedEffect(Unit) { graph.account.load() }
    ScreenColumn(contentPadding) {
        PageHeader("Настройки", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        // An update put off with «Позже» waits here until the next launch shows it again.
        val updates by graph.updates.state.collectAsState()
        if (updates.available != null) {
            Panel {
                Column(Modifier.padding(16.dp)) {
                    app.animix.ui.screens.home.UpdateDetails(graph)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Обновить", graph.updates::install, Modifier.fillMaxWidth(), icon = AniMixIcons.Download, enabled = !updates.downloading)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // History, achievements and «О приложении» live in the profile menu; settings hold only settings.
        val shown = SettingsCategory.entries.filter { it != SettingsCategory.About }
        Panel {
            shown.forEachIndexed { index, category ->
                val summary = when (category) {
                    SettingsCategory.Appearance -> settings.themeMode.label
                    SettingsCategory.Playback -> if (settings.autoNextEpisode) "Автопереход вкл." else "Автопереход выкл."
                    SettingsCategory.Lists -> if (settings.favoritesLast) "Избранное в конце" else "Избранное первым"
                    SettingsCategory.Downloads -> settings.downloadQuality ?: "Не выбрано"
                    SettingsCategory.Import -> "Shikimori, Anixart"
                    SettingsCategory.Network -> if (settings.smartConnection) "Умное" else "Обычное"
                    SettingsCategory.Performance -> settings.playerMode.label + if (settings.batterySaver) " · экономия" else ""
                    SettingsCategory.Notifications -> if (settings.notifyNewEpisodes) "Новые серии" else "Выключены"
                    SettingsCategory.Data -> "Объём и очистка"
                    SettingsCategory.Account -> account.user?.name ?: "Локальный профиль"
                    SettingsCategory.Privacy -> if (account.user != null) "Кто видит коллекцию" else "После входа"
                    SettingsCategory.About -> AppRelease.version
                }
                NavRow(category.title, category.icon, onClick = { navigator.push(Route.SettingsSection(category.name)) }, trailing = summary)
                if (index < shown.lastIndex) Divider()
            }
        }
        Spacer(Modifier.height(12.dp))
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
            SettingsCategory.Appearance -> AppearanceSection(graph)
            SettingsCategory.Performance -> {
                // The player is chosen like the theme: two named options side by side, the chosen one explained below.
                Txt("Вид плеера", AniMixTheme.type.heading)
                Spacer(Modifier.height(12.dp))
                Panel {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        app.animix.core.PlayerMode.entries.forEach { mode ->
                            ThemeOption(mode.label, if (mode == app.animix.core.PlayerMode.Plus) AniMixIcons.Sparkle else AniMixIcons.Play,
                                settings.playerMode == mode, Modifier.weight(1f)) { graph.settings.update { it.copy(playerMode = mode) } }
                        }
                    }
                    Txt(when (settings.playerMode) {
                        app.animix.core.PlayerMode.Plus -> "Стеклянные кнопки и свечение рамок, скорость от 0,75× до 2×. На телефоне в полноэкранном режиме — " +
                            "яркость и громкость свайпом, и «картинка в картинке»: серия продолжится в маленьком окне, если выйти из приложения."
                        app.animix.core.PlayerMode.Simple -> "Плотные тёмные кнопки без стекла и свечения, обычная скорость. Меньше нагрузки на слабых устройствах; " +
                            "качество, пропуски, автопереход и Horizon Link на месте."
                    }, AniMixTheme.type.caption, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp))
                }
                Spacer(Modifier.height(24.dp))
                Panel {
                    ToggleRow("Экономия батареи", settings.batterySaver,
                        { value -> graph.settings.update { it.copy(batterySaver = value) } }, AniMixIcons.Battery,
                        subtitle = "Без анимаций, размытия и автолистания; в плеере — без стекла и свечения рамок, полоса прогресса шагами. Внешний вид и навигация те же")
                }
            }
            SettingsCategory.Notifications -> {
                val askPermission = app.animix.data.downloads.rememberDownloadPermissionRequest()
                Panel {
                    ToggleRow("Новые серии", settings.notifyNewEpisodes, { v ->
                        graph.settings.update { it.copy(notifyNewEpisodes = v) }
                        if (v) askPermission()
                    }, AniMixIcons.Bell, subtitle = "Онгоинги из «Смотрю»: когда выходит следующая серия")
                    Divider()
                    ToggleRow("Серия в моей озвучке", settings.notifyDubbing, { v -> graph.settings.update { it.copy(notifyDubbing = v) } }, AniMixIcons.Mic,
                        subtitle = if (settings.notifyNewEpisodes) "Когда серия выходит в озвучке, выбранной для тайтла на экране «Смотреть»" else "Сначала включите новые серии",
                        enabled = settings.notifyNewEpisodes)
                }
                Txt(if (app.animix.core.Platform.name == "Android") "Проверяем каждые полчаса, пока AniMix открыт, и раз в несколько часов в фоне. Уведомления копятся и под колокольчиком в профиле."
                    else "Проверяем каждые полчаса, пока AniMix открыт; уведомление появляется в углу экрана и под колокольчиком в профиле.",
                    AniMixTheme.type.micro, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            }
            SettingsCategory.Playback -> Panel {
                ToggleRow("Автопереход к следующей серии", settings.autoNextEpisode, { v -> graph.settings.update { it.copy(autoNextEpisode = v, autoNextAsked = true) }; if (v) graph.achievements.settingEnabled("autoNextEpisode") }, AniMixIcons.Play)
                Divider()
                ToggleRow("Пропускать опенинги", settings.skipOpenings, { v -> graph.settings.update { it.copy(skipOpenings = v) }; if (v) graph.achievements.settingEnabled("skipOpenings") }, AniMixIcons.Clock)
                Divider()
                ToggleRow("Пропускать эндинги", settings.skipEndings, { v -> graph.settings.update { it.copy(skipEndings = v) }; if (v) graph.achievements.settingEnabled("skipEndings") }, AniMixIcons.Clock)
                Divider()
                ToggleRow("Без рамок", settings.fillScreen, { v -> graph.settings.update { it.copy(fillScreen = v) } }, AniMixIcons.Fill, subtitle = "Заполняет плеер, обрезая лишь узкие полосы по краям")
                Divider()
                ToggleRow("Свечение рамок", settings.ambientGlow, { v -> graph.settings.update { it.copy(ambientGlow = v) } }, AniMixIcons.Sun,
                    subtitle = if (settings.batterySaver) "Отключено режимом экономии батареи" else "Поля вокруг кадра другой формы светятся цветами сцены",
                    enabled = !settings.batterySaver)
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
            SettingsCategory.About -> AboutSection(graph)
            SettingsCategory.Data -> StorageSection(graph, navigator)
            SettingsCategory.Account -> AccountSection(graph, navigator)
            SettingsCategory.Privacy -> PrivacySection(graph, navigator)
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
