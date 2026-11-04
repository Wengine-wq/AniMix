package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.2.1 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.2.1"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "Solid Ground"
    const val label = "AniMix $version · $name"
    const val nextVersion = "1.3 re"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Достижения за сезоны и фильмы выдаются надёжно: серия засчитывается, даже если тайминг эндинга у релиза неточный; сохранённый просмотр перепроверяется сам.",
        "Две новые темы с бирюзовым акцентом — светлая Horizon Noon открывается на 5-м уровне, тёмная Horizon Midnight на 10-м. Значок приложения теперь можно выбрать из четырёх.",
        "Кнопка пропуска живёт 10 секунд и убывает заливкой; «Пропустить 1:30» больше не висит и не сдвигается вместе с видео.",
        "«Картинка в картинке» показывает видео, а не чёрный квадрат.",
        "Каждое нажатие видно: единый отклик на всех кнопках, карточках и строках.",
        "AniPulse стал дайджестом недели: что вы смотрели, новые серии, почти досмотренное и короткая сводка новостей без манги и ошибок разбора.",
        "«Подобрано для вас» — постоянная полка на главной с объяснением каждого выбора; подборки и умный подбор вышли из экспериментальных, несколько жанров ищутся вместе, все жанры в сворачиваемых фильтрах.",
        "Профиль друга как свой: уровень, статистика, коллекция с недавними тайтлами и последние достижения.",
        "Студии на странице аниме ведут ко всем их тайтлам.",
        "Закрытые оформления и рамки профиля можно примерить до нужного уровня.",
        "Стабильность: запросы к каталогу больше не упираются в лимит Shikimori, ошибки сети описаны по-человечески, «О приложении» и меню профиля стали спокойнее.",
    )
}

/** Where «О приложении» leads: the project on GitHub and the app's Telegram channel (support and ideas). */
object AppLinks {
    const val GITHUB = "https://github.com/Wengine-wq/AniMix"
    const val TELEGRAM = "https://t.me/animixoff"
}
