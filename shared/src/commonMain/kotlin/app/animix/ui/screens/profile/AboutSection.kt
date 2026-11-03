package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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

/** Release notes and future plans stay visibly separate; the project's own links come right after who we are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSection() {
    val uri = LocalUriHandler.current
    // The app's own mark, as on the home screen of the device; the mascot stays with empty states.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(Res.drawable.animix_logo), "Логотип AniMix",
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
    // The project: the code on GitHub; support and ideas go to the app's Telegram channel.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Проект на GitHub", { runCatching { uri.openUri(AppLinks.GITHUB) } }, icon = AniMixIcons.Link)
        if (AppLinks.TELEGRAM.isNotEmpty()) {
            SecondaryButton("Поддержать проект", { runCatching { uri.openUri(AppLinks.TELEGRAM) } }, icon = AniMixIcons.Heart)
            SecondaryButton("Предложить идею", { runCatching { uri.openUri(AppLinks.TELEGRAM) } }, icon = AniMixIcons.Bulb)
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
            "«Плеер+» — стекло, свечение рамок, скорость, жесты и «картинка в картинке»; «Упрощённый» — плотные кнопки без эффектов. Качество, пропуск опенингов и эндингов, автопереход в обоих.")
        ReleaseFeature(AniMixIcons.Bell, "Уведомления о новых сериях",
            "Онгоинги из «Смотрю» сообщают о новой серии и о её выходе в выбранной озвучке — системным уведомлением и под колокольчиком в профиле.")
        ReleaseFeature(AniMixIcons.Download, "Загрузки и просмотр без сети",
            "Очередь серий с выбором озвучки и качества, паузой и продолжением. Скачанные серии доступны в приложении без интернета.")
        ReleaseFeature(AniMixIcons.Clock, "Watch flow",
            "Очередь под свободное время: подбор серий, прогноз окончания и остановка по лимиту. Можно объединять разные тайтлы в одну сессию.")
        ReleaseFeature(AniMixIcons.Pulse, "AniPulse",
            "Еженедельник: анонсы, трейлеры и премьеры недели из новостей Shikimori, ваши время и серии, новые серии, почти досмотренное, онгоинги в ваших жанрах и подборка недели. Без ИИ.")
        ReleaseFeature(AniMixIcons.User, "Профиль, уровень и награды",
            "Свой аватар, баннер и значок. Уровень — кольцо вокруг аватара; на 10-м уровне открывается оформление профиля, на 20-м — рамка аватара.")
        ReleaseFeature(AniMixIcons.Share, "Аккаунт, друзья и Horizon Link",
            "Синхронизация телефона и ПК, друзья с коллекциями и достижениями, приватность для каждого раздела, продолжение серии на другом устройстве.")
        ReleaseFeature(AniMixIcons.Trophy, "Достижения",
            "Награды за подтверждённый просмотр, коллекцию и привычки, включая секретные. Импорт из Shikimori и Anixart открывает награды из истории без XP.")
        ReleaseFeature(AniMixIcons.Flame, "Экспериментальные функции",
            "Свои подборки с умным подбором и полка «Подобрано для вас» включаются в настройках.")
    }

    // What comes next, kept apart from what already works: no dates, no promises of speed.
    Spacer(Modifier.height(32.dp))
    Txt("Дальше · ${AppRelease.nextVersion}", AniMixTheme.type.heading)
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RoadmapStage("01", "Подборки для всех", "Свои подборки и умный подбор выйдут из экспериментальных функций с учётом ваших отзывов.")
        RoadmapStage("02", "Стабильность и скорость", "Замеры производительности плеера и списков на слабых устройствах.")
        RoadmapStage("03", "Совместный просмотр", "Отложен: вернёмся к нему, когда AniMix будет пользоваться больше людей.")
    }
    Spacer(Modifier.height(24.dp))
    Txt("Это план развития, а не список уже доступных функций. Состав может уточняться; дата выхода не объявлена.", AniMixTheme.type.micro)
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

@Composable
private fun RoadmapStage(number: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Txt(number, AniMixTheme.type.label, color = AniMixTheme.colors.accentText, modifier = Modifier.width(28.dp).padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, AniMixTheme.type.bodyStrong)
            Spacer(Modifier.height(6.dp))
            Txt(description, AniMixTheme.type.caption)
        }
    }
}
