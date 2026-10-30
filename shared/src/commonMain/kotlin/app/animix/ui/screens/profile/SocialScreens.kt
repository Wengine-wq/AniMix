package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
private fun rememberSocialAction(): Triple<Boolean, String?, (suspend () -> Unit) -> Unit> {
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

/** Profile → «Друзья»: friends, sent requests, search and who sees the collection. */
@Composable
fun FriendsScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SocialUser>>(emptyList()) }
    var visible by remember { mutableStateOf<Boolean?>(null) }
    val (busy, error, run) = rememberSocialAction()
    LaunchedEffect(account.user?.id) { if (account.user != null) run { graph.social.refresh(); visible = graph.social.privacy() } }
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
                SocialUserRow(user, navigator) {
                    Chip("Удалить", false, { if (!busy) run { graph.social.friendAction(user.id, remove = true) } }, icon = AniMixIcons.Close)
                }
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
        visible?.let { value ->
            Spacer(Modifier.height(28.dp))
            Panel {
                ToggleRow("Показывать друзьям коллекцию и статистику", value, { next -> run { graph.social.privacy(next); visible = next } },
                    AniMixIcons.Bookmark, subtitle = "Списки, часы, активность и достижения. Позиции просмотра не показываются никому.", enabled = !busy)
            }
        }
    }
}

/** Profile → bell: friend requests to accept or decline. */
@Composable
fun NotificationsScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    val (busy, error, run) = rememberSocialAction()
    LaunchedEffect(account.user?.id) { if (account.user != null) run { graph.social.refresh() } }
    ScreenColumn(padding) {
        PageHeader("Уведомления", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        if (account.user == null) {
            MessageState("Войдите в AniMix", message = "Заявки в друзья приходят в онлайн-профиль.", icon = AniMixIcons.Bell)
            return@ScreenColumn
        }
        error?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.accentText, modifier = Modifier.padding(bottom = 12.dp)) }
        val requests = social.incomingRequests()
        if (requests.isEmpty()) {
            MessageState("Новых уведомлений нет", message = "Здесь появятся заявки в друзья.", icon = AniMixIcons.Bell)
            return@ScreenColumn
        }
        Txt("Заявки в друзья", AniMixTheme.type.heading)
        requests.forEach { user ->
            SocialUserRow(user, navigator) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Принять", true, { if (!busy) run { graph.social.friendAction(user.id) } }, icon = AniMixIcons.Check)
                    Chip("Отклонить", false, { if (!busy) run { graph.social.friendAction(user.id, remove = true) } }, icon = AniMixIcons.Close)
                }
            }
        }
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
private fun SocialUserRow(user: SocialUser, navigator: Navigator, actions: @Composable () -> Unit) {
    Panel(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                NavRow(user.name, AniMixIcons.User, { navigator.push(Route.FriendProfile(user.id)) })
            }
            Spacer(Modifier.width(8.dp))
            actions()
        }
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

/** A friend's profile: the same layout as one's own, from what the friend shares. */
@Composable
fun FriendProfileScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val overview = rememberLoad(id) { graph.social.overview(id) }
    LaunchedEffect(id) { while (isActive) { delay(15_000); overview.refresh() } }
    ScreenColumn(padding) {
        PageHeader("Профиль", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        when (val state = overview.value) {
            Load.Loading -> LoadingIndicator()
            is Load.Failed -> MessageState("Не удалось открыть профиль", message = state.error.userMessage(), icon = AniMixIcons.User,
                actionLabel = "Повторить", onAction = overview::refresh)
            is Load.Ready -> {
                val raw = state.value
                val user = raw.getValue("user").jsonObject
                val lib = raw["library"]?.takeIf { it != JsonNull }?.let { AppJson.decodeFromJsonElement<LibraryData>(it) }
                val awards = raw["achievements"]?.takeIf { it != JsonNull }?.let { AppJson.decodeFromJsonElement<AchievementState>(it) } ?: AchievementState()
                val profile = lib?.profile ?: LocalProfile(name = user["display_name"]?.jsonPrimitive?.contentOrNull ?: "AniMix",
                    about = user["about"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    avatar = user["avatar_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) },
                    banner = user["banner_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) })
                val badge = graph.achievements.all.firstOrNull { it.id == profile.badgeAchievementId }
                    ?.let { app.animix.data.achievements.AchievementProgress(it, 1, awards.unlocked[it.id], awards.historical[it.id]) }
                ProfileIdentity(profile, badge, level = lib?.level(awards.unlocked.size))
                Spacer(Modifier.height(12.dp))
                Txt(if (raw["online"]?.jsonPrimitive?.booleanOrNull == true) "В сети" else "Не в сети", AniMixTheme.type.caption)
                if (lib == null) {
                    Spacer(Modifier.height(24.dp))
                    Txt("Коллекция и подробная статистика видны взаимным друзьям с открытой коллекцией.", AniMixTheme.type.body)
                } else {
                    val stats = lib.stats()
                    Spacer(Modifier.height(24.dp))
                    Row {
                        ProfileStat(profileHours(stats.totalMinutes), "часов в AniMix", Modifier.weight(1f))
                        ProfileStat("${stats.episodes}", "серий", Modifier.weight(1f))
                        ProfileStat("${stats.completed}", "завершено", Modifier.weight(1f))
                        ProfileStat("${awards.unlocked.size + awards.historical.keys.count { it !in awards.unlocked }}", "достижений", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(32.dp))
                    CollectionDistribution(lib, null)
                    Spacer(Modifier.height(32.dp))
                    Txt("Активность", AniMixTheme.type.heading)
                    Spacer(Modifier.height(16.dp))
                    ActivityChart(stats.lastDays)
                    if (stats.bestStreak > 1) Txt("Рекорд: ${stats.bestStreak} ${profileDaysWord(stats.bestStreak)} подряд", AniMixTheme.type.micro,
                        modifier = Modifier.padding(top = 8.dp))
                    if (stats.topGenres.isNotEmpty()) {
                        Spacer(Modifier.height(32.dp))
                        Txt("Любимые жанры", AniMixTheme.type.heading)
                        Spacer(Modifier.height(12.dp))
                        GenreTaste(stats.topGenres)
                    }
                    Spacer(Modifier.height(32.dp))
                    Txt("Коллекция", AniMixTheme.type.heading)
                    Spacer(Modifier.height(12.dp))
                    Panel {
                        lib.entries.values.filter { it.status != null || it.favorite }.sortedBy { it.anime.title }.forEach { entry ->
                            NavRow(entry.anime.title, AniMixIcons.Play, { navigator.push(Route.Detail(entry.anime)) }, trailing = entry.status?.label ?: "Избранное")
                        }
                    }
                }
            }
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

/** Settings → «Аккаунт» → Horizon Link: this account's devices and whether they are online. */
@Composable
internal fun HorizonDevices(graph: AppGraph) {
    val social by graph.social.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { graph.social.refresh() } }
    Txt("Начали серию на одном устройстве — продолжите на другом: кнопка «Horizon Link» в плеере. Нужен один аккаунт и интернет на обоих.",
        AniMixTheme.type.caption)
    Spacer(Modifier.height(12.dp))
    Panel {
        if (social.devices.isEmpty()) NavRow("Это устройство", AniMixIcons.Monitor, {}, trailing = Platform.name)
        social.devices.sortedByDescending { it.seenAt }.forEach { device ->
            val self = device.id == graph.social.deviceId
            NavRow(device.label + if (self) " · это устройство" else "", deviceIcon(device), {},
                trailing = if (device.online()) "В сети" else "Не в сети")
        }
    }
}
