# Деплой Shigu's Dream backend (публичный адрес для клиентов)

Клиенты (мод) подключаются к **backend**, база всегда за ним. Публичный backend = оба игрока просто указывают один адрес в `config/shigusdream.json`, туннели и пробросы портов не нужны. Все платформы ниже дают валидный TLS, поэтому мод автоматически использует `wss://`.

Схема автосоздаётся в БД при старте (`CREATE TABLE IF NOT EXISTS`) — ручное применение `db/init.sql` не требуется.

## Вариант 1: Render.com (бесплатно, без карты)

1. Залейте проект в GitHub-репозиторий (render.yaml в корне).
2. Render → **New → Blueprint** → выберите репозиторий — создадутся web-сервис (Docker) и бесплатная PostgreSQL.
3. Дождитесь деплоя — адрес вида `https://shigusdream-backend.onrender.com`.
4. В моде: `"backendUrl": "https://shigusdream-backend.onrender.com"`.

Особенности free-плана: бесплатная БД живёт ~30 дней (потом пересоздать/обновить); сервис «засыпает» после ~15 мин без трафика — первое подключение после паузы может занять до минуты (мод сам переподключится по backoff).

## Вариант 2: Railway (триал, потом ~$5/мес)

1. Railway → **New Project → Deploy from GitHub repo** (корень репо; Railway сам увидит `backend/Dockerfile`? укажите его в настройках сервиса: Root Directory = `backend`).
2. Добавьте плагин **PostgreSQL** — Railway даст переменную `DATABASE_URL` (приложение понимает её нативно).
3. Задайте переменные: `SHIGU_STORAGE=postgres`, `SHIGU_JWT_SECRET=<длинный случайный>`.
4. Адрес сервиса (Settings → Networking → Generate Domain) → в `backendUrl`.

Плюс: без «сна», WebSocket-соединения живут стабильно.

## Вариант 3: VPS (Docker, полный контроль)

```bash
git clone <репозиторий> && cd shigus-dream
SHIGU_JWT_SECRET="<длинный случайный>" docker compose up -d --build
# готово: http://<ip-сервера>:8080 (перед ним можно поставить Caddy/nginx для TLS)
```

## Вариант 4: Fly.io

```bash
fly launch --no-deploy          # создаст приложение по fly.toml
fly postgres create             # или использовать Neon (ниже)
fly postgres attach <db-app>
fly secrets set SHIGU_JWT_SECRET="<длинный случайный>"
fly deploy
```

## Внешняя управляемая БД (Neon / Supabase / Render DB)

Работает с любым вариантом размещения backend: задайте одну переменную —

```
SHIGU_DATABASE_URL=postgres://user:pass@host/db     # или DATABASE_URL — приложение поймёт само
SHIGU_STORAGE=postgres
```

Приложение само конвертирует `postgres://` в JDBC и включает `sslmode=require`. Для Neon прямо в строке уже будет `?sslmode=require` — тоже ок.

## После деплоя — у клиентов

`config/shigusdream.json`:

```json
{ "backendUrl": "https://<ваш-адрес>", "autoConnect": true }
```

Коды привязки: `https://<ваш-адрес>/link`. Первый созданный аккаунт — admin.

## Чек-лист безопасности

- `SHIGU_JWT_SECRET` — случайная строка 40+ символов (по умолчанию в дев-режиме стоит небезопасный `dev-insecure-secret-change-me`).
- База не должна быть доступна извне без нужды; SSL включён по умолчанию.
- Логи не содержат токенов; refresh-токены хранятся только у клиентов.
