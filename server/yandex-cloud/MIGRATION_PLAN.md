# AniMix: однодневная миграция Cloudflare → Yandex Cloud

## Цель и границы

Перенести **пользовательский слой AniMix** из Cloudflare Worker + D1 в российскую
инфраструктуру без изменения Flutter-контрактов и без переноса каталога аниме,
видеопотоков или данных Yummy/Shikimori в нашу БД.

После переключения пользовательский API будет доступен по Yandex API Gateway:

```text
Flutter → API Gateway → Cloud Function (Node.js 22) → YDB Serverless
                                              └────→ Object Storage (аватары/фоны)
                                              └────→ Lockbox (секреты)
```

Cloudflare остаётся read-only fallback до успешной проверки новой версии. Он не
удаляется в день миграции.

## Что переносится

| Слой | Источник | Назначение в Yandex Cloud |
| --- | --- | --- |
| Пользователи и профиль | `users` | таблица `users` в YDB |
| Google/Shikimori связи | `external_accounts` | таблица `external_accounts` |
| Сессии | `sessions` | таблица `sessions`; старые сессии не мигрируются |
| Списки и прогресс | `user_anime_entries` | таблица `user_anime_entries` |
| История | `user_history` | таблица `user_history` |
| OAuth state/tickets | `oauth_transactions`, `auth_tickets` | новые одноразовые записи в YDB |
| Аватары и фоны | `user_profile_media` | Yandex Object Storage |

Не переносим старые access/refresh-токены. При первом запуске после cutover клиент
должен один раз войти через Google: это безопаснее, чем переносить активные
сессии между разными контурами.

## Совместимый API

Новая функция обязана сохранить тела ответов и маршруты:

```text
GET    /health
GET    /v1/health
GET    /v1/auth/google/start
GET    /v1/auth/google/callback
POST   /v1/auth/google/exchange
POST   /v1/auth/refresh
POST   /v1/auth/logout
GET    /v1/me
PATCH  /v1/me/profile
PUT    /v1/me/media/avatar
PUT    /v1/me/media/banner
DELETE /v1/me/media/avatar
DELETE /v1/me/media/banner
GET    /v1/users/:id/avatar
GET    /v1/users/:id/banner
GET    /v1/library
GET    /v1/library/:shikimoriId
PUT    /v1/library/:shikimoriId
POST   /v1/library/import/shikimori
```

Это сохраняет совместимость с уже собранными Flutter-клиентами. Единственное
изменение в новом билде — `ANIMIX_API_BASE_URL` указывает на API Gateway.

## Подготовка до дня миграции

### Что делает владелец облака

1. Создаёт Yandex Cloud billing account и папку `animix-prod`.
2. Создаёт service account `animix-api-sa`.
3. Даёт ему роли:
   - `serverless.functions.invoker` для публичного вызова функции;
   - роль доступа к YDB в папке (`ydb.editor` на время развёртывания,
     затем минимально необходимая роль для runtime);
   - `storage.editor` на бакет медиа;
   - `lockbox.payloadViewer` на секреты.
4. Создаёт serverless YDB `animix-users` с лимитами защиты от счета:
   - storage limit: `1 GiB`;
   - throttling: `10–20 RU/s` на старте;
   - не отключает ограничение размера/пропускной способности.
5. Создаёт приватный bucket `animix-profile-media`.
6. Создаёт Lockbox secret `animix-api-secrets`:
   - `GOOGLE_CLIENT_SECRET`;
   - `SHIKIMORI_CLIENT_SECRET`;
   - `AUTH_TOKEN_PEPPER` (новый случайный 32+ byte secret);
   - `GOOGLE_CLIENT_ID`, `SHIKIMORI_CLIENT_ID` можно оставить обычными env vars.

### Что делает разработчик

1. Создаёт `server/yandex-cloud/api/` с Node.js 22 handler.
2. Выносит общую доменную логику из `server/shikimori-oauth-proxy/worker.ts`:
   хеширование, OAuth, сессии, библиотека, ответы профиля.
3. Заменяет D1 SQL на параметризованные YDB YQL-запросы.
4. Заменяет BLOB медиа на Object Storage:
   - приватная загрузка только по Bearer-сессии;
   - публичная раздача через API с `Cache-Control` и version query;
   - размер изображения до `1.5 MiB`, JPEG после клиентского encode.
5. Добавляет JSON-structured logs без токенов, email и секретов.
6. Создаёт migration/import CLI, который читает экспорт D1 и пишет только
   пользователей, связи, библиотеку, историю и медиа.

## Боевой план на один день

| Время | Шаг | Критерий готовности |
| --- | --- | --- |
| 00:00–00:30 | Активировать billing, создать folder, service account, YDB, bucket, Lockbox | YDB доступна, секреты не лежат в git |
| 00:30–02:30 | Поднять Node 22 Function + API Gateway | `/v1/health` возвращает `{ok:true}` из Yandex |
| 02:30–04:00 | Перенести схему и написать data importer | Локальный импорт проходит на копии экспорта |
| 04:00–05:00 | Реализовать Google OAuth + Shikimori token proxy | Google callback возвращает ticket, Shikimori secret не попадает в приложение |
| 05:00–06:00 | Реализовать библиотеку, профиль и Object Storage | Создание/редактирование профиля и запись прогресса проходят через YDB |
| 06:00–06:40 | Экспортировать D1, выполнить финальный импорт | Количество library entries совпадает по пользователям |
| 06:40–07:20 | Настроить Google OAuth redirect URI на API Gateway | Новый Google login работает без Cloudflare |
| 07:20–08:00 | Собрать Flutter с новым `ANIMIX_API_BASE_URL` | Windows smoke-test и Android/iOS CI конфигурация зелёные |
| 08:00–09:00 | Canary на одном аккаунте, затем cutover | Профиль, аватар, фон, библиотека, импорт и relogin работают |

Время дано с запасом. Если Google Console или IAM начинают требовать шаманский
бубен, они обычно и съедают весь резерв, а не код.

## Порядок переноса данных

1. В Cloudflare включить короткое окно записи: кнопки изменения списка и профиля
   временно показывают «идёт обслуживание».
2. Экспортировать D1 в локальный зашифрованный файл. Экспорт не коммитится и не
   уходит в GitHub.
3. Выполнить importer в YDB в транзакциях/батчах.
4. Загружать профильные изображения в Object Storage под ключами:
   `profiles/<user-id>/avatar/<version>.jpg` и
   `profiles/<user-id>/banner/<version>.jpg`.
5. Сверить:
   - число пользователей;
   - число связанных Shikimori аккаунтов;
   - число записей библиотеки по статусам;
   - число history entries;
   - наличие avatar/banner.
6. Не переносить `sessions`, `oauth_transactions` и `auth_tickets`.
7. Включить запись на новом API.

## OAuth cutover

1. В Google Cloud Console добавить новый exact URI:
   `https://<gateway-id>.apigw.yandexcloud.net/v1/auth/google/callback`.
2. В Lockbox/Function задать этот же URI как `GOOGLE_REDIRECT_URI`.
3. Оставить старый Cloudflare redirect URI зарегистрированным на 24 часа для
   уже открытых браузерных вкладок.
4. В Flutter изменить только `ANIMIX_API_BASE_URL` в GitHub Variables/CI и
   сборочных dart-defines. Никаких OAuth secrets в IPA/EXE.
5. После выхода новой версии удалить старый Cloudflare URI из Google Console.

## Проверка перед cutover

```text
[ ] GET /v1/health без VPN
[ ] Google login создаёт AniMix user
[ ] перезапуск приложения не требует relogin
[ ] refresh-token обновляется один раз при параллельных запросах
[ ] профиль обновляется после PATCH без перезапуска
[ ] avatar/banner появляются на другом тестовом аккаунте
[ ] библиотека: planned/watching/completed/on_hold/dropped/rewatching
[ ] import Shikimori переносит все статусы
[ ] Google/Shikimori secrets не встречаются в git diff, логах, APK/IPA/EXE
[ ] запросы с недействительным токеном получают 401, а не 500
```

## Откат

До успешного canary Cloudflare не удаляется.

Если один из критических чеков не пройден:

1. вернуть прежний `ANIMIX_API_BASE_URL` в GitHub Variables;
2. собрать предыдущий клиент или включить его endpoint через Remote Config;
3. выключить запись в Yandex, сохранить логи без персональных данных;
4. исправить проблему на staging-функции, повторить canary.

## После миграции

1. Настроить budget alert на `1 ₽` и лимиты YDB.
2. Оставить Cloudflare в режиме read-only 7 дней, затем экспортировать архив и
   удалить Worker/D1/secrets вручную.
3. Добавить ежедневный timer backup YDB → Object Storage и хранить 7 дней.
4. Добавить в приложение remote endpoint manifest: новый origin можно менять
   без выпуска IPA, но manifest должен быть подписан или жёстко ограничен
   allowlist, иначе это будет удалённая кнопка «взломай меня».

## Решение о старте

Стартовать миграцию можно после двух подтверждений:

1. Billing account в Yandex Cloud активен.
2. Владелец готов добавить Yandex API Gateway URI в Google OAuth Client.

После этого следующий коммит создаёт `server/yandex-cloud/api/`, YDB-схему,
импортер и инструкции развёртывания. Никакие ключи, дампы D1 или пользовательские
медиа не добавляются в Git.
