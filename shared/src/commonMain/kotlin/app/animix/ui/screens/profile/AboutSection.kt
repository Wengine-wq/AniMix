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
import app.animix.ui.components.AniMixMascot
import app.animix.ui.icons.AniMixIcons
import app.animix.ui.theme.AniMixTheme

/** Release notes and future plans stay visibly separate; this page never advertises a live account. */
@Composable
internal fun AboutSection() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AniMixMascot(Modifier.size(width = 112.dp, height = 160.dp), contentDescription = "Маскот AniMix")
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt("AniMix", AniMixTheme.type.title)
            Txt("Версия ${AppRelease.version}", AniMixTheme.type.caption)
        }
    }
    Spacer(Modifier.height(16.dp))
    Txt(AppRelease.name, AniMixTheme.type.heading, color = AniMixTheme.colors.accentText)
    Spacer(Modifier.height(8.dp))
    Txt("Перерождение первого AniMix. Просмотр, свободное время и своя коллекция — в одном приложении на телефоне и ПК.", AniMixTheme.type.body)

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
            "AniPulse — еженедельный выпуск по вашим спискам — и подборки на главной. Включаются в настройках.")
    }

    Spacer(Modifier.height(32.dp))
    Txt("Следующая версия · ${AppRelease.nextVersion}", AniMixTheme.type.heading)
    Spacer(Modifier.height(8.dp))
    Txt("В планах. Главный приоритет — аккаунт и сохранение вашей коллекции между устройствами.", AniMixTheme.type.caption)
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RoadmapStage("01", "Аккаунт и синхронизация",
            "Вход в AniMix, облачное сохранение профиля, списков и позиции просмотра. Продолжение на телефоне и ПК с сохранением работы без сети.")
        RoadmapStage("02", "Публичные профили",
            "Ссылка на свой профиль, просмотр профилей других пользователей и выбор того, какую часть коллекции и статистики показывать.")
        RoadmapStage("03", "Больше сценариев Watch flow",
            "Сохранённые варианты сессий и планирование к конкретному времени окончания — для перерыва, дороги или свободного вечера.")
        RoadmapStage("04", "Стабильность на каждом устройстве",
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
