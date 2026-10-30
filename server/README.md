# Сервер AniMix New Horizons

Node + PostgreSQL: вход через Google, онлайн-профиль (имя, «О себе», значок, аватар, баннер), списки, достижения, синхронизация документа аккаунта, друзья и заявки, онлайн-статус устройств и Horizon Link. Аватары и баннеры хранятся файлами на сервере (`MEDIA_DIR`) и раздаются по `/media/`.

Сейчас развёрнут на VDSina: `193.124.64.116`, `https://193-124-64-116.sslip.io`.

## Разработка

```powershell
cd server
npm install
npm test        # сборка TypeScript + тесты на PostgreSQL в памяти (pg-mem)
```

Схема — `schema/001_initial.sql`: только `CREATE … IF NOT EXISTS` и `ALTER … ADD COLUMN IF NOT EXISTS`, применяется при каждом старте и не трогает существующие данные.

## Развёртывание (`deploy/`)

```powershell
powershell -ExecutionPolicy Bypass -File server\deploy\deploy.ps1 -Ip 193.124.64.116
```

- Загружает `server/` (без `node_modules`, `dist`, `deploy`) в `/opt/animix/api` и запускает `setup.sh`: swap, Docker (MTU 1400), `ufw` 22/80/443, вход по SSH только по ключу `%USERPROFILE%\.ssh\animix_vdsina`, сборка и запуск.
- `/opt/animix/.env` создаётся один раз (OAuth-клиенты Google и Shikimori из прежней функции Yandex Cloud через `yc`; пароль базы и `AUTH_TOKEN_PEPPER` — новые случайные) и при повторных развёртываниях сохраняется.
- API, Caddy (HTTPS Let's Encrypt) и бэкап работают в сети хоста: исходящие соединения из bridge-сетей Docker у этого провайдера подвисают. PostgreSQL слушает только `127.0.0.1`.
- Бэкап каждую ночь: `animix-<дата>.dump` и `media-<дата>.tgz` в `/opt/animix/backups`, 14 дней. На ПК: `deploy\backup.ps1 -Ip <IP>`.
- Google Cloud Console → OAuth-клиент → Authorized redirect URIs: `https://<адрес>/v1/auth/google/callback`. Приложение на ПК возвращается на `http://127.0.0.1:<порт>/oauth/callback`, на Android — на `animix://oauth/callback`.
