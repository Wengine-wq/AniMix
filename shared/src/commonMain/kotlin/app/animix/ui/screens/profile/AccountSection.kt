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
 * Settings → «Аккаунт», top to bottom: who you are and the one action that matters (sign in, or the
 * sync state), this account's devices, linked services, and signing out last. The move from AniMix 2.x
 * lives in «Импорт списков», with the other ways to bring lists in.
 */
@Composable
internal fun AccountSection(graph: AppGraph, navigator: Navigator) {
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
    val profile = if (user != null && library.profile.accountId != user.id) library.profile.copy(name = user.name) else library.profile
    Column {
        // Who: the same avatar as the profile, the name and what kind of profile this is.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Txt(profile.name, AniMixTheme.type.heading, maxLines = 2)
                Txt(if (user != null) user.email ?: "Онлайн-профиль AniMix" else "Локальный профиль на этом устройстве",
                    AniMixTheme.type.caption, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        if (user == null) SignedOut(graph, waiting = browserSignIn != null, onSignIn = ::signIn)
        else SyncPanel(graph)

        if (user != null) {
            Section("Устройства")
            HorizonDevices(graph)
        }

        Section("Привязки")
        Panel {
            AccountRow(AniMixIcons.Share, "Shikimori", shikimori?.let { "Подключён: ${it.nickname}" } ?: "Для импорта списков",
                trailing = if (shikimori != null) "Открыть" else "Подключить",
                onClick = { navigator.push(Route.SettingsSection("Import")) })
            Divider()
            AccountRow(AniMixIcons.User, "Google", user?.email?.let { "Вход в AniMix: $it" } ?: "Вход в AniMix через Google",
                trailing = if (user != null) "Подключён" else null)
        }

        if (user != null) {
            Spacer(Modifier.height(32.dp))
            SecondaryButton("Выйти из аккаунта", graph.account::signOut, Modifier.fillMaxWidth(), icon = AniMixIcons.Close, enabled = !account.busy)
            Txt("Списки и история останутся на этом устройстве.", AniMixTheme.type.micro,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Not signed in: what an online profile gives, and one button. */
@Composable
private fun SignedOut(graph: AppGraph, waiting: Boolean, onSignIn: () -> Unit) {
    val colors = AniMixTheme.colors
    val account by graph.account.state.collectAsState()
    Panel {
        Column(Modifier.padding(16.dp)) {
            Txt("Онлайн-профиль AniMix", AniMixTheme.type.subheading)
            Spacer(Modifier.height(10.dp))
            Benefit(AniMixIcons.Refresh, "Списки, история и достижения на телефоне и ПК")
            Benefit(AniMixIcons.User, "Друзья и их коллекции")
            Benefit(AniMixIcons.Share, "Продолжить серию на другом устройстве")
            Spacer(Modifier.height(16.dp))
            // Short enough for 130% text on a phone; the line below says the profile is created on first sign-in.
            PrimaryButton(if (account.busy) "Входим…" else "Войти через Google", onSignIn,
                Modifier.fillMaxWidth(), icon = AniMixIcons.User, enabled = !account.busy && !waiting)
            Txt(when {
                account.error != null -> account.error.orEmpty()
                account.waitingForBrowser -> "Завершите вход в браузере — AniMix продолжит сам."
                else -> "Регистрации нет: при первом входе профиль создаётся сам."
            }, AniMixTheme.type.micro, color = if (account.error != null) colors.danger else colors.textSecondary,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun Benefit(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = AniMixTheme.colors.accent)
        Spacer(Modifier.width(12.dp))
        Txt(text, AniMixTheme.type.caption, color = AniMixTheme.colors.textPrimary)
    }
}

/** Signed in: the actual sync state in words and with a mark, and a manual retry. */
@Composable
private fun SyncPanel(graph: AppGraph) {
    val colors = AniMixTheme.colors
    val sync by graph.accountSync.state.collectAsState()
    val account by graph.account.state.collectAsState()
    val failed = sync.error != null && !sync.syncing
    val done = sync.lastSuccessAt != null && !sync.pending && !failed
    Panel {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (failed) colors.surfaceRaised else colors.accentSoft), contentAlignment = Alignment.Center) {
                if (sync.syncing) LoadingIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (failed) AniMixIcons.Retry else if (done) AniMixIcons.Check else AniMixIcons.Refresh, null, Modifier.size(18.dp),
                    tint = if (failed) colors.danger else colors.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(when {
                    sync.syncing -> "Синхронизируем…"
                    failed -> "Не удалось синхронизировать"
                    done -> "Всё синхронизировано"
                    sync.pending -> "Есть несохранённые изменения"
                    else -> "Ожидаем синхронизацию"
                }, AniMixTheme.type.bodyStrong)
                Txt(if (failed) sync.error.orEmpty() else "Списки, просмотр, достижения и профиль", AniMixTheme.type.caption, maxLines = 3)
            }
            Spacer(Modifier.width(8.dp))
            Chip(if (failed) "Повторить" else "Обновить", false, { if (!sync.syncing) graph.accountSync.requestSync() }, icon = AniMixIcons.Refresh)
        }
    }
    account.error?.let { Txt(it, AniMixTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
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
    Spacer(Modifier.height(32.dp))
    Txt(title, AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun AccountDivider() = RowDivider()

@Composable
private fun Divider() = AccountDivider()

/** A row in an account panel; without [onClick] it only informs and shows no tap target. */
@Composable
internal fun AccountRow(icon: ImageVector, title: String, subtitle: String, trailing: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, enabled = enabled)
        Spacer(Modifier.width(12.dp))
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
