package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.2.2 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.2.2"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "Fine Tuning"
    const val label = "AniMix $version · $name"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "AniPulse стал настоящим выпуском: вступление о вашей неделе, главная история с картинкой и короткие пересказы новостей, а уже потом новые серии и подборки.",
        "Оформления профиля перерисованы и стали заметны: волны, сакура, звёзды и неон видны над баннером и под именем, на баннере без своей фотографии и медленно движутся. Рамки аватара — в том же стиле.",
        "Уведомления живут в меню профиля: число новых видно прямо на кнопке меню, отдельного колокольчика в шапке больше нет.",
        "Шестерёнка плеера больше не открывает пустой список: скорость и качество есть в обоих плеерах.",
        "«Упрощённый» плеер получил свайпы яркости и громкости в полноэкранном режиме на телефоне.",
        "В «Друзьях» видны настоящие аватарки, а не только первая буква имени.",
        "Окно обновления объясняет, зачем обновляться, показывает пропущенные версии и что произойдёт при установке.",
        "Перенос из AniMix 2.x переехал в «Импорт списков» — к Shikimori и Anixart.",
        "«О приложении» без плана развития и пометки об экспериментальных функциях.",
    )
}

/** Where «О приложении» leads: the project on GitHub and the app's Telegram channel (support and ideas). */
object AppLinks {
    const val GITHUB = "https://github.com/Wengine-wq/AniMix"
    const val TELEGRAM = "https://t.me/animixoff"
}
