package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.Platform
import app.animix.data.importer.OAuthBrowser
import app.animix.data.legacy.LegacyMigration
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

/**
 * Settings → «Аккаунт», laid out for what comes with the AniMix server: sign-in, sessions on
 * other devices, Horizon Link and Yandex/Google sign-in. Everything that needs the server is
 * shown as unavailable rather than pretending to work; the Shikimori link is real.
 */
@Composable
internal fun AccountSection(graph: AppGraph, navigator: Navigator) {
    val colors = AniMixTheme.colors
    val library by graph.library.state.collectAsState()
    val shikimori by graph.shikimoriAccount.user.collectAsState()
    LaunchedEffect(Unit) { graph.shikimoriAccount.load() }
    Column {
        Panel {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                    Txt(library.profile.name.take(1).uppercase(), AniMixTheme.type.heading, color = colors.onAccent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(library.profile.name, AniMixTheme.type.bodyStrong, maxLines = 1)
                    Txt("Локальный профиль: всё хранится на этом устройстве", AniMixTheme.type.caption)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Войти в AniMix", {}, Modifier.fillMaxWidth(), icon = AniMixIcons.User, enabled = false)
        Unavailable("Аккаунты AniMix появятся вместе с серверной частью. Профиль и списки перенесутся в аккаунт.")

        Section("Миграция с AniMix 2.x")
        LegacyMigrationSection(graph)

        Section("Сессии и устройства")
        Panel {
            AccountRow(AniMixIcons.Monitor, "Это устройство", "${Platform.name} · активно сейчас", trailing = "Текущее")
            Divider()
            AccountRow(AniMixIcons.Close, "Завершить другие сессии", "Другие устройства появятся после входа", enabled = false)
        }

        Section("Horizon Link")
        Panel {
            AccountRow(AniMixIcons.Play, "Начали на телефоне — продолжите на ПК",
                "Серия и позиция переходят между вашими устройствами", trailing = "Скоро", enabled = false)
        }

        Section("Привязки")
        Panel {
            AccountRow(AniMixIcons.Share, "Shikimori", shikimori?.let { "Подключён: ${it.nickname}" } ?: "Не подключён · нужен для импорта списков",
                trailing = if (shikimori != null) "Открыть" else "Подключить",
                onClick = { navigator.push(Route.SettingsSection("Import")) })
            Divider()
            AccountRow(AniMixIcons.User, "Яндекс ID", "Вход в AniMix через Яндекс", trailing = "Недоступно", enabled = false)
            Divider()
            AccountRow(AniMixIcons.User, "Google", "Вход в AniMix через Google", trailing = "Недоступно", enabled = false)
        }
        Unavailable("Сессии, Horizon Link и вход через Яндекс и Google заработают с серверной частью AniMix.")
    }
}

@Composable
private fun LegacyMigrationSection(graph: AppGraph) {
    val colors = AniMixTheme.colors
    val state by graph.legacy.state.collectAsState()
    val library by graph.library.state.collectAsState()
    val uri = LocalUriHandler.current
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { local = withContext(Dispatchers.Default) { graph.legacy.hasLocalData() } }
    browserUrl?.let { url ->
        OAuthBrowser(url, LegacyMigration.CALLBACK, service = "Google",
            onRedirect = { redirect -> browserUrl = null; graph.legacy.completeSignIn(redirect) },
            onCancel = { browserUrl = null; graph.legacy.cancelSignIn() })
    }
    Txt("Списки, оценки, просмотренные серии, часы просмотра и достижения из AniMix 2.x. Списки попадают в историю просмотров, достижения — в коллекцию с обычным XP.",
        AniMixTheme.type.caption)
    Spacer(Modifier.height(12.dp))
    Panel {
        AccountRow(AniMixIcons.User, "Войти в аккаунт AniMix 2.x", "Через Google, как в прежней версии. Сессия закроется после переноса",
            trailing = "Войти", enabled = !state.busy, onClick = {
                val url = graph.legacy.signInUrl()
                if (Platform.name == "Android") runCatching { uri.openUri(url) } else browserUrl = url
            })
        if (local) {
            Divider()
            AccountRow(AniMixIcons.Monitor, "Перенести с этого компьютера", "Найдены данные AniMix 2.x, в том числе без аккаунта",
                trailing = "Перенести", enabled = !state.busy, onClick = graph.legacy::migrateLocal)
        }
    }
    val done = state.result ?: library.legacyMigration
    when {
        state.busy -> Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Txt(state.stage, AniMixTheme.type.caption)
        }
        state.waitingForBrowser && Platform.name == "Android" ->
            Unavailable("Завершите вход в браузере — AniMix продолжит перенос сам.")
        state.error != null -> Txt(state.error.orEmpty(), AniMixTheme.type.caption, color = colors.accentText,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        done != null -> Txt(
            "Перенесено${done.accountName?.let { " из аккаунта «$it»" } ?: if (done.source == "device") " с этого компьютера" else ""}: " +
                "тайтлов — ${done.titles}, достижений — ${done.achievements}, часов просмотра — ${done.watchSeconds / 3600}. Повторный перенос не дублирует данные.",
            AniMixTheme.type.caption, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    }
    if (Platform.name == "Android") Unavailable("Без аккаунта данные 2.x хранятся внутри прежнего приложения, поэтому на телефоне перенос идёт через вход в аккаунт.")
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Txt(title, AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Unavailable(text: String) {
    Txt(text, AniMixTheme.type.micro, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(AniMixTheme.colors.outline))

@Composable
private fun AccountRow(icon: ImageVector, title: String, subtitle: String, trailing: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) colors.textSecondary else colors.textMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong, color = if (enabled) colors.textPrimary else colors.textSecondary)
            Txt(subtitle, AniMixTheme.type.caption)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Txt(trailing, AniMixTheme.type.label, color = if (enabled && onClick != null) colors.accent else colors.textMuted)
        }
    }
}
