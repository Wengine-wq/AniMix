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
import app.animix.data.account.openLoopbackCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme

/**
 * Settings → «Аккаунт»: the online AniMix profile (Google sign-in on the AniMix server), migration of
 * the AniMix 2.x local profile into it, then what is still to come (sessions, Horizon Link, Yandex ID).
 */
@Composable
internal fun AccountSection(graph: AppGraph, navigator: Navigator) {
    val colors = AniMixTheme.colors
    val library by graph.library.state.collectAsState()
    val shikimori by graph.shikimoriAccount.user.collectAsState()
    val account by graph.account.state.collectAsState()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var browserSignIn by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { graph.shikimoriAccount.load(); graph.account.load() }
    browserSignIn?.let { job ->
        BrowserWaitDialog(onCancel = { job.cancel(); browserSignIn = null; graph.account.cancelSignIn() })
    }
    // Google signs in only in a normal browser: Android returns through the animix:// link,
    // desktop through a one-shot listener on 127.0.0.1.
    fun signIn() {
        val loopback = openLoopbackCallback()
        if (loopback == null) { runCatching { uri.openUri(graph.account.signInUrl()) }; return }
        browserSignIn = scope.launch {
            try {
                runCatching { uri.openUri(graph.account.signInUrl(loopback.returnUri)) }
                withTimeoutOrNull(10 * 60_000L) { loopback.await() }?.let(graph.account::completeSignIn)
                    ?: graph.account.cancelSignIn()
            } finally { loopback.close(); browserSignIn = null }
        }
    }
    val user = account.user
    Column {
        Panel {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                    Txt((user?.name ?: library.profile.name).take(1).uppercase(), AniMixTheme.type.heading, color = colors.onAccent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(user?.name ?: library.profile.name, AniMixTheme.type.bodyStrong, maxLines = 1)
                    Txt(if (user != null) "Онлайн-профиль AniMix" + (user.email?.let { " · $it" } ?: "")
                        else "Локальный профиль: всё хранится на этом устройстве", AniMixTheme.type.caption, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (user == null) {
            PrimaryButton(if (account.busy) "Входим…" else "Войти или зарегистрироваться", ::signIn,
                Modifier.fillMaxWidth(), icon = AniMixIcons.User, enabled = !account.busy && browserSignIn == null)
            when {
                account.error != null -> Txt(account.error.orEmpty(), AniMixTheme.type.caption, color = colors.accentText,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                account.waitingForBrowser -> Unavailable("Завершите вход в браузере — AniMix продолжит сам.")
                else -> Unavailable("Вход через Google. При первом входе создаётся онлайн-профиль AniMix.")
            }
        } else {
            SecondaryButton("Выйти из аккаунта", graph.account::signOut, Modifier.fillMaxWidth(), icon = AniMixIcons.Close)
        }

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
            AccountRow(AniMixIcons.User, "Google", account.user?.email?.let { "Подключён: $it" } ?: "Вход в AniMix через Google",
                trailing = if (account.user != null) "Подключён" else "Через «Войти»", enabled = account.user != null)
        }
        Unavailable("Сессии на других устройствах, Horizon Link и вход через Яндекс появятся в следующих версиях.")
    }
}

@Composable
private fun LegacyMigrationSection(graph: AppGraph) {
    val colors = AniMixTheme.colors
    val state by graph.legacy.state.collectAsState()
    val account by graph.account.state.collectAsState()
    val library by graph.library.state.collectAsState()
    var local by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { local = withContext(Dispatchers.Default) { graph.legacy.hasLocalData() } }
    Txt("Локальный профиль AniMix 2.x: списки, оценки, просмотренные серии, часы просмотра и достижения. " +
        "Списки попадают в историю просмотров и в онлайн-профиль, достижения — в коллекцию с обычным XP.",
        AniMixTheme.type.caption)
    Spacer(Modifier.height(12.dp))
    if (local) {
        Panel {
            AccountRow(AniMixIcons.Monitor, if (Platform.name == "Android") "Перенести с этого устройства" else "Перенести с этого компьютера",
                if (account.user != null) "В онлайн-профиль «${account.user?.name}» и на это устройство"
                else "Пока только на это устройство: сначала войдите, чтобы списки попали в онлайн-профиль",
                trailing = "Перенести", enabled = !state.busy, onClick = graph.legacy::migrateLocal)
        }
    } else {
        Unavailable(if (Platform.name == "Android") "Данные AniMix 2.x на этом устройстве не найдены. Они остаются, если AniMix установлен поверх версии 2.x."
            else "Данные AniMix 2.x на этом компьютере не найдены.")
    }
    val done = state.result ?: library.legacyMigration
    when {
        state.busy -> Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Txt(state.stage, AniMixTheme.type.caption)
        }
        state.error != null -> Txt(state.error.orEmpty(), AniMixTheme.type.caption, color = colors.accentText,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        done != null -> Txt(
            "Перенесено${done.accountName?.let { " в онлайн-профиль «$it»" } ?: " на это устройство"}: " +
                "тайтлов — ${done.titles}, достижений — ${done.achievements}, часов просмотра — ${done.watchSeconds / 3600}. Повторный перенос не дублирует данные.",
            AniMixTheme.type.caption, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    }
}

/** While the system browser is open: what to do there, and a way back. */
@Composable
private fun BrowserWaitDialog(onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(Modifier.widthIn(max = 380.dp).clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surfaceRaised).padding(20.dp)) {
            Txt("Вход через браузер", AniMixTheme.type.heading)
            Spacer(Modifier.height(6.dp))
            Txt("Войдите в Google в открывшемся браузере. Когда вход подтвердится, AniMix продолжит сам, а вкладку можно будет закрыть.",
                AniMixTheme.type.caption)
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Отмена", onCancel)
        }
    }
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
