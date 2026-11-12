package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.4.0 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.4.0"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "Future of AniMix is here"
    const val label = "AniMix $version · $name"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Жидкое стекло стало линзой: навбар, меню и плавающие кнопки преломляют то, что под ними, по граням картинка изгибается, как под настоящим стеклом.",
        "Навбар больше не подёргивается при смене вкладки: название разворачивается плавно вместе со слотом.",
        "Меню постера по удержанию открывается прямо у постера и теперь срабатывает на телефоне; на ПК его открывает правая кнопка мыши.",
        "Уровень растёт быстрее: за досмотренную серию больше опыта, а серия дней приносит опыт каждый день подряд, начиная со второго.",
        "Новая страница «Уровень»: путь до следующего уровня, серия дней по дням недели, из чего сложился уровень и награды дорожкой.",
        "«Продолжить просмотр» показывает кадр, на котором вы остановились, вместо обложки.",
        "Кольцо коллекции в профиле со скруглёнными сегментами, в одном стиле со столбиками активности.",
        "Светлые темы Dawn и Noon: вверху профиля и других страниц больше нет серого градиента.",
        "AniPulse на главной исчезает после прочтения и возвращается с новой недели; перечитать можно в «Меню».",
        "Серия дней теперь считается по дням с досмотренной серией; когда она продлевается, приложение говорит об этом.",
    )
}

/** Where «О приложении» leads: the project on GitHub and the app's Telegram channel (support and ideas). */
object AppLinks {
    const val GITHUB = "https://github.com/Wengine-wq/AniMix"
    const val TELEGRAM = "https://t.me/animixoff"
}
