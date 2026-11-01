package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.1.1 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.1.1"
    const val name = "New Horizons"
    /** The update's own name, shown in «О приложении». */
    const val title = "Improved user experience"
    const val label = "AniMix $version · $name"
    const val nextVersion = "1.2 re"

    /** What changed since the previous version, in the words of the release notes on GitHub. */
    val changes = listOf(
        "Плеер быстрее и стабильнее: кадры готовятся вне интерфейса, на Android — больше запас буфера и пауза при звонке или отключении наушников.",
        "Кнопки плеера плотнее, свечение рамок плавное и обновляется реже — меньше нагрузка.",
        "Вернулся пропуск опенингов и эндингов: тайминги берутся и у соседних серий, без них — кнопка «Пропустить 1:30»; опенинг и эндинг видны на полосе.",
        "«Связанное» в порядке просмотра с подписью «Продолжение», «Предыстория»; «Похожее» подбирается по жанрам, рейтингу и вашим спискам.",
        "Офлайн-плеер без онлайн-элементов.",
        "Обновление предлагается окном при запуске; «Позже» оставляет его в настройках.",
        "Профиль: статистика, коллекция, активность и жанры на полупрозрачных плашках, у вас и у друзей; большие числа больше не съезжают.",
        "Режим экономии батареи учитывает новый плеер.",
        "Друзья в сети и время последнего входа видны сразу.",
    )
}
