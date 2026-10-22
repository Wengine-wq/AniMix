# Пользовательский аудит AniMix — 22 октября 2026

Проверен текущий собранный AniMix 3.0.0 / 3.0 (1.0 re) New Horizons. Три агента выполнили разные пользовательские сценарии; основной агент отдельно проверил локальные сохранения и отказоустойчивость. Все ANIMIX_HOME изолированы в build/user-audit; личные данные пользователя не использовались. Продуктовый код в ходе аудита не менялся.

## Среда и границы проверки

**После разбора с пользователем:** A06 признан ожидаемым поведением: офлайн-плеер переходит к следующей доступной скачанной серии. Остальные результаты и выполненные исправления перечислены в конце отчёта.

- Реальный Compose Desktop рендер текущего JAR, обычные семантические клики и pointer-события мыши/touch, а также реальный VLC для офлайн-перехода.
- Размеры телефона и desktop, светлая/тёмная темы, крупный шрифт.
- Тестовый локальный кеш, короткое синтетическое видео, нормальная/отключённая/задержанная сеть.
- Проверки с повреждёнными файлами — отдельное намеренное внедрение сбоя, а не утверждение о самопроизвольном повреждении данных.
- На физическом Android-устройстве и внешних аккаунтах с реальными импортами прогон не проводился.

## Подтверждённые проблемы

### A01 — P1: одна ошибочная запись обнуляет всю библиотеку

**Сценарий:** вернувшийся пользователь с сохранённой библиотекой; внедрение ошибки формата данных.

В library.json оставлен исправный профиль и тайтл с избранным/статусом/позицией, затем добавлена одна несовместимая запись. После запуска профиль стал «Гость», коллекция — 0; исходный JSON заменён 39-байтовым пустым профилем. Запуск с одним исправным тайтлом в том же окружении прошёл нормально.

`Library.load()` трактует ошибку чтения/декодирования как отсутствие данных и вызывает сохранение начального состояния. Уверенность высокая; естественная вероятность повреждения/несовместимости не измерялась.

- Код: [Library.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/library/Library.kt:86>).
- Доказательства: [исходный смешанный файл](<D:/AniMix New Horizons/build/user-audit/reliability/mixed-original.json>), [снимок пустого профиля](<D:/AniMix New Horizons/build/user-audit/reliability/mixed-library.png>), [перезаписанный файл](<D:/AniMix New Horizons/build/user-audit/reliability/mixed-library/data/library.json>), [контроль](<D:/AniMix New Horizons/build/user-audit/reliability/valid-control.png>).
- Исправление: различать missing/error/ready, сохранять исходник/резервную копию и предлагать восстановление; не записывать пустую библиотеку поверх нечитаемой.

### A02 — P2: сбой одного хранилища обрывает общую инициализацию

**Сценарий:** локальные Preferences или достижения повреждены, внедрение сбоя.

Испорченный settings.preferences_pb блокирует запуск даже при исправном резервном settings.json: JAR завершается с CorruptionException до снимка интерфейса. Повреждённый achievements.json без валидного backup вызывает необработанное исключение и прерывает общий запуск до загрузки Watch flow и Downloads. Файлы в этих случаях сохраняются, но сценария восстановления и локализации ошибки нет.

- Код: [App.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/App.kt:111>), [AppSettings.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/core/AppSettings.kt:83>), [SettingsStorage.desktop.kt](<D:/AniMix New Horizons/shared/src/desktopMain/kotlin/app/animix/core/SettingsStorage.desktop.kt:13>), [Achievements.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/achievements/Achievements.kt:126>).
- Доказательства: [ошибка Preferences](<D:/AniMix New Horizons/build/user-audit/reliability/damaged-preferences.log>), [сохранённый JSON](<D:/AniMix New Horizons/build/user-audit/reliability/damaged-preferences/data/settings.json>), [ошибка достижений](<D:/AniMix New Horizons/build/user-audit/reliability/damaged-achievements.log>), [незавершённый интерфейс](<D:/AniMix New Horizons/build/user-audit/reliability/damaged-achievements.png>).
- Исправление: экран восстановления с сохранением исходника; отдельные результаты инициализации подсистем вместо общего прерывающегося запуска.

### A03 — P2: после ошибки загрузки карточки остаются вечные скелетоны

**Сценарий:** новичок открывает `animix://anime/52991` без кеша и подключения.

Имя тайтла отсутствует, показано «Другое» и текст сетевой ошибки без кнопки повтора. «Похожее» и «Отзывы» продолжают показывать скелетоны: они ждут непустое название через `awaitCancellation()`. Восстановление связи не перезапускает завершившийся запрос; приходится выходить и открывать страницу снова.

- Код: [DetailScreen.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/detail/DetailScreen.kt:82>), [Reviews.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/detail/Reviews.kt:115>).
- Доказательства: [карточка](<D:/AniMix New Horizons/build/user-audit/new-user/detail-offline.png>), [зависимые секции](<D:/AniMix New Horizons/build/user-audit/new-user/detail-offline-bottom.png>).
- Исправление: общее состояние ошибки зависимых секций и действие повторного запроса.

### A04 — P2: в коллекцию сохраняется безымянный тайтл

На той же незагруженной странице доступны «Добавить → Запланировано». Действие сохраняет пустые title/originalTitle, episodes=0 и kind=Other. После перезапуска в коллекции остаётся безымянная карточка. Успешное открытие страницы онлайн позже не обновляет её библиотечные метаданные.

- Код: [действия карточки](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/detail/DetailScreen.kt:99>), [создание записи](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/library/Library.kt:224>).
- Доказательства: [сохранённые данные](<D:/AniMix New Horizons/build/user-audit/new-user/add-empty-title/data/library.json>), [коллекция после перезапуска](<D:/AniMix New Horizons/build/user-audit/new-user/empty-title-planned.png>), [повторное открытие онлайн](<D:/AniMix New Horizons/build/user-audit/new-user/empty-title-online-reopen.png>).
- Исправление: не сохранять placeholder как полноценные метаданные; обновлять существующую запись после успешной загрузки.

### A05 — P2: дополнительный источник задерживает готовые результаты поиска

Свежая выдача Shikimori уже есть в кеше, но медленный Yummy оставляет весь поиск в скелетонах. В контрольном запуске с немедленной ошибкой Yummy основной результат появляется. Через 8 секунд задержки дополнительного источника результата всё ещё нет; журнал подтверждает отсутствие запросов Shikimori.

- Код: [AnimeRepository.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/AnimeRepository.kt:40>): после готового base выполняется `extra.await()`.
- Доказательства: [задержка](<D:/AniMix New Horizons/build/user-audit/new-user/search-secondary-stall-confirmed.png>), [контроль](<D:/AniMix New Horizons/build/user-audit/new-user/search-secondary-failed-control.png>), [запросы](<D:/AniMix New Horizons/build/user-audit/new-user/search-secondary-stall-confirmed-requests.json>), [скрипт воспроизведения](<D:/AniMix New Horizons/build/user-audit/new-user/reproduce-secondary-stall.py>).
- Исправление: показывать готовую основную выдачу, затем дополнять её независимо.

### A06 — согласованное поведение: переход к следующей скачанной серии

**Решение пользователя:** отсутствие скачанной серии 2 не должно мешать переходу с 1 на доступную 3. Первоначальная трактовка как дефекта отменена; поведение сохранено.

**Сценарий:** активный зритель скачал серии 1 и 3 одного тайтла, озвучки и качества.

После реального завершения серии 1 VLC автоматически начинает 3 без предупреждения об отсутствующей 2. Контроль с непрерывными 1/2/3 корректно переходит к 2. Алгоритм выбирает минимальную скачанную серию с большим sortKey, не проверяя сюжетную последовательность.

- Код: [DownloadsScreen.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/downloads/DownloadsScreen.kt:465>).
- Доказательства: [начало серии 1](<D:/AniMix New Horizons/build/user-audit/viewer/offline-gap-3000ms.png>), [автоматический переход к 3](<D:/AniMix New Horizons/build/user-audit/viewer/offline-gap-16000ms.png>), [прогресс](<D:/AniMix New Horizons/build/user-audit/viewer/offline-gap/data/library.json>), [контроль](<D:/AniMix New Horizons/build/user-audit/viewer/offline-present.png>).
- Изменение не требуется: переход идёт по доступным скачанным сериям того же тайтла и озвучки.

### A07 — P2: следующая скачанная серия другого качества считается отсутствующей

Серии 1 (720p) и 2 (1080p) одного тайтла и озвучки скачаны полностью. После конца 1 приложение показывает «Это последняя серия», предлагая только повтор. Строгий фильтр качества исключает доступную серию 2. Требуется выходить в загрузки и запускать её вручную.

- Код: [DownloadsScreen.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/downloads/DownloadsScreen.kt:465>).
- Доказательства: [сообщение плеера](<D:/AniMix New Horizons/build/user-audit/viewer/offline-quality.png>), [скачанные серии](<D:/AniMix New Horizons/build/user-audit/viewer/offline-quality/data/downloads/queue.json>).
- Исправление: искать следующую серию с предпочтением текущего качества и запасным выбором доступного файла.

### A08 — P2: ограничение имени повреждает emoji

**Сценарий:** пользователь с длинным именем, содержащим emoji.

Имя из 35 emoji после загрузки превращается в 17 emoji и оборванный символ. Интерфейс показывает символ замены, в перезаписанном JSON последний фрагмент становится `?`. `.take(35)` и `.length` считают UTF-16 code units, поэтому могут разрезать символ. Такая же обрезка есть в редакторе и сохранении имени.

- Код: [загрузка](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/library/Library.kt:90>), [сохранение имени](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/data/library/Library.kt:171>), [редактор](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/profile/EditProfileScreen.kt:83>).
- Доказательства: [испорченное имя](<D:/AniMix New Horizons/build/user-audit/accessibility/emoji.png>), [перезаписанная библиотека](<D:/AniMix New Horizons/build/user-audit/accessibility/emoji/data/library.json>).
- Исправление: единое ограничение по пользовательским символам без разрыва Unicode; одинаковый подсчёт в счётчике, редакторе и хранилище.

### A09 — P2: при управлении клавиатурой фокус не виден

**Сценарий:** пользователь Windows перемещается по интерфейсу клавишей Tab.

На карандаше, настройках и пяти вкладках навбара фокус реально перемещается: semantics содержит `Focused=true`, Enter открывает редактор. Скриншоты до и после Tab при этом пиксельно одинаковы. Проверено также с включёнными анимациями. Пользователь не видит, какую кнопку активирует. Обобщение на все элементы приложения не делалось.

- Код: [IconCircle](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/components/Components.kt:317>) и [навигация](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/App.kt:275>): indication отключена, отдельное оформление фокуса отсутствует.
- Дополнительные доказательства: [semantics с включёнными анимациями](<D:/AniMix New Horizons/build/user-audit/accessibility/keyboard-animated-results/keyboard-semantics.txt>), [фокус навбара](<D:/AniMix New Horizons/build/user-audit/accessibility/keyboard-nav-results/keyboard-semantics.txt>).
- Доказательства: [semantics](<D:/AniMix New Horizons/build/user-audit/accessibility/keyboard-semantics.txt>), [до Tab](<D:/AniMix New Horizons/build/user-audit/accessibility/keys-before.png>), [после Tab](<D:/AniMix New Horizons/build/user-audit/accessibility/keys-tab1.png>), [Enter открыл редактор](<D:/AniMix New Horizons/build/user-audit/accessibility/keys-enter.png>).
- Исправление: общий хорошо различимый стиль клавиатурного фокуса для кнопок и навигации.

### A10 — P2: кнопка возврата не имеет доступного названия

В реальном semantics редактора профиля кнопка PageHeader имеет OnClick, но не содержит Text, ContentDescription или подписи действия. Визуально это стрелка; назначение недоступно средствам озвучивания через semantics. Проверено дерево доступности, полноценный прогон экранным диктором не проводился.

- Код: [PageHeader](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/components/Components.kt:99>) передаёт в IconCircle иконку без contentDescription.
- Доказательство: [semantics после Enter](<D:/AniMix New Horizons/build/user-audit/accessibility/keyboard-semantics.txt>).
- Исправление: доступное название «Назад» в общем PageHeader.

### A11 — P3: Watch flow принимает нажатие и молча ничего не делает

При непустой очереди и ещё загружающихся озвучках кнопка «Добавить в оставшееся время» активна. Нажатие не меняет очередь и не объясняет причину. В аналогичном состоянии пустой очереди основная кнопка правильно отключена. Загрузка воспроизведена штатной искусственной задержкой; обработчик и UI настоящие.

- Код: [WatchFlowScreen.kt](<D:/AniMix New Horizons/shared/src/commonMain/kotlin/app/animix/ui/screens/watchflow/WatchFlowScreen.kt:221>), ранний выход prepare на строке 91.
- Доказательства: [активная кнопка после клика](<D:/AniMix New Horizons/build/user-audit/viewer/flow-loading-action.png>), [неизменившаяся очередь](<D:/AniMix New Horizons/build/user-audit/viewer/flow-loading-action/data/watch-flow.json>).
- Исправление: согласовать enabled с готовностью данных или объяснять ожидание.

## Что прошло проверку

- Онлайн-загрузка главной, баннеров и полок; понятная ошибка главной без сети с повтором; состояния пустого поиска и поиска с ошибкой сети.
- Профиль и редактор на 360×740 с крупным шрифтом и длинным кириллическим именем; кнопка сохранения достижима прокруткой. Светлая/тёмная темы и desktop-компоновка.
- Воспроизведение локального видео настоящим VLC, обычный офлайн-переход 1→2.
- Завершение Watch flow из трёх серий и жёсткий минутный лимит во время паузы. Учёт пауз таймером соответствует документации.
- Предположение об исчезающем предпросмотре редактора не воспроизвелось и не включено в дефекты.

## Ограничения и порядок исправления

Первоначально зафиксировано 11 находок: 1 P1, 9 P2, 1 P3. После решения пользователя A06 исключён: остаются 10 дефектов (1 P1, 8 P2, 1 P3). P1 означает риск потери данных; P2 — сломанный пользовательский сценарий; P3 — менее критичное трение. A01/A02 получены намеренным повреждением тестовых файлов, остальные — обычными действиями или воспроизведением плохой сети/долгой загрузки. Проверка не измеряет частоту ошибок в реальном использовании.

Первоначальные приоритеты: сохранность библиотеки (A01), обработка ошибок хранилищ (A02), качество следующей офлайн-серии (A07), сетевые состояния (A03–A05), имя и доступность (A08–A10), обратная связь Watch flow (A11). A06 исключён из дефектов по решению пользователя.

Физический Android, экранная клавиатура, системные разрешения, выбор фото, полноценный экранный диктор, успешная загрузка/проигрывание внешнего CDN и реальные импорты аккаунтов не покрыты. Полный цикл перезапуска после перетаскивания/удаления очереди не подтверждён. Набор модульных тестов в этом аудите повторно не запускался: проверялся текущий собранный JAR. Продуктовый код не менялся.

Отчёты агентов: [новичок](<D:/AniMix New Horizons/build/user-audit/new-user/findings.md>), [активный зритель](<D:/AniMix New Horizons/build/user-audit/viewer/findings.md>), [доступность и профиль](<D:/AniMix New Horizons/build/user-audit/accessibility/findings.md>), [отказоустойчивость](<D:/AniMix New Horizons/build/user-audit/reliability/findings.md>).

## Исправления и повторная проверка

Проверено на пересобранных Android APK и Desktop JAR от 22.10.2026.

- A01: ошибки чтения/декодирования больше не превращаются в пустую библиотеку; добавлены предыдущий исправный снимок, восстановление из backup и архив несовместимого исходника. Выход после ошибки запуска не записывает начальное состояние незагруженных подсистем. [Правила восстановления](<D:/AniMix New Horizons/docs/library-recovery.md>).
- A02: необработанный сбой запуска заменён состоянием ошибки с повтором. Настройки/достижения автоматически не сбрасываются; при отсутствии исправной копии восстановление остаётся ручным.
- A03/A04: ошибка карточки имеет повтор, зависимые скелетоны завершаются; действия недоступны для placeholder. Модель не сохраняет безымянные тайтлы. Успешная загрузка обновляет метаданные существующей записи, сохраняя статус, избранное и порядок.
- A05: готовая выдача основного каталога публикуется до ожидания Yummy, затем дополняется его результатами. По уточнению пользователя отдельная 10-секундная отсечка удалена: медленный ответ Yummy сохраняет участие в той же выдаче. Применяется общая HTTP-политика таймаутов/повторов. Пагинация учитывает размер основной страницы.
- A06/A07: переход по доступным скачанным сериям сохранён. Для следующей серии предпочтительно прежнее качество, при его отсутствии используется доступное; отсутствующие файлы и другая озвучка исключены.
- A08: единый счётчик и обрезка по Unicode grapheme clusters для имени и описания. Проверены обычные emoji, флаги, модификаторы, ZWJ-семьи и комбинируемые знаки.
- A09/A10: общая видимая обводка фокуса для кнопок/карточек/чипов, строк настроек, аватара/значка профиля и навбара; у стрелки возврата доступное имя «Назад», у IconCircle роль кнопки. Подтверждено AWT Tab/Enter и фактическим Compose semantics.
- A11: вторичная кнопка Watch flow отключена до готовности озвучки/серии и во время подготовки, по тем же условиям, что основная.

37 целевых тестов прошли без ошибок: библиотека, восстановление, профиль, Unicode, офлайн-очередь, доступность основного каталога при зависшем источнике, обычные/исторические достижения и надёжность выдачи наград. `assembleDebug` и `packageUberJarForCurrentOS` завершились успешно.

Повторные реальные UI-прогоны и VLC: [результаты](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/followup-results.json>), [повреждённая библиотека сохранена](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/storage-corrupt.png>), [повтор загрузки исправил карточку и библиотечные метаданные](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/detail-retry.png>), [готовая выдача при задержанном Yummy](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/search-delay.png>), [серия 2 воспроизводится в 1080p после 720p](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/offline-quality.png>), [фокус](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/keys-nav/keys-tab1.png>), [светлый редактор и счётчик 35/35](<D:/AniMix New Horizons/build/user-audit-fixes-2026-10-22/editor-light.png>).

В первичном повторном прогоне проверка pixel diff ошибочно использовала RGBA `getbbox`; она исправлена на сравнение RGB. Итоговая проверка подтверждает отличие в границах кнопки и перемещение обводки между вкладками. Сетевым ошибкам дали завершить штатные повторные запросы (12 секунд), а кеш поиска дополнен обязательными полями модели. Изменений продукта для этих исправлений тестового окружения не потребовалось. Дополнительно проверены фокус без анимаций в Dawn и отключённая вторичная кнопка Watch flow при загрузке озвучки. Физический Android и экранный диктор по-прежнему не проверялись.
