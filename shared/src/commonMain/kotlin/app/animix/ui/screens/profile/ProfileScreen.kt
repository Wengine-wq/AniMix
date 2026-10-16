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
import app.animix.data.library.ListStatus
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
    var editing by remember { mutableStateOf(false) }
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
            Column(Modifier.weight(1f)) {
                if (editing) {
                    NameField(library.profile.name) { name ->
                        graph.library.renameProfile(name)
                        editing = false
                    }
                } else {
                    Txt(library.profile.name, type.heading, modifier = Modifier.clickable { editing = true })
                }
                Spacer(Modifier.height(2.dp))
                Txt("Локальный профиль · списки хранятся на этом устройстве", type.caption)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(library.listed.toString(), "В списках", Modifier.weight(1f))
            Stat(library.inStatus(ListStatus.Completed).size.toString(), "Просмотрено", Modifier.weight(1f))
            Stat(achievements.count { it.unlocked }.toString(), "Достижения", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Panel {
            NavRow("Мои списки", AniMixIcons.Bookmark, onClick = { navigator.push(Route.Lists) }, trailing = library.listed.takeIf { it > 0 }?.toString())
            Divider()
            NavRow("История просмотров", AniMixIcons.History, onClick = { navigator.push(Route.History) })
            Divider()
            NavRow("Избранное", AniMixIcons.Heart, onClick = { navigator.select(Tab.Favorites) }, trailing = library.favorites.size.takeIf { it > 0 }?.toString())
            Divider()
            NavRow("Загрузки", AniMixIcons.Download, onClick = { navigator.select(Tab.Downloads) })
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
                        graph.achievements.themeChosen(mode.name)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        GroupTitle("Воспроизведение")
        Panel {
            ToggleRow("Автопереход к следующей серии", settings.autoNextEpisode, { v -> graph.settings.update { it.copy(autoNextEpisode = v) }; if (v) graph.achievements.settingEnabled("autoNextEpisode") }, AniMixIcons.Play)
            Divider()
            ToggleRow("Пропускать опенинги", settings.skipOpenings, { v -> graph.settings.update { it.copy(skipOpenings = v) }; if (v) graph.achievements.settingEnabled("skipOpenings") }, AniMixIcons.Clock)
            Divider()
            ToggleRow("Пропускать эндинги", settings.skipEndings, { v -> graph.settings.update { it.copy(skipEndings = v) }; if (v) graph.achievements.settingEnabled("skipEndings") }, AniMixIcons.Clock)
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
