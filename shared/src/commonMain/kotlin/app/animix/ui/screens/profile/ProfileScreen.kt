package app.animix.ui.screens.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.animix.AppGraph
import app.animix.data.achievements.AchievementProgress
import app.animix.data.library.*
import app.animix.ui.components.*
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.navigation.Navigator
import app.animix.ui.navigation.Route
import app.animix.ui.navigation.Tab
import app.animix.ui.theme.AniMixMotion
import app.animix.ui.theme.AniMixTheme
import kotlin.time.Instant

@Composable
fun ProfileScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val account by graph.account.state.collectAsState()
    val social by graph.social.state.collectAsState()
    val stats = remember(library) { library.stats() }
    val level = remember(library, achievements) { library.level(achievements.count { it.earned }) }
    val badge = achievements.firstOrNull { it.unlocked && it.achievement.id == library.profile.badgeAchievementId }
    ScreenColumn(contentPadding) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Профиль", AniMixTheme.type.title, modifier = Modifier.weight(1f))
            if (account.user != null) {
                val requests = social.incomingRequests().size
                Box {
                    IconCircle(AniMixIcons.Bell, { navigator.push(Route.Notifications) },
                        contentDescription = if (requests > 0) "Уведомления: заявок в друзья — $requests" else "Уведомления")
                    if (requests > 0) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(AniMixTheme.colors.accent),
                        contentAlignment = Alignment.Center) { Txt("$requests", AniMixTheme.type.micro, color = AniMixTheme.colors.onAccent) }
                }
                Spacer(Modifier.width(8.dp))
            }
            IconCircle(AniMixIcons.Edit, { navigator.push(Route.EditProfile()) }, contentDescription = "Редактировать профиль")
            Spacer(Modifier.width(8.dp))
            // Menu, not just settings: achievements, friends, level, history, settings and «О приложении».
            IconCircle(AniMixIcons.Menu, { navigator.push(Route.Menu) }, contentDescription = "Меню")
        }
        Spacer(Modifier.height(20.dp))
        val self = social.devices.firstOrNull { it.id == graph.social.deviceId }
        ProfileIdentity(library.profile, badge, onEdit = { navigator.push(Route.EditProfile()) },
            onBadge = { navigator.push(Route.Achievements) }, level = level,
            online = if (account.user != null) self?.online() ?: (social.error == null) else null,
            lastSeenAt = self?.seenAt)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            ProfileStat(profileHours(stats.totalMinutes), "часов в AniMix", Modifier.weight(1f))
            ProfileStat(stats.episodes.toString(), "серий", Modifier.weight(1f))
            ProfileStat(stats.completed.toString(), "завершено", Modifier.weight(1f))
            // Friends live with the other numbers of the profile; a tap opens the list.
            ProfileStat(if (account.user != null) social.acceptedFriends().size.toString() else "—", "друзей",
                Modifier.weight(1f).clickable { navigator.push(Route.Friends) })
        }
        Spacer(Modifier.height(32.dp))
        CollectionDistribution(library, onOpen = { navigator.select(Tab.Lists) })
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Активность", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
            if (stats.streak > 0) {
                Icon(AniMixIcons.Flame, null, Modifier.size(16.dp), tint = AniMixTheme.colors.accent)
                Spacer(Modifier.width(6.dp))
                Txt("${stats.streak} ${profileDaysWord(stats.streak)} подряд", AniMixTheme.type.caption)
            } else Txt("14 дней", AniMixTheme.type.caption)
        }
        Spacer(Modifier.height(16.dp))
        if (stats.lastDays.all { it == 0 }) {
            Txt("Здесь появятся ваши просмотры. Минута сегодня начнёт серию дней.", AniMixTheme.type.caption)
        } else {
            ActivityChart(stats.lastDays)
            if (stats.bestStreak > 1) Txt("Рекорд: ${stats.bestStreak} ${profileDaysWord(stats.bestStreak)} подряд",
                AniMixTheme.type.micro, modifier = Modifier.padding(top = 8.dp))
        }
        if (stats.topGenres.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Txt("Любимые жанры", AniMixTheme.type.heading)
            Spacer(Modifier.height(12.dp))
            GenreTaste(stats.topGenres)
        }
    }
}

@Composable
internal fun ProfileIdentity(profile: LocalProfile, badge: AchievementProgress?, onEdit: (() -> Unit)? = null, onBadge: (() -> Unit)? = null,
    level: ProfileLevel? = null, online: Boolean? = null, lastSeenAt: Long? = null) {
    val colors = AniMixTheme.colors
    val avatarInteraction = remember { MutableInteractionSource() }
    val badgeInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val avatarSize = if (wide) 104.dp else 88.dp
        val bannerHeight = if (wide) 204.dp else 152.dp
        Column {
            // The avatar overlaps the banner's edge; the name and presence sit to its right, under the banner.
            Box(Modifier.fillMaxWidth()) {
                ProfileBanner(profile.banner, Modifier.fillMaxWidth().height(bannerHeight))
                Row(Modifier.fillMaxWidth().padding(top = bannerHeight - avatarSize / 2, start = 16.dp)) {
                    ProfileAvatar(profile, avatarSize, level = level,
                        clickModifier = Modifier
                        .then(if (onEdit != null) Modifier.semantics { contentDescription = "Изменить аватар и оформление профиля" }
                            .clickable(avatarInteraction, indication = null, role = Role.Button, onClick = onEdit)
                            .focusOutline(avatarInteraction, CircleShape) else Modifier))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).padding(top = avatarSize / 2 + 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(profile.name, if (wide) AniMixTheme.type.title else AniMixTheme.type.heading, modifier = Modifier.weight(1f, fill = false))
                            if (badge != null) {
                                Box(Modifier.size(40.dp).semantics { contentDescription = "Значок профиля: ${badge.achievement.name}" }
                                    .then(if (onBadge != null) Modifier.clip(CircleShape)
                                        .clickable(badgeInteraction, indication = null, role = Role.Button, onClick = onBadge)
                                        .focusOutline(badgeInteraction, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                                    AchievementIcon(badge.achievement, true, 28.dp)
                                }
                            }
                        }
                        profile.handle?.takeIf { it.isNotBlank() }?.let {
                            Txt("@$it", AniMixTheme.type.caption, color = colors.textSecondary)
                        }
                        PresenceLine(online, lastSeenAt, Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (profile.about.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Txt(profile.about, AniMixTheme.type.body, color = colors.textSecondary)
            }
            if (profile.createdAt > 0) {
                Spacer(Modifier.height(8.dp))
                Txt(profileJoinedLabel(profile.createdAt), AniMixTheme.type.micro)
            }
        }
    }
}

@Composable
internal fun ProfileBanner(media: ProfileMedia?, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val model = remember(media) { media?.imageModel() }
    Box(modifier.clip(AniMixTheme.shapes.large).background(colors.surfaceRaised)) {
        // The default cover uses the existing palette, also serving as the failed-image fallback.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(colors.surfaceRaised, colors.accentSoft, colors.surface)))
            drawCircle(colors.accent.copy(alpha = 0.13f), size.width * 0.35f, Offset(size.width * 0.82f, size.height * 0.2f))
            val horizon = Path().apply {
                moveTo(0f, size.height * 0.85f)
                cubicTo(size.width * 0.3f, size.height * 0.05f, size.width * 0.65f, size.height * 1.1f, size.width, size.height * 0.35f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(horizon, colors.accent.copy(alpha = 0.16f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, colors.surface.copy(alpha = 0.4f))))
        }
        if (model != null) RemoteImage(model, Modifier.fillMaxSize(), shape = AniMixTheme.shapes.large, placeholder = false)
    }
}

@Composable
internal fun ProfileAvatar(profile: LocalProfile, size: Dp, modifier: Modifier = Modifier, level: ProfileLevel? = null,
    clickModifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val model = remember(profile.avatar) { profile.avatar?.imageModel() }
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().then(clickModifier).clip(CircleShape).background(colors.background).padding(5.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                val initial = profile.name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
                if (initial != null) Txt(initial, AniMixTheme.type.title, color = colors.onAccent)
                else Icon(AniMixIcons.User, null, Modifier.size(32.dp), tint = colors.onAccent)
                if (model != null) RemoteImage(model, Modifier.fillMaxSize(), shape = CircleShape, placeholder = false)
            }
        }
        if (level != null) LevelRing(level, Modifier.matchParentSize())
    }
}

/**
 * Experience as a ring in the avatar's frame, filled from the top, with the level in a small
 * bubble on the ring. No XP numbers: the ring is the progress to the next level.
 */
@Composable
private fun LevelRing(level: ProfileLevel, modifier: Modifier) {
    val colors = AniMixTheme.colors
    val fraction by app.animix.ui.theme.animateMotionFloatAsState(level.fraction, androidx.compose.animation.core.tween(AniMixMotion.Page))
    val percent = (level.fraction * 100).toInt()
    Box(modifier.semantics { contentDescription = "Уровень ${level.level}, до следующего уровня пройдено $percent %" }) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = 2.5.dp.toPx()
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(colors.outline, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (fraction > 0f) drawArc(colors.accent, -90f, 360f * fraction, false, topLeft, arc,
                style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp).clip(AniMixTheme.shapes.pill)
            .background(colors.background).padding(2.dp).clip(AniMixTheme.shapes.pill).background(colors.accent)
            .padding(horizontal = 8.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
            Txt("${level.level}", AniMixTheme.type.label, color = colors.onAccent, maxLines = 1)
        }
    }
}

@Composable
internal fun ProfileStat(value: String, label: String, modifier: Modifier) {
    // Four numbers share one row: long ones and enlarged system text step down to `heading`, so they never touch.
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
    Column(modifier.padding(end = 8.dp)) {
        Txt(value, if (value.length > 3 || large) AniMixTheme.type.heading else AniMixTheme.type.title, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Txt(label, AniMixTheme.type.micro)
    }
}

@Composable
internal fun CollectionDistribution(library: LibraryData, onOpen: (() -> Unit)?) {
    val colors = AniMixTheme.colors
    val statuses = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.Planned, ListStatus.OnHold, ListStatus.Dropped)
    val counts = statuses.map { status -> library.entries.values.count { it.status == status } }
    val total = counts.sum()
    val palette = listOf(colors.accent, colors.accentStrong, colors.textSecondary, colors.star, colors.textMuted)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt("Коллекция", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        // Lists are edited where they live: the «Списки» tab, one tap from here.
        Txt("$total тайтлов" + if(onOpen!=null)" · открыть" else "", AniMixTheme.type.label, color = colors.accent,
            modifier = Modifier.clip(AniMixTheme.shapes.pill).then(if(onOpen!=null)Modifier.clickable(onClick=onOpen) else Modifier).padding(horizontal = 8.dp, vertical = 8.dp))
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(112.dp).semantics { contentDescription = "Всего в списках: $total" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = 13.dp.toPx()
                val diameter = size.minDimension - stroke
                val origin = Offset(stroke / 2, stroke / 2)
                var start = -90f
                if (total == 0) drawArc(colors.outline, 0f, 360f, false, origin, Size(diameter, diameter), style = Stroke(stroke))
                else counts.forEachIndexed { index, count ->
                    val angle = count.toFloat() / total * 360f
                    if (count > 0) drawArc(palette[index], start + 2f, (angle - 4f).coerceAtLeast(1f), false,
                        origin, Size(diameter, diameter), style = Stroke(stroke))
                    start += angle
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(total.toString(), AniMixTheme.type.title)
                Txt("в списках", AniMixTheme.type.micro)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            statuses.forEachIndexed { index, status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(palette[index]))
                    Spacer(Modifier.width(8.dp))
                    Txt(status.label, AniMixTheme.type.caption, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Txt(counts[index].toString(), AniMixTheme.type.label)
                }
            }
        }
    }
}

@Composable
internal fun ActivityChart(minutes: List<Int>) {
    val colors = AniMixTheme.colors
    val max = (minutes.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        minutes.forEachIndexed { index, value ->
            Box(Modifier.weight(1f).fillMaxHeight((value.toFloat() / max).coerceAtLeast(0.04f))
                .clip(AniMixTheme.shapes.small).background(when {
                    index == minutes.lastIndex && value > 0 -> colors.accent
                    value > 0 -> colors.accent.copy(alpha = 0.35f)
                    else -> colors.outline
                }))
        }
    }
    Row(Modifier.padding(top = 8.dp)) {
        Txt("13 дней назад", AniMixTheme.type.micro, modifier = Modifier.weight(1f))
        Txt(if ((minutes.lastOrNull() ?: 0) > 0) "Сегодня · ${minutes.last()} мин" else "Сегодня", AniMixTheme.type.micro)
    }
}

@Composable
internal fun GenreTaste(genres: List<Pair<String, Int>>) {
    val max = genres.maxOf { it.second }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        genres.forEach { (name, weight) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(name, AniMixTheme.type.caption, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                ProgressLine(weight.toFloat() / max, Modifier.width(112.dp))
            }
        }
    }
}

internal fun profileHours(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes < 600 -> "${minutes / 60},${minutes % 60 * 10 / 60}"
    else -> "${minutes / 60}"
}

internal fun profileDaysWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

private fun profileJoinedLabel(millis: Long): String {
    val date = Instant.fromEpochMilliseconds(millis).toString()
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    val month = date.substring(5, 7).toIntOrNull()?.minus(1)?.let(months::getOrNull) ?: return ""
    return "В AniMix с $month ${date.take(4)}"
}
