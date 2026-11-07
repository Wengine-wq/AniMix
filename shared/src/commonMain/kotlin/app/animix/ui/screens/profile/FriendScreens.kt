package app.animix.ui.screens.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.core.AppJson
import app.animix.data.account.PublicSummary
import app.animix.data.account.lastDays
import app.animix.data.achievements.AchievementProgress
import app.animix.data.library.*
import app.animix.model.Anime
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.theme.AniMixTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** One title of a friend's collection: the card and the list facts, nothing about playback. */
@Serializable
internal data class FriendTitle(val anime: Anime, val status: ListStatus? = null, val favorite: Boolean = false,
    val score: Int? = null, val episodesWatched: Int = 0, val updatedAt: Long = 0)

/** What a friend's page may show, part by part, as the server answered for this viewer. */
internal class FriendView(val raw: JsonObject) {
    private val user = raw.getValue("user").jsonObject
    val profile: LocalProfile = raw["profile"]?.takeIf { it != JsonNull }?.let { runCatching { AppJson.decodeFromJsonElement<LocalProfile>(it) }.getOrNull() }
        ?: LocalProfile(name = user["display_name"]?.jsonPrimitive?.contentOrNull ?: "AniMix",
            about = user["about"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            avatar = user["avatar_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) },
            banner = user["banner_url"]?.jsonPrimitive?.contentOrNull?.let { ProfileMedia(url = it) })
    val summary: PublicSummary? = raw["summary"]?.takeIf { it != JsonNull }?.let { runCatching { AppJson.decodeFromJsonElement<PublicSummary>(it) }.getOrNull() }
    val collection: List<FriendTitle>? = raw["collection"]?.takeIf { it != JsonNull }?.let {
        runCatching { AppJson.decodeFromJsonElement<List<FriendTitle>>(it) }.getOrNull()
    }
    private val awards = raw["achievements"]?.takeIf { it != JsonNull }?.jsonObject
    val unlocked: Map<String, Long>? = awards?.get("unlocked")?.let { AppJson.decodeFromJsonElement<Map<String, Long>>(it) }
    val historical: Map<String, Long> = awards?.get("historical")?.let { AppJson.decodeFromJsonElement<Map<String, Long>>(it) }.orEmpty()
    private val can = raw["can"]?.jsonObject
    /** Servers before 1.2 send the collection with the whole library: treat it as one visible part. */
    val canCollection = can?.get("collection")?.jsonPrimitive?.booleanOrNull ?: (raw["visible"]?.jsonPrimitive?.booleanOrNull == true)
    val canStats = can?.get("stats")?.jsonPrimitive?.booleanOrNull ?: canCollection
    val canAchievements = can?.get("achievements")?.jsonPrimitive?.booleanOrNull ?: canCollection
    val online = raw["online"]?.jsonPrimitive?.booleanOrNull
    val lastSeenAt = raw["last_seen_at"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    val level: ProfileLevel? = summary?.let { ProfileLevel(it.level, 0, (it.levelFraction * 1000).toLong(), 1000) }
}

/** A friend's page: the same header as one's own, then only what the friend shares; the collection and awards open on their own pages. */
@Composable
fun FriendProfileScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val overview = rememberLoad(id) { FriendView(graph.social.overview(id)) }
    val social by graph.social.state.collectAsState()
    val (busy, actionError, run) = rememberSocialAction()
    var confirmRemoval by remember(id) { mutableStateOf(false) }
    // Presence changes while the page is open; the rest is cached on the server side of the request.
    LaunchedEffect(id) { while (isActive) { delay(60_000); overview.refresh() } }
    Box(Modifier.fillMaxSize()) {
    ProfileDesignLayer(profileDesign((overview.value as? Load.Ready)?.value?.profile?.design), Modifier.fillMaxWidth().height(ProfileDesignHeight), animated = true)
    ScreenColumn(padding) {
        PageHeader("Профиль", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        when (val state = overview.value) {
            Load.Loading -> LoadingIndicator()
            is Load.Failed -> MessageState("Не удалось открыть профиль", message = state.error.userMessage(), icon = AniMixIcons.User,
                actionLabel = "Повторить", onAction = overview::refresh)
            is Load.Ready -> {
                val view = state.value
                val profile = view.profile
                app.animix.Ambient(profile.banner?.url ?: profile.avatar?.url)
                val badge = graph.achievements.all.firstOrNull { it.id == profile.badgeAchievementId }
                    ?.let { AchievementProgress(it, 1, view.unlocked?.get(it.id), view.historical[it.id]) }
                ProfileIdentity(profile, badge, level = view.level, online = view.online, lastSeenAt = view.lastSeenAt)
                val status = social.friends.firstOrNull { it.id == id }?.status ?: "none"
                // Becoming friends is the first thing to do on a stranger's page, so it sits under the name.
                if (status != "friends") {
                    Spacer(Modifier.height(20.dp))
                    when (status) {
                        "incoming" -> PrimaryButton("Принять заявку", { run { graph.social.friendAction(id); overview.refresh() } }, Modifier.fillMaxWidth(), icon = AniMixIcons.Check, enabled = !busy)
                        "outgoing" -> SecondaryButton("Отменить заявку", { run { graph.social.friendAction(id, remove = true) } }, Modifier.fillMaxWidth(), enabled = !busy)
                        else -> PrimaryButton("Добавить в друзья", { run { graph.social.friendAction(id) } }, Modifier.fillMaxWidth(), icon = AniMixIcons.User, enabled = !busy)
                    }
                }
                // The same page as one's own: numbers with the level, the collection ring with a short shelf of
                // recent titles, the newest awards, activity and genres. Whole lists open on their own pages.
                val summary = view.summary
                if (summary != null) FriendNumbers(summary)
                // Numbers and the level are counted on the friend's own device; an older AniMix there sends none yet.
                else if (view.canStats) {
                    Spacer(Modifier.height(24.dp))
                    GlassPlate {
                        Txt("Уровень и статистика", AniMixTheme.type.heading)
                        Spacer(Modifier.height(6.dp))
                        Txt("Появятся, когда ${profile.name} откроет AniMix 1.2 или новее: часы, серии и уровень считаются на устройстве друга.",
                            AniMixTheme.type.caption)
                    }
                }
                val titles = view.collection
                if (summary != null || titles != null) {
                    Spacer(Modifier.height(12.dp))
                    GlassPlate {
                        val counts = summary?.let { s -> ListStatus.entries.associateWith { s.statuses[it.name] ?: 0 } }
                            ?: ListStatus.entries.associateWith { status -> titles.orEmpty().count { it.status == status } }
                        CollectionDistribution(counts, onOpen = if (titles != null) { { navigator.push(Route.FriendCollection(id)) } } else null)
                        val recent = titles.orEmpty().sortedByDescending { it.updatedAt }.take(10)
                        if (recent.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            Txt("Недавно в коллекции", AniMixTheme.type.subheading)
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recent, key = { it.anime.key }) { title ->
                                    AnimeCard(title.anime, { navigator.push(Route.Detail(title.anime)) }, Modifier.width(96.dp),
                                        caption = title.status?.label ?: if (title.favorite) "Избранное" else null)
                                }
                            }
                        }
                    }
                }
                val awards = view.unlocked
                if (awards != null) FriendAwards(graph, view, awards) { navigator.push(Route.FriendAchievements(id)) }
                if (summary != null) FriendActivity(summary)
                val hidden = buildList {
                    if (!view.canCollection) add("коллекцию")
                    if (!view.canStats) add("статистику")
                    if (!view.canAchievements) add("достижения")
                }
                if (hidden.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AniMixIcons.Lock, null, Modifier.padding(top = 2.dp).size(16.dp), tint = AniMixTheme.colors.textMuted)
                        Spacer(Modifier.width(10.dp))
                        Txt("${profile.name} скрывает ${hidden.joinToString(", ")}" + if (status != "friends") " или показывает только друзьям." else ".",
                            AniMixTheme.type.caption, modifier = Modifier.weight(1f))
                    }
                }
                // Friendship lives with the person, not as a button on every row of the list.
                if (status == "friends") {
                    Spacer(Modifier.height(32.dp))
                    if (!confirmRemoval) SecondaryButton("Удалить из друзей", { confirmRemoval = true }, Modifier.fillMaxWidth(), icon = AniMixIcons.Close)
                    else {
                        Txt("Удалить «${profile.name}» из друзей?", AniMixTheme.type.bodyStrong)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("Отмена", { confirmRemoval = false }, Modifier.weight(1f))
                            SecondaryButton("Удалить", { confirmRemoval = false; run { graph.social.friendAction(id, remove = true); overview.refresh() } },
                                Modifier.weight(1f), icon = AniMixIcons.Trash, enabled = !busy)
                        }
                    }
                }
                actionError?.let { Txt(it, AniMixTheme.type.caption, color = AniMixTheme.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
    }
}

/** The friend's numbers on the plate of one's own profile; the level is a number of its own here, not only the ring's bubble. */
@Composable
private fun FriendNumbers(summary: PublicSummary) {
    Spacer(Modifier.height(24.dp))
    GlassPlate {
        ProfileStats(listOf(
            StatItem("${summary.level}", "уровень"),
            StatItem(profileHours(summary.minutes), "часов в AniMix"),
            StatItem("${summary.episodes}", "серий"),
            StatItem("${summary.completed}", "завершено"),
        ))
    }
}

/** The newest awards as icons, with the count and the way to all of them. */
@Composable
private fun FriendAwards(graph: AppGraph, view: FriendView, awards: Map<String, Long>, onOpen: () -> Unit) {
    val earned = graph.achievements.all.mapNotNull { a ->
        val at = awards[a.id] ?: view.historical[a.id] ?: return@mapNotNull null
        a to at
    }.sortedByDescending { it.second }
    Spacer(Modifier.height(12.dp))
    GlassPlate {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Достижения", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
            Txt("Открыть", AniMixTheme.type.label, color = AniMixTheme.colors.accent,
                modifier = Modifier.clip(AniMixTheme.shapes.pill).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 8.dp))
        }
        Txt("${earned.size} из ${graph.achievements.all.size}", AniMixTheme.type.caption)
        if (earned.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            // A row of the newest ones; their names are on the achievements page.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                earned.take(6).forEach { (achievement, _) -> AchievementIcon(achievement, true, 40.dp) }
            }
        }
    }
}

@Composable
private fun FriendActivity(summary: PublicSummary) {
    val days = summary.lastDays()
    if (days.any { it > 0 }) {
        Spacer(Modifier.height(12.dp))
        GlassPlate {
            Txt("Активность", AniMixTheme.type.heading)
            Spacer(Modifier.height(16.dp))
            ActivityChart(days)
            if (summary.bestStreak > 1) Txt("Рекорд: ${summary.bestStreak} ${profileDaysWord(summary.bestStreak)} подряд", AniMixTheme.type.micro,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (summary.genres.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        GlassPlate {
            Txt("Любимые жанры", AniMixTheme.type.heading)
            Spacer(Modifier.height(12.dp))
            GenreTaste(summary.genres)
        }
    }
}

/** A friend's lists: the same chips and poster grid as one's own «Списки», read-only. */
@Composable
fun FriendCollectionScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val overview = rememberLoad(id) { FriendView(graph.social.overview(id)) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val view = (overview.value as? Load.Ready)?.value
    val titles = view?.collection.orEmpty()
    val categories = listOf<ListStatus?>(null) + ListStatus.entries
    val firstFilled = categories.firstOrNull { item -> if (item == null) titles.any { it.favorite } else titles.any { it.status == item } }
    val status = if (selectedName != null) ListStatus.entries.firstOrNull { it.name == selectedName } else firstFilled
    val favoritesChosen = selectedName == FAVORITES || (selectedName == null && firstFilled == null && titles.any { it.favorite })
    val shown = titles.filter { if (favoritesChosen || status == null) it.favorite else it.status == status }.sortedByDescending { it.updatedAt }
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(direction) + 20.dp, end = padding.calculateEndPadding(direction) + 20.dp,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 12.dp)) {
                PageHeader(view?.profile?.name?.let { "Коллекция · $it" } ?: "Коллекция", { navigator.back() })
                if (titles.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { item ->
                            val count = if (item == null) titles.count { it.favorite } else titles.count { it.status == item }
                            val label = item?.label ?: "Избранное"
                            val selected = if (item == null) favoritesChosen else !favoritesChosen && item == status
                            Chip(if (count > 0) "$label · $count" else label, selected,
                                { selectedName = item?.name ?: FAVORITES }, icon = if (item == null) AniMixIcons.Heart else null)
                        }
                    }
                }
            }
        }
        when {
            overview.value is Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingIndicator(Modifier.padding(top = 40.dp)) }
            overview.value is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState("Не удалось открыть коллекцию", message = (overview.value as Load.Failed).error.userMessage(),
                    icon = AniMixIcons.Bookmark, actionLabel = "Повторить", onAction = overview::refresh)
            }
            view?.collection == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState("Коллекция скрыта", message = "${view?.profile?.name ?: "Пользователь"} показывает её не всем.", icon = AniMixIcons.Lock)
            }
            shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                MessageState(if (titles.isEmpty()) "Коллекция пока пуста" else "В этом списке пусто", icon = AniMixIcons.Bookmark)
            }
            else -> items(shown, key = { it.anime.key }) { title ->
                AnimeCard(title.anime, onClick = { navigator.push(Route.Detail(title.anime)) },
                    caption = title.score?.let { "Оценка $it" })
            }
        }
    }
}

/** What a friend has earned, newest first, in the rows of one's own «Достижения». */
@Composable
fun FriendAchievementsScreen(graph: AppGraph, navigator: Navigator, id: String, padding: PaddingValues) {
    val overview = rememberLoad(id) { FriendView(graph.social.overview(id)) }
    ScreenColumn(padding) {
        PageHeader("Достижения", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        when (val state = overview.value) {
            Load.Loading -> LoadingIndicator()
            is Load.Failed -> MessageState("Не удалось открыть достижения", message = state.error.userMessage(), icon = AniMixIcons.Trophy,
                actionLabel = "Повторить", onAction = overview::refresh)
            is Load.Ready -> {
                val view = state.value
                val unlocked = view.unlocked
                if (unlocked == null) {
                    MessageState("Достижения скрыты", message = "${view.profile.name} показывает их не всем.", icon = AniMixIcons.Lock)
                    return@ScreenColumn
                }
                val earned = graph.achievements.all.mapNotNull { a ->
                    val at = unlocked[a.id]; val history = view.historical[a.id]
                    if (at == null && history == null) null else AchievementProgress(a, a.trigger.threshold, at, history)
                }.sortedByDescending { it.unlockedAt ?: it.historyUnlockedAt ?: 0 }
                Txt("${view.profile.name} · ${earned.size} из ${graph.achievements.all.size}", AniMixTheme.type.caption)
                Spacer(Modifier.height(16.dp))
                if (earned.isEmpty()) MessageState("Наград пока нет", icon = AniMixIcons.Trophy)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { earned.forEach { AchievementRow(it) } }
            }
        }
    }
}
private const val FAVORITES = "favorites"
