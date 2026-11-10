package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.AppJson
import app.animix.core.Platform
import app.animix.data.account.*
import app.animix.data.achievements.AchievementState
import app.animix.data.library.*
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.*
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Incoming friend requests, for the bell badge in the profile header. */
internal fun SocialState.incomingRequests(): List<SocialUser> = friends.filter { it.status == "incoming" }
internal fun SocialState.acceptedFriends(): List<SocialUser> = friends.filter { it.status == "friends" }

/** Runs a social action with one shared busy flag and a plain-language error. */
@Composable
internal fun rememberSocialAction(): Triple<Boolean, String?, (suspend () -> Unit) -> Unit> {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val run: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            busy = true
            try { block(); error = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Не удалось выполнить действие. Проверьте подключение и повторите." }
            finally { busy = false }
        }
    }
    return Triple(busy, error, run)
}

/** «Друзья»: friends with their presence, incoming and sent requests, and search by name. */
@Composable
fun FriendsScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    val (busy, error, run) = rememberSocialAction()
    LaunchedEffect(account.user?.id) { if (account.user != null) run { graph.social.refresh() } }
    // Servers without presence in the list: ask each friend's overview once, like their profile does.
    var presence by remember { mutableStateOf<Map<String, Pair<Boolean?, Long?>>>(emptyMap()) }
    val friendIds = social.acceptedFriends().filter { it.online == null }.map { it.id }
    LaunchedEffect(friendIds) {
        friendIds.take(30).forEach { id ->
            val raw = runCatching { graph.social.overview(id) }.getOrElse { if (it is CancellationException) throw it; null } ?: return@forEach
            presence = presence + (id to (raw["online"]?.jsonPrimitive?.booleanOrNull to raw["last_seen_at"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }))
        }
    }
    LaunchedEffect(query) {
        delay(400)
        found = runCatching { graph.social.search(query) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
    }
    ScreenColumn(padding) {
        PageHeader("Друзья", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        if (account.user == null) {
            MessageState("Войдите в AniMix", message = "Друзья доступны в онлайн-профиле: «Настройки → Аккаунт».", icon = AniMixIcons.User,
                actionLabel = "Открыть аккаунт", onAction = { navigator.push(Route.SettingsSection("Account")) })
            return@ScreenColumn
        }
        SocialSearchField(query) { query = it }
        (error ?: social.error)?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.accentText, modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
        val requests = social.incomingRequests()
        if (requests.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Panel { NavRow("Заявки в друзья", AniMixIcons.Bell, { navigator.push(Route.Notifications) }, trailing = "${requests.size}") }
        }
        if (query.trim().length >= 2) {
            SocialSection("Результаты поиска")
            if (found.isEmpty()) Txt("Никого не нашли. Попробуйте другое имя.", AniMixTheme.type.caption)
            found.forEach { user ->
                val status = social.friends.find { it.id == user.id }?.status ?: user.status
                SocialUserRow(user, navigator) {
                    when (status) {
                        "friends" -> Txt("В друзьях", AniMixTheme.type.label, color = AniMixTheme.colors.textMuted)
                        "outgoing" -> Txt("Заявка отправлена", AniMixTheme.type.label, color = AniMixTheme.colors.textMuted)
                        "incoming" -> Chip("Принять", true, { run { graph.social.friendAction(user.id) } }, icon = AniMixIcons.Check)
                        else -> Chip("Добавить", false, { if (!busy) run { graph.social.friendAction(user.id) } }, icon = AniMixIcons.User)
                    }
                }
            }
        } else {
            val friends = social.acceptedFriends()
            SocialSection("Друзья · ${friends.size}")
            if (friends.isEmpty()) Txt("Найдите друзей по имени в поиске выше.", AniMixTheme.type.caption)
            friends.forEach { user ->
                val known = presence[user.id]
                SocialUserRow(user, navigator, online = user.online ?: known?.first, lastSeenAt = user.lastSeenAt ?: known?.second)
            }
            val sent = social.friends.filter { it.status == "outgoing" }
            if (sent.isNotEmpty()) {
                SocialSection("Отправленные заявки")
                sent.forEach { user ->
                    SocialUserRow(user, navigator) {
                        Chip("Отменить", false, { if (!busy) run { graph.social.friendAction(user.id, remove = true) } }, icon = AniMixIcons.Close)
                    }
                }
            }
        }
    }
}

/**
 * Settings → «Приватность»: each part of the profile with its own audience — everyone, friends or nobody.
 * Name, avatar, banner and the profile design stay public: they are how people find each other.
 */
@Composable
internal fun PrivacySection(graph: AppGraph, navigator: Navigator) {
    val account by graph.account.state.collectAsState()
    var visibility by remember { mutableStateOf<ProfileVisibility?>(null) }
    val (busy, error, run) = rememberSocialAction()
    LaunchedEffect(account.user?.id) { if (account.user != null) run { visibility = graph.social.visibility() } }
    if (account.user == null) {
        MessageState("Нечего скрывать", message = "Без входа всё хранится только на этом устройстве и никому не видно.", icon = AniMixIcons.Lock,
            actionLabel = "Открыть аккаунт", onAction = { navigator.push(Route.SettingsSection("Account")) }, actionIcon = AniMixIcons.User)
        return
    }
    val current = visibility
    // A change shows at once and returns to the saved value if the server refuses it.
    fun change(next: ProfileVisibility) {
        val before = visibility
        visibility = next
        run { try { visibility = graph.social.setVisibility(next) } catch (error: Exception) { visibility = before; throw error } }
    }
    if (current == null) { if (error == null) PageLoading() } else Panel {
        PrivacyRow(AniMixIcons.Bookmark, "Коллекция", "Списки и избранное", current.collection, !busy) { change(current.copy(collection = it)) }
        AccountDivider()
        PrivacyRow(AniMixIcons.Clock, "Статистика", "Часы, серии, уровень, активность и любимые жанры", current.stats, !busy) { change(current.copy(stats = it)) }
        AccountDivider()
        PrivacyRow(AniMixIcons.Trophy, "Достижения", "Полученные награды и их число", current.achievements, !busy) { change(current.copy(achievements = it)) }
        AccountDivider()
        PrivacyRow(AniMixIcons.Wifi, "В сети", "Зелёная точка и время последнего входа", current.presence, !busy) { change(current.copy(presence = it)) }
    }
    Txt("Имя, аватар, баннер, оформление профиля и «О себе» видны всем, кто найдёт вас в поиске. Позиции просмотра не видны никому.",
        AniMixTheme.type.micro, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
    error?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.danger, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
}

/** One part of the profile and its audience as three chips: the choice reads in words, not only by colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyRow(icon: ImageVector, title: String, subtitle: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = AniMixTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        RowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            Txt(subtitle, AniMixTheme.type.caption)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Все", "friends" to "Друзья", "nobody" to "Никто").forEach { (key, label) ->
                    Chip(label, value == key, { if (enabled && value != key) onChange(key) })
                }
            }
        }
    }
}

/** «В сети 5 минут назад», «В сети вчера в 21:40», «В сети 12 окт.»: when someone was last online, in words that fit anyone. */
internal fun lastSeenLabel(at: Long, now: Long = TrustedClockNow()): String {
    if (at <= 0) return "Не в сети"
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    val today = app.animix.core.localDayIndex(now)
    val day = app.animix.core.localDayIndex(at)
    val date = dayToDate(day)
    val clock = app.animix.core.localTimeLabel(at)
    return "В сети " + when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        day == today -> "сегодня в $clock"
        day == today - 1 -> "вчера в $clock"
        else -> "${date.day} ${MonthsShort[date.month - 1]}"
    }
}

private fun TrustedClockNow() = app.animix.core.TrustedClock.now()
private val MonthsShort = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
private fun plural(n: Long, one: String, few: String, many: String) = when {
    n % 100 in 11..14 -> many; n % 10 == 1L -> one; n % 10 in 2..4 -> few; else -> many
}

/** A green dot online, a grey one with the last time otherwise; no dot when nothing is known. */
@Composable
internal fun PresenceLine(online: Boolean?, lastSeenAt: Long?, modifier: Modifier = Modifier) {
    if (online == null && lastSeenAt == null) return
    val colors = AniMixTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (online == true) colors.online else colors.textMuted))
        Spacer(Modifier.width(6.dp))
        Txt(if (online == true) "В сети" else lastSeenLabel(lastSeenAt ?: 0), AniMixTheme.type.caption,
            color = if (online == true) colors.textPrimary else colors.textSecondary, maxLines = 1)
    }
}

/** Profile → bell: new episodes from «Смотрю» and friend requests, newest first; the dots go when the page is left. */
@Composable
fun NotificationsScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    val notices by graph.notifier.notices.collectAsState()
    val (busy, error, run) = rememberSocialAction()
    LaunchedEffect(account.user?.id) { if (account.user != null) run { graph.social.refresh() } }
    DisposableEffect(Unit) { onDispose { graph.scope.launch { graph.notifier.markRead() } } }
    ScreenColumn(padding) {
        PageHeader("Уведомления", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        error?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.accentText, modifier = Modifier.padding(bottom = 12.dp)) }
        val requests = if (account.user != null) social.incomingRequests() else emptyList()
        if (requests.isEmpty() && notices.isEmpty()) {
            MessageState("Новых уведомлений нет",
                message = "Здесь появятся новые серии онгоингов из «Смотрю»" + if (account.user != null) " и заявки в друзья." else ".",
                icon = AniMixIcons.Bell, actionLabel = "Настроить", onAction = { navigator.push(Route.SettingsSection("Notifications")) },
                actionIcon = AniMixIcons.Settings)
            return@ScreenColumn
        }
        if (requests.isNotEmpty()) {
            Txt("Заявки в друзья · ${requests.size}", AniMixTheme.type.heading)
            Spacer(Modifier.height(12.dp))
            requests.forEach { user ->
                SocialUserRow(user, navigator, actionsBelow = true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Принять", true, { if (!busy) run { graph.social.friendAction(user.id) } }, icon = AniMixIcons.Check)
                        Chip("Отклонить", false, { if (!busy) run { graph.social.friendAction(user.id, remove = true) } }, icon = AniMixIcons.Close)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (notices.isNotEmpty()) {
            Txt("Новые серии", AniMixTheme.type.heading)
            Spacer(Modifier.height(12.dp))
            Panel {
                notices.forEachIndexed { index, notice ->
                    if (index > 0) AccountDivider()
                    EpisodeNoticeRow(notice) { navigator.push(Route.Watch(notice.anime, notice.episode.toString())) }
                }
            }
        }
    }
}

/** A poster, the title, what came out and when; an accent dot until the page is left once. */
@Composable
private fun EpisodeNoticeRow(notice: app.animix.data.notify.EpisodeNotice, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(notice.anime.posterUrl, Modifier.width(44.dp).aspectRatio(0.7f), shape = AniMixTheme.shapes.small)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(notice.anime.title, AniMixTheme.type.bodyStrong, maxLines = 2)
            Txt(if (notice.dubbing != null) "Серия ${notice.episode} в озвучке ${notice.dubbing}" else "Вышла серия ${notice.episode}",
                AniMixTheme.type.caption, maxLines = 2)
            Txt(noticeTime(notice.at), AniMixTheme.type.micro)
        }
        if (!notice.read) Box(Modifier.padding(start = 8.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accent))
    }
}

private fun noticeTime(at: Long): String {
    val today = app.animix.core.localDayIndex()
    val day = app.animix.core.localDayIndex(at)
    val clock = app.animix.core.localTimeLabel(at)
    val date = dayToDate(day)
    return when (day) {
        today -> "Сегодня в $clock"
        today - 1 -> "Вчера в $clock"
        else -> "${date.day} ${MonthsShort[date.month - 1]} в $clock"
    }
}

@Composable
private fun SocialSection(title: String) {
    Spacer(Modifier.height(28.dp))
    Txt(title, AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
}

/** One person: avatar or initial, name (opens the profile), and the actions on the right. */
@Composable
private fun SocialUserRow(user: SocialUser, navigator: Navigator, actionsBelow: Boolean = false, online: Boolean? = null, lastSeenAt: Long? = null,
    actions: (@Composable () -> Unit)? = null) {
    val colors = AniMixTheme.colors
    Panel(Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(AniMixTheme.shapes.medium)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { navigator.push(Route.FriendProfile(user.id)) }
                    .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SocialAvatar(user, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(user.name, AniMixTheme.type.bodyStrong, maxLines = 2)
                        PresenceLine(online, lastSeenAt, Modifier.padding(top = 2.dp))
                    }
                    if (actions == null) Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
                }
                if (actions != null && !actionsBelow) { Spacer(Modifier.width(8.dp)); actions() }
            }
            if (actions != null && actionsBelow) Box(Modifier.fillMaxWidth().padding(top = 10.dp, start = 60.dp)) { actions() }
        }
    }
}

/**
 * A person's own photo in a circle, the same as on their profile; the initial on the accent gradient
 * shows underneath while it loads and stays when there is no photo or it fails to load.
 */
@Composable
internal fun SocialAvatar(user: SocialUser, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val url = user.avatar?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(colors.accentGradient),
        contentAlignment = Alignment.Center) {
        Txt(user.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", AniMixTheme.type.bodyStrong, color = colors.onAccent)
        if (url != null) RemoteImage(url, Modifier.matchParentSize(), shape = androidx.compose.foundation.shape.CircleShape, placeholder = false)
    }
}

@Composable
private fun SocialSearchField(value: String, onChange: (String) -> Unit) {
    val colors = AniMixTheme.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(AniMixTheme.shapes.pill).background(colors.surfaceRaised)
            .border(1.dp, colors.outline, AniMixTheme.shapes.pill).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AniMixIcons.Search, null, Modifier.size(18.dp), tint = colors.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt("Найти по имени", AniMixTheme.type.body, color = colors.textMuted, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = AniMixTheme.type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The Horizon Link chip in the player's top bar: always visible when signed in. */
@Composable
fun HorizonButton(graph: AppGraph) {
    val account by graph.account.state.collectAsState()
    if (account.user == null) return
    var open by remember { mutableStateOf(false) }
    app.animix.ui.player.PlayerChip("Horizon Link", AniMixIcons.Share) { open = true }
    if (open) HorizonSheet(graph) { open = false }
}

@Composable
fun HorizonSheet(graph: AppGraph, onDismiss: () -> Unit) {
    val social by graph.social.state.collectAsState()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { graph.social.refresh() }.onFailure { if (it is CancellationException) throw it; notice = "Не удалось обновить устройства" }
            delay(5_000)
        }
    }
    AniMixSheet(onDismiss, title = "Horizon Link", subtitle = "Продолжить эту серию на другом своём устройстве") {
        val others = social.devices.filter { it.id != graph.social.deviceId }
        val online = others.filter { it.online() }
        if (online.isEmpty()) {
            Txt("Других устройств в сети нет. Откройте AniMix на телефоне или ПК под этим же аккаунтом — устройство появится здесь через несколько секунд.",
                AniMixTheme.type.caption)
        } else Panel {
            online.forEach { device ->
                NavRow(device.label, deviceIcon(device), {
                    scope.launch {
                        notice = try { graph.social.send(device.id); "Отправлено. Здесь видео встанет на паузу, когда «${device.label}» откроет серию." }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { "Передача не удалась. Проверьте устройство и повторите." }
                    }
                }, trailing = "В сети")
            }
        }
        val offline = others.filterNot { it.online() }
        if (offline.isNotEmpty()) {
            Txt("Не в сети: " + offline.joinToString { it.label }, AniMixTheme.type.micro, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        }
        notice?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.accentText, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
        Spacer(Modifier.height(12.dp))
    }
}

internal fun deviceIcon(device: HorizonDevice): ImageVector = if (device.platform == "Android") AniMixIcons.Play else AniMixIcons.Monitor

/** An offer from another device to continue here: shown above any screen. */
@Composable
fun HorizonIncoming(graph: AppGraph, navigator: Navigator) {
    val social by graph.social.state.collectAsState()
    val item = social.incoming.firstOrNull() ?: return
    var dismissed by remember(item.id) { mutableStateOf(false) }
    if (dismissed) return
    val from = social.devices.firstOrNull { it.id == item.source }?.label ?: "другого устройства"
    androidx.compose.ui.window.Dialog(onDismissRequest = { dismissed = true }) {
        Column(Modifier.widthIn(max = 400.dp).clip(AniMixTheme.shapes.large).background(AniMixTheme.colors.surfaceRaised).padding(20.dp)) {
            Txt("Horizon Link", AniMixTheme.type.micro, color = AniMixTheme.colors.accentText)
            Txt("Продолжить здесь?", AniMixTheme.type.heading)
            Spacer(Modifier.height(6.dp))
            Txt("${item.playback.anime.title} · серия ${item.playback.episode}, с $from", AniMixTheme.type.caption)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Продолжить", {
                    val play = item.playback
                    graph.watchFlow.pause()
                    graph.library.saveEpisodePosition(play.anime, play.episode, play.positionMs)
                    graph.settings.update { it.copy(preferredQuality = play.quality, dubbingByAnime = it.dubbingByAnime + (play.anime.key to play.dubbingKey)) }
                    graph.social.receive(item)
                    dismissed = true
                    navigator.push(Route.Watch(play.anime, play.episode, item.id))
                }, Modifier.weight(1f), icon = AniMixIcons.Play)
                SecondaryButton("Позже", { dismissed = true }, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Settings → «Аккаунт» → «Устройства»: every device signed in to this account, named after the phone or
 * the PC's motherboard and system. Any other device can be signed out from here after a confirmation.
 */
@Composable
internal fun HorizonDevices(graph: AppGraph) {
    val social by graph.social.state.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    val (busy, error, run) = rememberSocialAction()
    DisposableEffect(Unit) { val release = graph.social.watch(); onDispose { release() } }
    LaunchedEffect(Unit) { runCatching { graph.social.refresh() } }
    Panel {
        if (social.devices.isEmpty()) AccountRow(AniMixIcons.Monitor, "Это устройство", Platform.name)
        social.devices.sortedWith(compareByDescending<HorizonDevice> { it.id == graph.social.deviceId }.thenByDescending { it.seenAt })
            .forEachIndexed { index, device ->
                val self = device.id == graph.social.deviceId
                if (index > 0) AccountDivider()
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RowIcon(deviceIcon(device))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(device.label, AniMixTheme.type.bodyStrong, maxLines = 2)
                            Txt(when { self -> "Это устройство"; device.online() -> "В сети"; else -> lastSeenLabel(device.seenAt) }, AniMixTheme.type.caption)
                        }
                        if (!self && confirm != device.id) {
                            Spacer(Modifier.width(8.dp))
                            Chip("Отключить", false, { confirm = device.id }, icon = AniMixIcons.Close)
                        }
                    }
                    if (confirm == device.id) Column(Modifier.padding(start = RowTextInset, end = 16.dp, bottom = 14.dp)) {
                        Txt("Отключить «${device.label}»? На нём придётся войти заново. Списки и загрузки на устройстве останутся.", AniMixTheme.type.caption)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Отмена", { confirm = null }, Modifier.weight(1f))
                            SecondaryButton("Отключить", { run { graph.social.removeDevice(device.id); confirm = null } },
                                Modifier.weight(1f), icon = AniMixIcons.Close, enabled = !busy)
                        }
                    }
                }
            }
    }
    Txt(error ?: "Начали серию на одном устройстве — продолжите на другом кнопкой «Horizon Link» в плеере.",
        AniMixTheme.type.micro, color = if (error != null) AniMixTheme.colors.danger else AniMixTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}
