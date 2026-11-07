package app.animix.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import app.animix.ui.components.Panel
import app.animix.ui.components.appIconPainter
import app.animix.ui.components.at
import app.animix.ui.components.focusOutline
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.animix.data.AppLinks
import app.animix.data.AppRelease
import app.animix.ui.components.Txt
import androidx.compose.foundation.Image
import app.animix.resources.Res
import app.animix.resources.animix_logo
import app.animix.ui.components.SecondaryButton
import app.animix.ui.components.accentGlow
import org.jetbrains.compose.resources.painterResource
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

/** Who we are, the project's links, what changed in this version and what the app can do. No roadmap: updates are bug fixes unless announced. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSection(graph: app.animix.AppGraph) {
    val uri = LocalUriHandler.current
    val settings by graph.settings.state.collectAsState()
    val level by graph.level.collectAsState()
    // The app's own mark, as on the home screen of the device (the chosen icon); the mascot stays with empty states.
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = settings.appIcon.at(level.level)
        Image(if (icon == app.animix.core.AppIcon.Classic) painterResource(Res.drawable.animix_logo) else appIconPainter(icon), "Логотип AniMix",
            Modifier.size(88.dp).accentGlow(AniMixTheme.shapes.large, strength = 0.18f))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt("AniMix", AniMixTheme.type.title)
            Txt("Версия ${AppRelease.version} · ${AppRelease.name}", AniMixTheme.type.caption)
            Txt("«${AppRelease.title}»", AniMixTheme.type.label, color = AniMixTheme.colors.accentText, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Txt("Перерождение первого AniMix. Просмотр, свободное время и своя коллекция — в одном приложении на телефоне и ПК.", AniMixTheme.type.body)
    Spacer(Modifier.height(20.dp))
    // The project as two calm rows of one panel, like the rest of the settings: where the code lives,
    // and the one channel for news, support and ideas (three buttons to the same link said nothing more).
    Panel {
        ProjectLink(AniMixIcons.Link, "Проект на GitHub", "Исходный код, релизы и история изменений") { runCatching { uri.openUri(AppLinks.GITHUB) } }
        if (AppLinks.TELEGRAM.isNotEmpty()) {
            androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 66.dp), color = AniMixTheme.colors.outline, thickness = 1.dp)
            ProjectLink(AniMixIcons.Bulb, "Telegram-канал AniMix", "Новости, поддержка проекта и ваши идеи") { runCatching { uri.openUri(AppLinks.TELEGRAM) } }
        }
    }

    Spacer(Modifier.height(32.dp))
    Txt("Что нового в ${AppRelease.version}", AniMixTheme.type.heading)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppRelease.changes.forEach { change ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(AniMixIcons.Check, null, Modifier.padding(top = 2.dp).size(16.dp), tint = AniMixTheme.colors.accentText)
                Spacer(Modifier.width(10.dp))
                Txt(change, AniMixTheme.type.caption, color = AniMixTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    Txt("Что есть в этой версии", AniMixTheme.type.heading)
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ReleaseFeature(AniMixIcons.Palette, "Единый интерфейс",
            "Общий визуальный язык на Android и Windows: светлая и тёмная темы, согласованные формы и плавная навигация. Экономия батареи и отключение анимаций.")
        ReleaseFeature(AniMixIcons.Search, "Каталог и поиск",
            "Shikimori и YummyAnime в одной выдаче, фильтры по типу, жанрам и году, которые сохраняются при переходах.")
        ReleaseFeature(AniMixIcons.Play, "Плеер на выбор",
            "«Плеер+» — стекло, свечение рамок и «картинка в картинке»; «Упрощённый» — плотные кнопки без эффектов. Скорость, качество, свайпы яркости и громкости, пропуск опенингов и эндингов, автопереход в обоих.")
        ReleaseFeature(AniMixIcons.Bell, "Уведомления о новых сериях",
            "Онгоинги из «Смотрю» сообщают о новой серии и о её выходе в выбранной озвучке — системным уведомлением и в «Меню» профиля, где число новых видно сразу.")
        ReleaseFeature(AniMixIcons.Download, "Загрузки и просмотр без сети",
            "Очередь серий с выбором озвучки и качества, паузой и продолжением. Скачанные серии доступны в приложении без интернета.")
        ReleaseFeature(AniMixIcons.Clock, "Watch flow",
            "Очередь под свободное время: подбор серий, прогноз окончания и остановка по лимиту. Можно объединять разные тайтлы в одну сессию.")
        ReleaseFeature(AniMixIcons.Pulse, "AniPulse",
            "Еженедельный выпуск: вступление о вашей неделе, главная история и короткие пересказы новостей Shikimori, YummyAnime и GoHa.ru, затем новые серии, почти досмотренное и подборка недели. Без ИИ.")
        ReleaseFeature(AniMixIcons.User, "Профиль, уровень и награды",
            "Свой аватар, баннер и значок. Уровень — кольцо вокруг аватара; на 10-м уровне открывается оформление профиля, на 20-м — рамка аватара.")
        ReleaseFeature(AniMixIcons.Share, "Аккаунт, друзья и Horizon Link",
            "Синхронизация телефона и ПК, друзья с коллекциями и достижениями, приватность для каждого раздела, продолжение серии на другом устройстве.")
        ReleaseFeature(AniMixIcons.Trophy, "Достижения",
            "Награды за подтверждённый просмотр, коллекцию и привычки, включая секретные. Импорт из Shikimori и Anixart открывает награды из истории без XP.")
        ReleaseFeature(AniMixIcons.Flame, "Подборки",
            "Свои подборки, умный подбор по фильтрам и полка «Подобрано для вас» на главной по вашим спискам.")
    }
}

/** A link out of the app: its mark in the soft accent circle of the menu cards, a name, one line, and an outward arrow. */
@Composable
private fun ProjectLink(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    val colors = AniMixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clickable(interaction, androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .focusOutline(interaction, AniMixTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = colors.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            Txt(description, AniMixTheme.type.caption)
        }
        Spacer(Modifier.width(8.dp))
        Icon(AniMixIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.textMuted)
    }
}

@Composable
private fun ReleaseFeature(icon: ImageVector, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = AniMixTheme.colors.accentText)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            Spacer(Modifier.height(6.dp))
            Txt(description, AniMixTheme.type.caption)
        }
    }
}
