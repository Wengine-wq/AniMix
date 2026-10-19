package app.animix.ui.screens.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import app.animix.ui.theme.AniMixTheme
import kotlin.time.Instant

@Composable
fun ProfileScreen(graph: AppGraph, navigator: Navigator, contentPadding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val stats = remember(library) { library.stats() }
    val level = remember(library, achievements) { library.level(achievements.count { it.earned }) }
    val badge = achievements.firstOrNull { it.unlocked && it.achievement.id == library.profile.badgeAchievementId }
    ScreenColumn(contentPadding) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("Профиль", AniMixTheme.type.title, modifier = Modifier.weight(1f))
            IconCircle(AniMixIcons.Edit, { navigator.push(Route.EditProfile()) }, contentDescription = "Редактировать профиль")
            Spacer(Modifier.width(8.dp))
            IconCircle(AniMixIcons.Sliders, { navigator.push(Route.Settings) }, contentDescription = "Настройки")
        }
        Spacer(Modifier.height(20.dp))
        ProfileIdentity(library.profile, badge, onEdit = { navigator.push(Route.EditProfile()) },
            onBadge = { navigator.push(Route.Achievements) })
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt("Уровень ${level.level}", AniMixTheme.type.label, color = AniMixTheme.colors.accentText, modifier = Modifier.weight(1f))
            Txt("${level.progressXp} / ${level.nextLevelXp} XP", AniMixTheme.type.micro)
        }
        ProgressLine(level.fraction, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            ProfileStat(profileHours(stats.totalMinutes), "часов в AniMix", Modifier.weight(1f))
            ProfileStat(stats.episodes.toString(), "серий", Modifier.weight(1f))
            ProfileStat(stats.completed.toString(), "завершено", Modifier.weight(1f))
        }
        Spacer(Modifier.height(32.dp))
        CollectionDistribution(library)
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
internal fun ProfileIdentity(profile: LocalProfile, badge: AchievementProgress?, onEdit: (() -> Unit)? = null, onBadge: (() -> Unit)? = null) {
    val colors = AniMixTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val avatarSize = if (wide) 104.dp else 88.dp
        val bannerHeight = if (wide) 204.dp else 152.dp
        Column {
            Box(Modifier.fillMaxWidth().height(bannerHeight + avatarSize / 2)) {
                ProfileBanner(profile.banner, Modifier.fillMaxWidth().height(bannerHeight))
                ProfileAvatar(profile, avatarSize, Modifier.align(Alignment.BottomStart).padding(start = 16.dp)
                    .then(if (onEdit != null) Modifier.semantics { contentDescription = "Изменить аватар и оформление профиля" }
                        .clickable(onClick = onEdit) else Modifier))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(profile.name, AniMixTheme.type.title, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Box(Modifier.size(44.dp).semantics { contentDescription = "Значок профиля: ${badge.achievement.name}" }
                        .then(if (onBadge != null) Modifier.clip(CircleShape).clickable(onClick = onBadge) else Modifier), contentAlignment = Alignment.Center) {
                        AchievementIcon(badge.achievement, true, 32.dp)
                    }
                }
            }
            profile.handle?.takeIf { it.isNotBlank() }?.let {
                Txt("@$it", AniMixTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            if (profile.about.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
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
internal fun ProfileAvatar(profile: LocalProfile, size: Dp, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val model = remember(profile.avatar) { profile.avatar?.imageModel() }
    Box(modifier.size(size).clip(CircleShape).background(colors.background).padding(5.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
            val initial = profile.name.firstOrNull { it.isLetterOrDigit() }?.uppercase()
            if (initial != null) Txt(initial, AniMixTheme.type.title, color = colors.onAccent)
            else Icon(AniMixIcons.User, null, Modifier.size(32.dp), tint = colors.onAccent)
            if (model != null) RemoteImage(model, Modifier.fillMaxSize(), shape = CircleShape, placeholder = false)
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Txt(value, if (value.length > 4) AniMixTheme.type.heading else AniMixTheme.type.title)
        Spacer(Modifier.height(4.dp))
        Txt(label, AniMixTheme.type.micro)
    }
}

@Composable
private fun CollectionDistribution(library: LibraryData) {
    val colors = AniMixTheme.colors
    val statuses = listOf(ListStatus.Watching, ListStatus.Completed, ListStatus.Planned, ListStatus.OnHold, ListStatus.Dropped)
    val counts = statuses.map { status -> library.entries.values.count { it.status == status } }
    val total = counts.sum()
    val palette = listOf(colors.accent, colors.accentStrong, colors.textSecondary, colors.star, colors.textMuted)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt("Коллекция", AniMixTheme.type.heading, modifier = Modifier.weight(1f))
        Txt("$total тайтлов", AniMixTheme.type.caption)
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
private fun ActivityChart(minutes: List<Int>) {
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
private fun GenreTaste(genres: List<Pair<String, Int>>) {
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

private fun profileHours(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes < 600 -> "${minutes / 60},${minutes % 60 * 10 / 60}"
    else -> "${minutes / 60}"
}

private fun profileDaysWord(n: Int): String = when {
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
