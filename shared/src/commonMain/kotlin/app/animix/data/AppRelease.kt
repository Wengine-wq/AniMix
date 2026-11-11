package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.3.0 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.3.0"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "In Focus"
    const val label = "AniMix $version · $name"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Жидкое стекло во всём приложении: кромки поверхностей освещены, как у стекла, плавающие панели по-настоящему размыты, а подложка навбара движется каплей. В «Производительности» его можно выключить — останется спокойное матовое стекло.",
        "Новая страница аниме: кадр на всю ширину, студия сразу под названием, другие сезоны сразу после описания, «Продолжить» с серией и моментом остановки.",
        "Долгое нажатие на любой постер — меню «Смотреть», «Запланировать», «В избранное», «Скачать серии».",
        "«На этой неделе» в «Смотрю»: когда выйдут следующие серии ваших онгоингов. На странице аниме и в «Смотреть» — дата следующей серии.",
        "Главная: «Продолжить просмотр» карточками-кадрами сразу под чипами, обновление жестом вниз, навбар с подписью выбранной вкладки.",
        "На постерах видно, что тайтл уже в ваших списках, а у «Смотрю» — сколько посмотрено.",
        "«Итоги года»: свайпы, слайд «Рейтинг года» и карточка года, которой можно поделиться.",
        "Трейлер и кадры на весь экран, поиск по озвучкам, диапазоны серий у длинных тайтлов, недавние запросы в поиске.",
        "Оформление профиля видно и поверх своего баннера; настройки собраны в группы, подсказки стали короче.",
        "Исправлено: стрелка после перемотки не исчезала, загрузка в профиле друга была не по центру, «Был в сети» писалось в мужском роде.",
    )
}

/** Where «О приложении» leads: the project on GitHub and the app's Telegram channel (support and ideas). */
object AppLinks {
    const val GITHUB = "https://github.com/Wengine-wq/AniMix"
    const val TELEGRAM = "https://t.me/animixoff"
}
