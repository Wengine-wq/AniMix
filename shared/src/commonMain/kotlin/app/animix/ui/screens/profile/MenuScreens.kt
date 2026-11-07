package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.AppRelease
import app.animix.data.library.level
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

/**
 * Profile → «Меню»: places of your AniMix as cards — the level first and wide, then achievements, friends,
 * history, notifications and the digest with their live numbers — and the app itself below.
 * Each place still has exactly one entry; settings never repeat these.
 */
@Composable
fun MenuScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    val notices by graph.notifier.notices.collectAsState()
    val level by graph.level.collectAsState()
    val last = remember(library) { library.entries.values.filter { it.episodePositions.isNotEmpty() || it.episodesWatched > 0 }.maxByOrNull { it.updatedAt } }
    ScreenColumn(padding) {
        PageHeader("Меню", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        Txt("Ваш AniMix", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        LevelCard(library.profile, level) { navigator.push(Route.Level) }
        Spacer(Modifier.height(12.dp))
        val requests = social.incomingRequests().size
        val unread = notices.count { !it.read } + if (account.user != null) requests else 0
        MenuGrid(listOfNotNull(
            MenuTile(AniMixIcons.Trophy, "Достижения", "из ${achievements.size}", "${achievements.count { it.unlocked }}") { navigator.push(Route.Achievements) },
            MenuTile(AniMixIcons.User, "Друзья", when {
                account.user == null -> "После входа"
                requests > 0 -> "Заявок: $requests"
                else -> "В друзьях"
            }, if (account.user != null) "${social.acceptedFriends().size}" else null, accent = requests > 0) { navigator.push(Route.Friends) },
            MenuTile(AniMixIcons.History, "История", last?.anime?.title ?: "Пока пусто") { navigator.push(Route.History) },
            MenuTile(AniMixIcons.Bell, "Уведомления", if (unread > 0) "Новых" else "Новых нет",
                if (unread > 0) "$unread" else null, accent = unread > 0) { navigator.push(Route.Notifications) },
            MenuTile(AniMixIcons.Pulse, "AniPulse", "Ваша аниме-неделя") { navigator.push(Route.Pulse) },
        ))
        Spacer(Modifier.height(28.dp))
        Txt("Приложение", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        MenuGrid(listOf(
            MenuTile(AniMixIcons.Settings, "Настройки", "Тема, плеер, уведомления") { navigator.push(Route.Settings) },
            MenuTile(AniMixIcons.Info, "О приложении", "Версия ${AppRelease.version}") { navigator.push(Route.SettingsSection("About")) },
        ))
    }
}

private class MenuTile(val icon: ImageVector, val title: String, val detail: String, val value: String? = null,
    val accent: Boolean = false, val onClick: () -> Unit)

/** Two cards in a row on a phone, three on a wide screen; every card of a row has the same height. */
@Composable
private fun MenuGrid(tiles: List<MenuTile>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile -> MenuCard(tile, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One place: its icon in a soft circle, a live number when there is one, the name and one quiet line. */
@Composable
private fun MenuCard(tile: MenuTile, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = AniMixTheme.shapes.large
    Column(
        modifier.heightIn(min = 124.dp).pressScale(interaction).softShadow(shape).clip(shape).background(colors.surface.copy(alpha = 0.72f))
            .rim(shape, accent = false).clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = tile.onClick)
            .focusOutline(interaction, shape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(tile.icon, null, Modifier.size(20.dp), tint = colors.accent)
            }
            Spacer(Modifier.weight(1f))
            if (tile.value != null) Txt(tile.value, AniMixTheme.type.title, maxLines = 1)
        }
        Spacer(Modifier.weight(1f).heightIn(min = 14.dp))
        Txt(tile.title, AniMixTheme.type.bodyStrong, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Txt(tile.detail, AniMixTheme.type.caption, color = if (tile.accent) colors.accentText else colors.textSecondary, maxLines = 2)
    }
}

/** The level as the first, wide card: the avatar with its ring, the number, the way to the next level and the next reward. */
@Composable
private fun LevelCard(profile: app.animix.data.library.LocalProfile, level: app.animix.data.library.ProfileLevel, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = AniMixTheme.shapes.large
    // The ring around the avatar already shows the way to the next level; the card names what comes with it.
    val next = LevelRewards.firstOrNull { it.level > level.level }
    Box(Modifier.fillMaxWidth().pressScale(interaction).softShadow(shape).clip(shape).background(colors.surface.copy(alpha = 0.72f))
        .rim(shape, accent = true).clickable(interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
        .focusOutline(interaction, shape)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile, 72.dp, level = level)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Txt("Уровень ${level.level}", AniMixTheme.type.heading)
                Spacer(Modifier.height(4.dp))
                Txt(if (next != null) "Следующая награда — ${next.title.replaceFirstChar { it.lowercase() }}" else "Все награды за уровни открыты", AniMixTheme.type.caption, maxLines = 2)
                if (next != null) Txt("на ${next.level}-м уровне", AniMixTheme.type.micro, color = colors.accentText)
            }
            Icon(AniMixIcons.ChevronRight, null, Modifier.size(20.dp), tint = colors.textMuted)
        }
    }
}

/** «Уровень»: the ring and number from the profile, how far the next level is, and what raises it. */
@Composable
fun LevelScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val level by graph.level.collectAsState()
    ScreenColumn(padding) {
        PageHeader("Уровень", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(library.profile, 128.dp, level = level)
            Spacer(Modifier.height(16.dp))
            Txt("Уровень ${level.level}", AniMixTheme.type.title)
            Spacer(Modifier.height(4.dp))
            Txt("До уровня ${level.level + 1} пройдено ${(level.fraction * 100).toInt()}%", AniMixTheme.type.caption, align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            ProgressLine(level.fraction, Modifier.widthIn(max = 280.dp))
        }
        Spacer(Modifier.height(36.dp))
        Txt("Как растёт уровень", AniMixTheme.type.heading)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LevelSource(AniMixIcons.Clock, "Время просмотра", "Каждая минута серии, которую вы видите впервые")
            LevelSource(AniMixIcons.Play, "Досмотренные серии", "Каждая серия, впервые досмотренная в AniMix")
            LevelSource(AniMixIcons.Trophy, "Достижения", "Каждое достижение, полученное в AniMix")
        }
        Txt("Перемотка, повторный просмотр и импорт истории уровень не повышают.", AniMixTheme.type.micro,
            modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(32.dp))
        Txt("Награды за уровни", AniMixTheme.type.heading)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LevelRewards.forEach { reward ->
                LevelReward(reward.level, reward.title, reward.description, level.level) { reward.preview() }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Темы", { navigator.push(Route.SettingsSection("Appearance")) }, Modifier.weight(1f), icon = AniMixIcons.Sun)
            SecondaryButton("Оформление", { navigator.push(Route.EditProfile()) }, Modifier.weight(1f), icon = AniMixIcons.Palette)
        }
    }
}

/** What a level opens, in order; the menu card names the next one, «Уровень» lists them all. */
internal class LevelRewardInfo(val level: Int, val title: String, val description: String, val preview: @Composable () -> Unit)

internal val LevelRewards = listOf(
    LevelRewardInfo(app.animix.core.ThemeMode.Ice.unlockLevel, "Тема Horizon Noon", "Светлая тема с бирюзовым акцентом и её значок") {
        ThemePreview(app.animix.ui.theme.HorizonIce)
    },
    LevelRewardInfo(app.animix.core.ThemeMode.Coal.unlockLevel, "Тема Horizon Midnight", "Тёмная тема с бирюзовым акцентом и её значок") {
        ThemePreview(app.animix.ui.theme.HorizonCoal)
    },
    LevelRewardInfo(DesignLevel, "Оформление профиля", "Волны, сакура, звёзды или неон в шапке профиля, с движением") {
        ProfileDesignLayer(ProfileDesign.Sakura, Modifier.fillMaxSize(), fade = false)
    },
    LevelRewardInfo(FrameLevel, "Рамка аватара", "В стиле выбранного оформления, вокруг кольца уровня") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(AniMixTheme.colors.accentGradient))
            ProfileFrameLayer(ProfileDesign.Stars, Modifier.size(26.dp))
        }
    },
).sortedBy { it.level }

@Composable
private fun ThemePreview(palette: app.animix.ui.theme.AniMixColors) {
    Box(Modifier.fillMaxSize().background(palette.background).padding(8.dp)) {
        Box(Modifier.width(30.dp).height(6.dp).clip(AniMixTheme.shapes.pill).background(palette.textPrimary.copy(alpha = 0.8f)))
        Box(Modifier.align(Alignment.BottomStart).width(36.dp).height(12.dp).clip(AniMixTheme.shapes.pill).background(palette.accentGradient))
    }
}

/** One reward: a small preview, what it is, and «Открыто» or how many levels remain. */
@Composable
private fun LevelReward(at: Int, title: String, description: String, current: Int, preview: @Composable () -> Unit) {
    val colors = AniMixTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 72.dp, height = 48.dp).clip(AniMixTheme.shapes.small).background(colors.chip)) { preview() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt("Уровень $at · $title", AniMixTheme.type.bodyStrong)
            Txt(description, AniMixTheme.type.caption)
            Txt(if (current >= at) "Открыто" else "Осталось ${at - current} ${levelsWord(at - current)}", AniMixTheme.type.micro,
                color = if (current >= at) colors.accentText else colors.textSecondary)
        }
    }
}

@Composable
private fun LevelSource(icon: ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = AniMixTheme.colors.accentText)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            Spacer(Modifier.height(4.dp))
            Txt(description, AniMixTheme.type.caption)
        }
    }
}
