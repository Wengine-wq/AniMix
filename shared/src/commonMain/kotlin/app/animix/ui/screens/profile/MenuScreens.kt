package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.*
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
 * Profile → «Меню»: everything about you that doesn't fit the tab bar, then the app itself.
 * Each place has exactly one entry; settings no longer repeat achievements, history or «О приложении».
 */
@Composable
fun MenuScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    val level = remember(library, achievements) { library.level(achievements.count { it.earned }) }
    ScreenColumn(padding) {
        PageHeader("Меню", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(24.dp))
        Txt("Ваш AniMix", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        Panel {
            NavRow("Уровень", AniMixIcons.Star, { navigator.push(Route.Level) }, trailing = "Уровень ${level.level}")
            AccountDivider()
            NavRow("Достижения", AniMixIcons.Trophy, { navigator.push(Route.Achievements) },
                trailing = "${achievements.count { it.unlocked }} из ${achievements.size}")
            AccountDivider()
            val requests = social.incomingRequests().size
            NavRow("Друзья", AniMixIcons.User, { navigator.push(Route.Friends) }, trailing = when {
                account.user == null -> "После входа"
                requests > 0 -> "${social.acceptedFriends().size} · заявок: $requests"
                else -> "${social.acceptedFriends().size}"
            })
            AccountDivider()
            NavRow("История просмотров", AniMixIcons.History, { navigator.push(Route.History) })
        }
        Spacer(Modifier.height(28.dp))
        Txt("Приложение", AniMixTheme.type.heading)
        Spacer(Modifier.height(12.dp))
        Panel {
            NavRow("Настройки", AniMixIcons.Settings, { navigator.push(Route.Settings) })
            AccountDivider()
            NavRow("О приложении", AniMixIcons.Info, { navigator.push(Route.SettingsSection("About")) }, trailing = AppRelease.version)
        }
    }
}

/** «Уровень»: the ring and number from the profile, how far the next level is, and what raises it. */
@Composable
fun LevelScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val level = remember(library, achievements) { library.level(achievements.count { it.earned }) }
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
        Spacer(Modifier.height(8.dp))
        Txt("Появятся в одном из следующих обновлений. Набранный уровень сохранится.", AniMixTheme.type.caption)
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
