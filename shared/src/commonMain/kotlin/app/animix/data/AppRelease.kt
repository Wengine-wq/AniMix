package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.2.0 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.2.0"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "Make it yours"
    const val label = "AniMix $version · $name"
    const val nextVersion = "1.3 re"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Плеер на выбор: «Плеер+» со скоростью до 2×, жестами яркости и громкости и «картинкой в картинке» на Android или лёгкий «Упрощённый».",
        "Уведомления о новых сериях онгоингов из «Смотрю» и о выходе серии в выбранной озвучке, на Android и в фоне.",
        "Награды за уровни: оформление профиля на 10-м уровне и рамка аватара в том же стиле на 20-м.",
        "Новый AniPulse — еженедельник без ИИ: главное за неделю из новостей Shikimori, YummyAnime и GoHa.ru (анонсы, трейлеры, премьеры), ваши время и серии, новые серии, почти досмотренное и подборка недели.",
        "Свои подборки и умный подбор по жанрам, году и длине — в экспериментальных функциях.",
        "Профиль друга: коллекция и достижения на своих страницах; приватность для каждого раздела — все, друзья или никто.",
        "Устройства аккаунта называются по модели телефона или материнской плате ПК, лишнее можно отключить.",
        "Меню профиля карточками, вернулся обновлённый маскот.",
        "Исправлено: просмотренное в гостевом профиле не теряется при входе в Google; рамки плеера не мигают на паузе.",
        "Меньше нагрузки на сервер: один запрос вместо четырёх, сжатие и проверка изменений без скачивания всей коллекции.",
    )
}

/** Where «О приложении» leads: the project on GitHub and the app's Telegram channel (support and ideas). */
object AppLinks {
    const val GITHUB = "https://github.com/Wengine-wq/AniMix"
    const val TELEGRAM = "https://t.me/animixoff"
}
