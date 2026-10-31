package app.animix.ui.screens.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.animix.data.AppRelease
import app.animix.ui.components.Txt
import androidx.compose.foundation.Image
import app.animix.resources.Res
import app.animix.resources.animix_logo
import app.animix.ui.components.accentGlow
import org.jetbrains.compose.resources.painterResource
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

/** Release notes and future plans stay visibly separate; this page never advertises a live account. */
@Composable
internal fun AboutSection() {
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
        ReleaseFeature(AniMixIcons.Play, "Встроенный плеер AniMix",
            "Общие элементы управления на телефоне и ПК: качество видео, полноэкранный режим, автопереход и пропуск размеченных опенингов и эндингов.")
        ReleaseFeature(AniMixIcons.Download, "Загрузки и просмотр без сети",
            "Очередь серий с выбором озвучки и качества, паузой и продолжением. Скачанные серии доступны в приложении без интернета.")
        ReleaseFeature(AniMixIcons.Clock, "Watch flow",
            "Очередь под свободное время: подбор серий, прогноз окончания и остановка по лимиту. Можно объединять разные тайтлы в одну сессию.")
        ReleaseFeature(AniMixIcons.User, "Профиль и уровень",
            "Свой аватар, баннер и значок достижения рядом с именем. Уровень — кольцо вокруг аватара; статистика, активность, любимые жанры и «Итоги года».")
        ReleaseFeature(AniMixIcons.Trophy, "Достижения",
            "Награды за подтверждённый просмотр, коллекцию и привычки, включая секретные. Импорт из Shikimori и Anixart открывает награды из истории без XP.")
        ReleaseFeature(AniMixIcons.Share, "Перенос данных",
            "Списки из Shikimori и Anixart, перенос списков, часов и достижений из AniMix 2.x — через аккаунт или с этого компьютера.")
        ReleaseFeature(AniMixIcons.Refresh, "Обновления",
            "Новая версия предлагается на главной и устанавливается прямо из приложения.")
        ReleaseFeature(AniMixIcons.Flame, "Экспериментальные функции",
            "Подборки на главной включаются в настройках. AniPulse временно приостановлен.")
    }

    Spacer(Modifier.height(32.dp))
    Txt("Следующая версия · ${AppRelease.nextVersion}", AniMixTheme.type.heading)
    Spacer(Modifier.height(8.dp))
    Txt("Следующий приоритет — стабильность и производительность. Аккаунт, синхронизация, друзья и Horizon Link уже подключены.", AniMixTheme.type.caption)
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RoadmapStage("01", "Стабильность на каждом устройстве",
            "Доработка плеера и загрузок при смене сети, проверка слабых устройств и ускорение запуска и длинных списков по результатам замеров.")
    }
    Spacer(Modifier.height(24.dp))
    Txt("Это план развития, а не список уже доступных функций. Состав обновления может уточняться; дата выхода пока не объявлена.", AniMixTheme.type.micro)
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
