# AniMix user API — Yandex Cloud

Это серверная замена Cloudflare Worker для пользовательской части AniMix. Здесь **нет** каталога аниме, HLS-потоков или проксирования видео: сервер хранит только AniMix-аккаунты, сессии, пользовательскую библиотеку, статистику и ссылки на аватар/фон. Так мы не превращаем проект на десять человек в филиал дата-центра, который хранит весь Shikimori просто «на всякий случай».

## Состав

- Cloud Functions (`nodejs22`) — API и Google OAuth.
- Serverless YDB — пользователи, токены, привязки Shikimori, библиотека и статистика.
- Object Storage — публично читаемые аватары и обложки профиля; запись доступна только функции.
- Lockbox — Google/Shikimori client secrets, pepper для токенов и S3 static key. В репозиторий они не попадают.
- API Gateway — единый HTTPS адрес для Flutter и OAuth callback.

## Что переносится без смены Flutter-контракта

`/v1/auth/google/*`, `/v1/auth/refresh`, `/v1/auth/logout`, `/v1/me`, редактирование профиля, медиа профиля, публичный профиль, `/v1/library*` и legacy Shikimori OAuth token proxy. Старые публичные адреса медиа тоже сохранены: они отдают редирект в Object Storage.

## Однократная подготовка в Yandex Cloud

Не вводи секреты в PowerShell history, `.env`, GitHub Variables или скриншоты. Секреты разделены на два Lockbox secret:

`animix-oauth-secrets`:

```text
GOOGLE_CLIENT_SECRET
SHIKIMORI_CLIENT_SECRET
```

`animix-platform-secrets`:

```text
AUTH_TOKEN_PEPPER
MEDIA_ACCESS_KEY_ID
MEDIA_SECRET_ACCESS_KEY
```

1. Установить и авторизовать YC CLI, затем выбрать нужную папку:

```powershell
yc init
yc config set folder-id <FOLDER_ID>
```

2. Создать два service account и начальные роли одной командой:

```powershell
.\scripts\bootstrap-access.ps1 -FolderId '<FOLDER_ID>'
```

Она выведет оба ID. На первом проходе она даёт runtime-аккаунту `ydb.editor` и `storage.editor` на папку, чтобы не разводить IAM-квест на четыре главы. После smoke-test роли стоит сузить до конкретной БД и bucket.

3. В Object Storage создать bucket, например `animix-profile-media-<уникальный-суффикс>`, включить **public read for objects** и отключить public list. Создать static access key для runtime service account; обе части ключа положить в `animix-platform-secrets`. Публичность здесь только на изображения профилей — не на библиотеку или токены.

4. Накатить схему таблиц. Скрипт берёт короткоживущий IAM token только из памяти процесса, не пишет его ни в файл, ни в историю:

```powershell
.\scripts\apply-schema.ps1 `
  -Endpoint 'grpcs://ydb.serverless.yandexcloud.net:2135' `
  -Database '/ru-central1/<CLOUD_ID>/<DATABASE_ID>'
```

Для schema нужен твой пользователь с `ydb.editor`; runtime-функция не должна создавать таблицы на первом запросе, это был бы облачный вариант «давай миграции на проде, чё такого».

## Деплой: два коротких прохода

Google требует заранее знать точный callback URL, а API Gateway выдаёт его только после создания. Скрипт поэтому делает это без магии и без угадывания доменов.

Первый запуск создаёт Cloud Function и API Gateway, затем печатает redirect URI:

```powershell
cd E:\flutterprjcts\animix\server\yandex-cloud\api
.\scripts\deploy.ps1 `
  -FolderId '<FOLDER_ID>' `
  -FunctionServiceAccountId '<RUNTIME_SA_ID>' `
  -GatewayServiceAccountId '<GATEWAY_SA_ID>' `
  -PlatformLockboxSecretId '<PLATFORM_LOCKBOX_SECRET_ID>' `
  -OAuthLockboxSecretId '<OAUTH_LOCKBOX_SECRET_ID>' `
  -YdbEndpoint 'grpcs://ydb.serverless.yandexcloud.net:2135' `
  -YdbDatabase '/ru-central1/<FOLDER_ID>/<DATABASE_ID>' `
  -MediaBucket '<MEDIA_BUCKET>' `
  -GoogleClientId '<GOOGLE_CLIENT_ID>' `
  -ShikimoriClientId '<SHIKIMORI_CLIENT_ID>'
```

Добавь напечатанный адрес вида `https://…apigw.yandexcloud.net/v1/auth/google/callback` в **Authorized redirect URIs** того же Google Web OAuth Client. Потом запусти ту же команду с добавленным параметром:

```powershell
  -GoogleRedirectUri 'https://…apigw.yandexcloud.net/v1/auth/google/callback'
```

Скрипт собирает zip без `node_modules`: Cloud Functions для Node.js автоматически устанавливает зависимости, заявленные в `package.json` и lock-файле. Точка входа пакета — `dist/index.handler`. После деплоя проверь:

```powershell
Invoke-RestMethod 'https://<API_GATEWAY_DOMAIN>/v1/health'
```

Ожидаемый ответ: `ok = true` и `service = animix-yandex-api`.

## Переключение Flutter

Production user API и CI уже указывают на Yandex API Gateway. Для отдельного
окружения адрес можно переопределить через `ANIMIX_API_BASE_URL` и
`SHIKIMORI_OAUTH_PROXY_URL`; оба значения публичные и не являются секретами.

```powershell
flutter build windows --release --dart-define=ANIMIX_API_BASE_URL=https://<API_GATEWAY_DOMAIN>
```

Для iOS тот же `--dart-define` должен быть добавлен в CI build command. В приложении нет и не будет Google/Shikimori client secret.

## Перенос данных и откат

Cloudflare пока не удаляется и остаётся аварийным источником старых данных.
Текущим пользователям безопаснее один раз войти через Google и выполнить импорт
библиотеки Shikimori: это переносит списки в новый AniMix-профиль, не копируя
хеши старых Cloudflare-сессий. Старые сессии намеренно не мигрируются. Если D1
доступен, профиль и библиотеку можно перенести отдельно; токены, OAuth-транзакции
и одноразовые билеты переносить нельзя.

Откат — вернуть прежний `ANIMIX_API_BASE_URL` в CI и выпустить hotfix. Никаких `DROP TABLE`, удаления D1 или bucket в этом процессе нет.
