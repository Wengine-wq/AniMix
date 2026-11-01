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
        "Плеер в стиле YouTube: стеклянные кнопки, полоса с главами опенинга и эндинга, время под пальцем, автопереход, качество под шестерёнкой.",
        "Свечение рамок: поля вокруг кадра другой формы светятся цветами сцены; отключается в плеере и настройках.",
        "«Без рамок» и Horizon Link подписаны в верхней панели плеера; в офлайн-плеере Horizon Link больше нет.",
        "Профиль: имя рядом с аватаром, статус «в сети» и «был в сети» у вас и у друзей.",
        "«Меню» в профиле: уровень, достижения, друзья, история, настройки и «О приложении»; новая страница «Уровень».",
        "Новый экран аккаунта и отдельная вкладка «Приватность» в настройках.",
        "Итоги года заметнее на главной, после просмотра — кнопкой рядом с фильтрами.",
        "Из загрузок можно перейти на страницу аниме.",
        "Перенос из AniMix 2.x находит больше списков; на телефоне подсказывает рабочие способы.",
        "Достижения за часы показывают прогресс в часах.",
        "История просмотров по дням, списки открываются на непустом, аккуратнее карточки тайтлов.",
        "Карточка обновления показывает, что изменилось.",
    )
}
