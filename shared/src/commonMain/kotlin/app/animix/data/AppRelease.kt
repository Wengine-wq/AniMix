package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.1.0 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.1.0"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "MORE than just a bug fix"
    const val label = "AniMix $version · $name"
    const val nextVersion = "1.2 re"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Плеер в стиле YouTube: полоса с главами опенинга и эндинга, время под пальцем, качество под шестерёнкой.",
        "Свечение рамок: поля вокруг кадра другой формы светятся цветами сцены.",
        "Новый экран аккаунта: профиль, синхронизация, устройства и перенос — по порядку.",
        "Итоги года заметнее на главной, после просмотра — кнопкой рядом с фильтрами.",
        "Из загрузок можно перейти на страницу аниме.",
        "Перенос из AniMix 2.x находит больше списков; на телефоне подсказывает рабочие способы.",
        "Достижения за часы показывают прогресс в часах.",
        "Карточка обновления рассказывает, что изменилось.",
    )
}
