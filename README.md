# Shigu's Dream

Система клиентского управления для **Minecraft 26.1.2 + Fabric**: авторизованный клиент отправляет заранее разрешённые локальные действия (`ClientAction`) другим клиентам с установленным модом.

**Minecraft-сервер полностью вне системы** — не нужны моды на сервере, плагины, OP или изменения конфигурации.

```
MC Client A (мод) ──WebSocket──> Backend / Gateway ──WebSocket──> MC Client B (мод)
                                     │
                                     ▼
                                 PostgreSQL
```

Backend определяет **кто, кому и что** отправляет. Клиентский мод определяет **как локально выполнить** разрешённое действие.

---

## Состав

| Компонент | Технология | Расположение |
|---|---|---|
| Клиентский мод | Kotlin, Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Java 25 | `mod/` |
| Backend / Gateway | Kotlin, Ktor 3.5.2, java-jwt, HikariCP | `backend/` |
| Схема БД | PostgreSQL (`users`, `user_permissions`, `link_codes`, `commands`) | `db/init.sql` |
| E2E-тесты | Python 3 + websockets | `tests/smoke_test.py` |

## Сборка

```bat
:: Мод (jar появится в mod\build\libs\shigusdream-0.4.5.jar)
cd mod && gradlew.bat build

:: Backend (дистрибутив в backend\build\install\backend)
cd backend && gradlew.bat installDist
```

Требуется JDK 25. `gradle/gradle-daemon-jvm.properties` в обоих проектах указывает Gradle использовать toolchain 25 автоматически.

## Запуск backend

**Публичный деплой (Render.com, Blueprint)**: репозиторий содержит `render.yaml` — в Render: New → Blueprint → выбрать репозиторий; создадутся web-сервис и бесплатная PostgreSQL, схема применится автоматически. Переменные: `SHIGU_JWT_SECRET`, `SHIGU_RECOVERY_SECRET` (генерируются), `SHIGU_DATABASE_URL` (провяжется автоматически; понимаются и `SHIGU_DB_JDBC`, и формат `postgres://`).

**Режим in-memory (по умолчанию)** — данные в памяти, сбрасываются при рестарте. Ничего устанавливать не нужно:

```bat
cd backend\build\install\backend\bin
set SHIGU_STORAGE=memory
backend.bat
```

**Режим PostgreSQL (продакшен, по ТЗ)** — через docker-compose (поднимает Postgres + backend, схема применяется автоматически):

```bat
docker compose up -d
```

Переменные окружения backend: `SHIGU_PORT` (8080), `SHIGU_STORAGE` (`memory`|`postgres`), `SHIGU_DB_JDBC`, `SHIGU_DB_USER`, `SHIGU_DB_PASSWORD`, `SHIGU_JWT_SECRET` (**обязательно задайте случайный секрет в проде**).

> **HTTPS/WSS:** для локальной разработки используется `http/ws`. В проде ставьте TLS-прокси (nginx/Caddy) перед backend — мод поддерживает `https://` URL (сам переключится на `wss://`).

## Запуск мода

1. Установите Fabric Loader 0.19.3+ для Minecraft **26.1.2**, положите в `mods/`:
   - `shigusdream-0.4.5.jar`
   - Fabric API и fabric-language-kotlin (Loom'овский `runClient` подтягивает их сам).
2. Запустите игру. В `config/shigusdream.json` укажите `backendUrl` (по умолчанию `http://localhost:8080`), `autoConnect`, `showHud`, `requireAdminWand`.
3. При первом подключении мод попросит backend выдать **одноразовый код привязки** и покажет его в чате.
4. Откройте в браузере `http://<backend>/link`, введите код и имя аккаунта → мод автоматически завершит вход (первый созданный аккаунт получает роль **admin**).
5. Токены хранятся локально в `config/shigusdream/tokens.json` и в логи не пишутся.

### Управление в игре

| Клавиша | Действие |
|---|---|
| **K** | Открыть Admin Panel |
| **J** | Статус соединения / переподключение |

### Admin Panel

```
Target:  [ список presence: username ● online / ○ offline ]
Action:  [ show_message / notification / play_sound ]

Arguments:
[ динамические поля по схеме действия ]

[ SEND ]
```

Список действий и схемы аргументов: `GET /actions` — тот же реестр валидирует backend, поэтому панель строит поля динамически.

## Действия (MVP)

| Действие | Аргументы | Эффект |
|---|---|---|
| `shigusdream:show_message` | `text` (≤256, required), `duration` (20–1200 тиков, по умолчанию 100) | Текст по центру экрана с затуханием |
| `shigusdream:notification` | `title` (≤128, required), `description` (≤256), `type` (info/success/warning/error) | Системный toast |
| `shigusdream:play_sound` | `sound` (required, обязан быть в реестре SoundEvent), `volume` (0–1), `pitch` (0.5–2) | Звук на клиенте |

Разрешены **только** зарегистрированные действия. Произвольный код, shell-команды, `eval`, выдача предметов, изменение мира/инвентаря, обход серверных permissions и инъекция пакетов исключены архитектурой.

## Аутентификация и безопасность

- Minecraft UUID — **идентификатор, но не секрет**. Привязка аккаунта — по одноразовому коду (TTL 15 минут, однократный), подтверждаемому на HTML-странице backend.
- Доступ — JWT: короткоживущий access (15 мин) + refresh (30 дней). Проверка прав на backend **до** доставки команды: роль `admin` → всё; обычным пользователям права выдаются точечно (`client.admin`, `client.action.<name>`) через `POST /users/{id}/permissions`.
- Каждый запрос несёт уникальный `request_id`; дедупликация и на backend (уникальный индекс), и на клиенте (LRU на 1000 последних) — повторное выполнение одного запроса невозможно.
- Логирование backend: `sender, target, action, request_id, status, error` для каждой команды. Токены и секреты не логируются.
- Соединение: ping/pong, реконнект с экспоненциальным backoff `1s → 2s → 4s → 8s → 16s → 30s`.

## HTTP API

```
POST /auth/link          {mc_uuid, mc_name} -> {link_code, confirm_url}
GET  /link               HTML-форма подтверждения кода
POST /auth/refresh       {refresh_token} -> {access_token}

GET  /users              (admin) список + online-статусы
GET  /users/{id}         (admin)
POST /users/{id}/role    (admin) {role: admin|user}
POST /users/{id}/permissions        (admin) {permission}
DELETE /users/{id}/permissions/{permission}  (admin)

GET  /actions            реестр действий со схемами
GET  /commands/{id}      (admin)
POST /commands/{id}/cancel  (admin) — только pending

GET  /ws                 WebSocket
```

WebSocket-протокол: конверт `{protocol_version: 1, message_type, request_id, payload}`; типы `auth`, `auth.pending`*, `auth.success`, `auth.error`, `presence.list`, `action.execute`, `action.result`, `action.error`, `ping`, `pong`. (* `auth.pending` — расширение: ожидание подтверждения кода привязки.)

Режимы доставки команд: `immediate` — только online (иначе `target_offline`); `queued` — хранится в БД (`pending`) и доставляется при следующем подключении цели. Статусы: `pending → delivered → executed | failed | expired | cancelled`.

## Проверка

- Юнит-тесты мода (codec/protocol/schema/dedup): `cd mod && gradlew.bat test` — без зависимостей от Minecraft.
- Полный E2E smoke-тест backend: запустите backend и `python tests/smoke_test.py` — два «клиента» проходят привязку, presence, доставку действий, дедуп, offline/queued, cancel, permissions. **19/19 OK.**
- В среде разработки применён JDK 25 (`JAVA_HOME` в системе указывал на JDK 8 — Gradle получает toolchain через `gradle-daemon-jvm.properties`).

## Следующий этап (по ТЗ, не входит в MVP)

`custom_data`-детекция предметов (каркас `CustomDataReader` уже есть, включая admin_wand), particles, entity highlighting, `open_gui`, `set_hud_state`, расширенные permissions.
