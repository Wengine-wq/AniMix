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
fun ProfileScreen(navigator: Navigator, contentPadding: PaddingValues) {
    val colors = AniMixTheme.colors
    val type = AniMixTheme.type
    ScreenColumn(contentPadding) {
        Txt("Профиль", type.title, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(colors.accentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AniMixIcons.User, null, Modifier.size(32.dp), tint = colors.onAccent)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Txt("Гость", type.heading)
                Spacer(Modifier.height(2.dp))
                Txt("Списки хранятся на этом устройстве", type.caption)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("0", "Аниме", Modifier.weight(1f))
            Stat("0", "Достижения", Modifier.weight(1f))
            Stat("0", "Коллекции", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Panel {
            NavRow("Мои списки", AniMixIcons.Bookmark, onClick = {})
            Divider()
            NavRow("История просмотров", AniMixIcons.History, onClick = {})
            Divider()
            NavRow("Избранное", AniMixIcons.Heart, onClick = { navigator.select(Tab.Favorites) })
            Divider()
            NavRow("Загрузки", AniMixIcons.Download, onClick = { navigator.select(Tab.Downloads) })
            Divider()
            NavRow("Достижения", AniMixIcons.Trophy, onClick = {})
        }
        Spacer(Modifier.height(14.dp))
        Panel {
            NavRow("Настройки", AniMixIcons.Sliders, onClick = { navigator.push(Route.Settings) })
            Divider()
            NavRow("Поддержка", AniMixIcons.Help, onClick = {})
        }
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val settings by graph.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val type = AniMixTheme.type
    ScreenColumn(contentPadding) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(AniMixIcons.Back, onClick = { navigator.back() })
            Spacer(Modifier.width(12.dp))
            Txt("Настройки", type.title)
        }
        Spacer(Modifier.height(24.dp))
        GroupTitle("Внешний вид")
        Panel {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val icon = when (mode) {
                        ThemeMode.System -> AniMixIcons.Monitor
                        ThemeMode.Dusk -> AniMixIcons.Moon
                        ThemeMode.Dawn -> AniMixIcons.Sun
                    }
                    ThemeOption(mode.label, icon, settings.themeMode == mode, Modifier.weight(1f)) {
                        graph.settings.update { it.copy(themeMode = mode) }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        GroupTitle("Воспроизведение")
        Panel {
            ToggleRow("Автопереход к следующей серии", settings.autoNextEpisode, { v -> graph.settings.update { it.copy(autoNextEpisode = v) } }, AniMixIcons.Play)
            Divider()
            ToggleRow("Пропускать опенинги", settings.skipOpenings, { v -> graph.settings.update { it.copy(skipOpenings = v) } }, AniMixIcons.Clock)
            Divider()
            ToggleRow("Пропускать эндинги", settings.skipEndings, { v -> graph.settings.update { it.copy(skipEndings = v) } }, AniMixIcons.Clock)
        }
        Spacer(Modifier.height(20.dp))
        GroupTitle("Данные")
        Panel {
            NavRow("Очистить кэш каталога", AniMixIcons.Refresh, onClick = { scope.launch { graph.clearCache() } })
        }
        Spacer(Modifier.height(24.dp))
        Txt("AniMix 3.0 New Horizons", type.caption, modifier = Modifier.align(Alignment.CenterHorizontally))
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
