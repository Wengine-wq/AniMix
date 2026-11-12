package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.animix.data.library.episodeDays
import app.animix.data.library.xpSources
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
        val streak = remember(library) { app.animix.core.localDayIndex().let { today -> app.animix.data.library.streaks(library.episodeDays().filterTo(HashSet()) { it <= today }, today).first } }
        LevelCard(library.profile, level, streak) { navigator.push(Route.Level) }
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
private fun LevelCard(profile: app.animix.data.library.LocalProfile, level: app.animix.data.library.ProfileLevel, streak: Int, onClick: () -> Unit) {
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
                // A run of days feeds the level: worth seeing before one even opens the page.
                if (streak >= 2) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AniMixIcons.Flame, null, Modifier.size(14.dp), tint = colors.accent)
                    Spacer(Modifier.width(4.dp))
                    Txt("$streak ${profileDaysWord(streak)} подряд", AniMixTheme.type.micro, color = colors.textPrimary)
                }
            }
            Icon(AniMixIcons.ChevronRight, null, Modifier.size(20.dp), tint = colors.textMuted)
        }
    }
}

/**
 * «Уровень»: where one is (the avatar in its ring over a soft light, the way from this level to the next),
 * the run of days that feeds it, what the level is made of, and the rewards as a path with the reached
 * ones lit. No XP numbers anywhere: shares and the ring say enough.
 */
@Composable
fun LevelScreen(graph: AppGraph, navigator: Navigator, padding: PaddingValues) {
    val library by graph.library.state.collectAsState()
    val achievements by graph.achievements.progress.collectAsState()
    val level by graph.level.collectAsState()
    val colors = AniMixTheme.colors
    val today = remember { app.animix.core.localDayIndex() }
    val days = remember(library) { library.episodeDays() }
    val streak = remember(days) { app.animix.data.library.streaks(days.filterTo(HashSet()) { it <= today }, today) }
    val sources = remember(library, achievements) { library.xpSources(achievements.count { it.earned }) }
    ScreenColumn(padding) {
        PageHeader("Уровень", { navigator.back() }, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        GlassPlate {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    // A soft light of the accent behind the avatar: the level is the subject of the page.
                    if (!app.animix.ui.theme.LocalUiPerformance.current.batterySaver) Box(Modifier.size(172.dp).drawBehind {
                        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(colors.accent.copy(alpha = if (colors.isDark) 0.22f else 0.28f), androidx.compose.ui.graphics.Color.Transparent)))
                    })
                    ProfileAvatar(library.profile, 120.dp, level = level)
                }
                Spacer(Modifier.height(10.dp))
                Txt("Уровень ${level.level}", AniMixTheme.type.title)
                Spacer(Modifier.height(18.dp))
                // From this level to the next: the two numbers stand at the ends of the way between them.
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "До уровня ${level.level + 1} пройдено ${(level.fraction * 100).toInt()} %"
                }, verticalAlignment = Alignment.CenterVertically) {
                    LevelDot(level.level, reached = true)
                    LevelTrack(level.fraction, Modifier.weight(1f).padding(horizontal = 10.dp))
                    LevelDot(level.level + 1, reached = false)
                }
                Spacer(Modifier.height(10.dp))
                Txt("До уровня ${level.level + 1} пройдено ${(level.fraction * 100).toInt()}%", AniMixTheme.type.caption, align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassPlate { StreakWeek(days, today, streak.first, streak.second) }
        Spacer(Modifier.height(12.dp))
        GlassPlate {
            Txt("Из чего сложился уровень", AniMixTheme.type.heading)
            Spacer(Modifier.height(14.dp))
            val parts = listOf(
                LevelPart(AniMixIcons.Clock, "Время просмотра", "Каждая минута, которую вы видите впервые", sources.minutes, colors.accent),
                LevelPart(AniMixIcons.Play, "Досмотренные серии", "Каждая серия, впервые досмотренная в AniMix", sources.episodes, colors.accent.copy(alpha = 0.62f)),
                LevelPart(AniMixIcons.Flame, "Серия дней", "Каждый день подряд, начиная со второго", sources.streak, colors.star),
                LevelPart(AniMixIcons.Trophy, "Достижения", "Каждое достижение, полученное в AniMix", sources.awards, colors.textSecondary.copy(alpha = 0.7f)),
            )
            if (sources.total > 0) {
                SharesBar(parts.map { it.value.toFloat() / sources.total to it.color })
                Spacer(Modifier.height(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                parts.forEach { part ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(part.color))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(part.title, AniMixTheme.type.bodyStrong)
                            Txt(part.hint, AniMixTheme.type.caption)
                        }
                        if (sources.total > 0) {
                            Spacer(Modifier.width(12.dp))
                            Txt("${(part.value * 100 / sources.total)}%", AniMixTheme.type.label, color = colors.textSecondary)
                        }
                    }
                }
            }
            Txt("Перемотка, повторный просмотр и импорт истории уровень не повышают.", AniMixTheme.type.micro,
                modifier = Modifier.padding(top = 16.dp))
        }
        Spacer(Modifier.height(12.dp))
        GlassPlate {
            Txt("Награды за уровни", AniMixTheme.type.heading)
            Spacer(Modifier.height(16.dp))
            LevelRewards.forEachIndexed { index, reward ->
                LevelReward(reward, level.level, first = index == 0, last = index == LevelRewards.lastIndex)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Темы", { navigator.push(Route.SettingsSection("Appearance")) }, Modifier.weight(1f), icon = AniMixIcons.Sun)
            SecondaryButton("Оформление", { navigator.push(Route.EditProfile()) }, Modifier.weight(1f), icon = AniMixIcons.Palette)
        }
    }
}

private class LevelPart(val icon: ImageVector, val title: String, val hint: String, val value: Long, val color: androidx.compose.ui.graphics.Color)

/** A level number at an end of the way: filled when reached, outlined when ahead. */
@Composable
private fun LevelDot(level: Int, reached: Boolean) {
    val colors = AniMixTheme.colors
    Box(Modifier.height(26.dp).widthIn(min = 26.dp).clip(AniMixTheme.shapes.pill)
        .then(if (reached) Modifier.background(colors.accentGradient) else Modifier.background(colors.chip).rim(AniMixTheme.shapes.pill, accent = false))
        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Txt("$level", AniMixTheme.type.label, color = if (reached) colors.onAccent else colors.textSecondary, maxLines = 1)
    }
}

/** The way between two levels: a rounded track, the walked part in the accent gradient with a lit end. */
@Composable
private fun LevelTrack(fraction: Float, modifier: Modifier = Modifier) {
    val colors = AniMixTheme.colors
    val value by app.animix.ui.theme.animateMotionFloatAsState(fraction.coerceIn(0f, 1f), androidx.compose.animation.core.tween(app.animix.ui.theme.AniMixMotion.Page * 2))
    androidx.compose.foundation.Canvas(modifier.height(10.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(colors.outline, cornerRadius = radius)
        // Never shorter than a dot, so a fresh level still shows where the way begins.
        val walked = (size.width * value).coerceAtLeast(size.height)
        drawRoundRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colors.gradientStart, colors.gradientEnd), endX = walked),
            size = androidx.compose.ui.geometry.Size(walked, size.height), cornerRadius = radius)
        drawCircle(colors.onAccent.copy(alpha = 0.55f), size.height * 0.2f, androidx.compose.ui.geometry.Offset(walked - size.height / 2, size.height / 2))
    }
}

/**
 * The run of days: the last seven days as dots (a day counts once an episode is watched to the end),
 * the run in words and what the next day brings. The second day in a row and every day after it adds
 * to the level; the longer the run, the more.
 */
@Composable
private fun StreakWeek(days: Set<Long>, today: Long, streak: Int, best: Int) {
    val colors = AniMixTheme.colors
    val todayDone = today in days
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (streak > 0) colors.accentSoft else colors.chip), contentAlignment = Alignment.Center) {
            Icon(AniMixIcons.Flame, null, Modifier.size(20.dp), tint = if (streak > 0) colors.accent else colors.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(if (streak > 0) "$streak ${profileDaysWord(streak)} подряд" else "Серия дней", AniMixTheme.type.subheading)
            Txt(when {
                streak == 0 -> "Досмотрите серию сегодня и завтра: второй день подряд уже повышает уровень"
                !todayDone -> "Досмотрите серию сегодня, чтобы не прервать серию дней"
                streak == 1 -> "Начало есть. Завтрашний день уже добавит к уровню"
                else -> "Сегодня засчитано. Каждый следующий день добавляет больше"
            }, AniMixTheme.type.caption)
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        (6 downTo 0).forEach { back ->
            val day = today - back
            val done = day in days
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(30.dp).clip(CircleShape).then(when {
                    done -> Modifier.background(colors.accentGradient)
                    back == 0 -> Modifier.background(colors.chip).rim(CircleShape, accent = true)
                    else -> Modifier.background(colors.chip)
                }), contentAlignment = Alignment.Center) {
                    if (done) Icon(AniMixIcons.Check, null, Modifier.size(14.dp), tint = colors.onAccent)
                }
                Spacer(Modifier.height(6.dp))
                Txt(if (back == 0) "Сегодня" else names[app.animix.core.weekdayOf(day)], AniMixTheme.type.micro,
                    color = if (back == 0) colors.textPrimary else colors.textSecondary, maxLines = 1)
            }
        }
    }
    if (best > 1 && best > streak) Txt("Рекорд: $best ${profileDaysWord(best)} подряд", AniMixTheme.type.micro, modifier = Modifier.padding(top = 12.dp))
}

/** Shares of a whole as one rounded bar of segments with small gaps, in the manner of the activity bars. */
@Composable
internal fun SharesBar(parts: List<Pair<Float, androidx.compose.ui.graphics.Color>>, modifier: Modifier = Modifier) {
    val grow by app.animix.ui.theme.animateMotionFloatAsState(1f, androidx.compose.animation.core.tween(app.animix.ui.theme.AniMixMotion.Page))
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val shown = parts.filter { it.first > 0f }
        val gap = 3.dp.toPx()
        val free = size.width - gap * (shown.size - 1)
        var x = 0f
        shown.forEach { (share, color) ->
            // A sliver still gets a rounded dot of its own.
            val width = (free * share).coerceAtLeast(size.height)
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(x * grow, 0f), androidx.compose.ui.geometry.Size(width * grow, size.height),
                androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            x += width + gap
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

/**
 * One reward on the path: the rail on the left is lit down to the reached rewards, the node holds a check
 * or a lock; then a small preview, what the reward is and how far it is.
 */
@Composable
private fun LevelReward(reward: LevelRewardInfo, current: Int, first: Boolean, last: Boolean) {
    val colors = AniMixTheme.colors
    val open = current >= reward.level
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(28.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val node = 22.dp.toPx()
            val line = 2.dp.toPx()
            if (!first) drawLine(if (open) colors.accent else colors.outline, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, node), line)
            if (!last) drawLine(colors.outline, androidx.compose.ui.geometry.Offset(x, node), androidx.compose.ui.geometry.Offset(x, size.height), line)
        }, contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 11.dp).size(22.dp).clip(CircleShape)
                .then(if (open) Modifier.background(colors.accentGradient) else Modifier.background(colors.surfaceRaised).rim(CircleShape, accent = false)),
                contentAlignment = Alignment.Center) {
                Icon(if (open) AniMixIcons.Check else AniMixIcons.Lock, null, Modifier.size(12.dp), tint = if (open) colors.onAccent else colors.textMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Уровень ${reward.level}", AniMixTheme.type.micro, color = if (open) colors.accentText else colors.textSecondary)
                Txt(reward.title, AniMixTheme.type.bodyStrong)
                Txt(reward.description, AniMixTheme.type.caption)
                if (!open) Txt("Осталось ${reward.level - current} ${levelsWord(reward.level - current)}", AniMixTheme.type.micro, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(width = 64.dp, height = 44.dp).clip(AniMixTheme.shapes.small).background(colors.chip)
                .then(if (open) Modifier else Modifier.alpha(0.55f))) { reward.preview() }
        }
    }
}

