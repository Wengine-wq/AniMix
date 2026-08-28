# AniMix Cloudflare Worker

Worker обслуживает собственную пользовательскую сессию AniMix и сохраняет
обратную совместимость со старым Shikimori OAuth. Каталог аниме и HLS-ссылки
не переносятся в D1: база хранит пользователей, сессии, внешние аккаунты,
списки и настройки.

## Авторизация

Основной вход — Google OAuth через `workers.dev`, поэтому собственный домен и
почтовый сервис не нужны:

1. `GET /v1/auth/google/start?return_uri=animix%3A%2F%2Foauth%2Fcallback` создаёт короткоживущий OAuth state и отправляет пользователя в Google.
2. `GET /v1/auth/google/callback` обменивает код на Google-профиль, создаёт или находит локального пользователя и выдаёт одноразовый ticket.
3. Приложение вызывает `POST /v1/auth/google/exchange` с `{ "ticket": "...", "state": "..." }`.
4. Worker возвращает AniMix access/refresh-сессию.

Google scopes ограничены `openid email profile`. Google подтверждает email, а
AniMix хранит только собственный профиль и связь `provider_subject`.

## Настройка Google OAuth

В Google Cloud Console создать OAuth Client типа Web application. В Authorized
redirect URI добавить:

```text
https://animix-shikimori-oauth.shikimori-oauth-proxy.workers.dev/v1/auth/google/callback
```

Затем заменить `GOOGLE_CLIENT_ID` в `wrangler.jsonc` и сохранить секрет:

```bash
npx wrangler secret put GOOGLE_CLIENT_SECRET
npx wrangler deploy
```

OAuth client secret никогда не добавлять во Flutter, `.env` или Git.

## D1 и секреты

```bash
npx wrangler d1 migrations apply animix-users --remote
npx wrangler secret put SHIKIMORI_CLIENT_SECRET
npx wrangler secret put AUTH_TOKEN_PEPPER
npx wrangler secret put GOOGLE_CLIENT_SECRET
npx wrangler deploy
```

В D1 лежат только хэши AniMix-токенов. Access-сессия действует 15 минут,
refresh-сессия — 30 дней и ротируется при обновлении.

## Служебные endpoints

- `GET /health` и `GET /v1/health` — health check;
- `GET /v1/me` — профиль и базовая статистика;
- `POST /v1/auth/refresh` — ротация refresh-сессии;
- `POST /v1/auth/logout` — отзыв access-сессии;
- `POST /` — старый Shikimori token exchange для уже установленных сборок.
