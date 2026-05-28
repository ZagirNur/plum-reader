# Plum Reader API — Integration Guide

Полная документация бэкенда для агента интеграции фронта/мобилки/бота.

## Что это

Plum Reader — Spring Boot 3 + Kotlin сервер. Принимает EPUB, разрезает на страницы, строит частотный словарь, отдаёт всё это клиенту через REST + JWT.

Stack: Spring Boot 3.5, Kotlin 2.0, JDK 21, PostgreSQL 16 (Flyway-managed).

## Где что лежит

| Файл | О чём |
|------|-------|
| [openapi.yaml](./openapi.yaml) | OpenAPI 3.1 — авторитативный контракт. Генерируй TS-клиент отсюда. |
| [auth.md](./auth.md) | JWT-флоу: register → login → use `Authorization: Bearer …`. |
| [errors.md](./errors.md) | Реестр кодов ошибок. Фронт matches на `error` string. |
| [state-machines.md](./state-machines.md) | `books.status`, `books.markup_status`, `processing_jobs.state` — что значит, какие переходы возможны, что показывать пользователю. |
| [dtos.md](./dtos.md) | Каждый DTO с поленным контрактом null/not-null по статусам. |
| [examples/](./examples/) | curl-примеры всех эндпоинтов: success + типичные ошибки. |
| [pipeline.md](./pipeline.md) | Жизненный цикл книги от upload до vocabulary. Используй это, чтобы понимать как опрашивать прогресс на фронте. |

## Базовый URL и версионирование

- Все эндпоинты под `/api/v1/`. Версия в URL.
- Health: `GET /api/v1/health` → `{"status":"ok"}`.
- Actuator (для k8s probes, не для фронта): `GET /actuator/health`, `GET /actuator/info`.

## Краткая карта эндпоинтов

| Метод + Path | Auth | О чём |
|--------------|------|-------|
| `POST /api/v1/auth/register` | ❌ | register, returns JWT |
| `POST /api/v1/auth/login` | ❌ | login, returns JWT |
| `GET  /api/v1/me` | ✅ | current user |
| `POST /api/v1/books/upload` | ✅ | upload EPUB (multipart) |
| `GET  /api/v1/books` | ✅ | library (paged via LIMIT 200 + `more`) |
| `GET  /api/v1/books/{id}` | ✅ | book detail |
| `GET  /api/v1/books/{id}/pages` | ✅ | indexes of pages |
| `GET  /api/v1/books/{id}/pages/{idx}` | ✅ | page xhtml |
| `PATCH /api/v1/books/{id}/progress` | ✅ | save reading position |
| `GET  /api/v1/books/{id}/vocabulary` | ✅ | top-N words by frequency |
| `GET  /api/v1/books/{id}/words/{word}` | ✅ | one word's frequency |

## Что важно знать перед интеграцией

1. **JWT короткий (60 мин по умолчанию).** Refresh-токенов ещё нет — клиент должен заново вызвать `/login` когда сервер вернёт 401 на authed запрос. Никаких retry-loop'ов на 401 на одном токене.
2. **Pipeline асинхронный.** После `POST /upload` книга в `status=uploaded`, `markup_status=pending`. Реальные страницы и словарь появляются через несколько секунд (см. [pipeline.md](./pipeline.md)). Клиент должен polling'ить `GET /books/{id}` либо опираться на `status`/`markup_status` в `GET /books`.
3. **Per-user library.** Книга принадлежит юзеру через таблицу `user_books`. Книги других юзеров для текущего юзера — 404 (не 403), чтобы не утекало само существование.
4. **Dedup по sha256.** Два юзера, загружающих одну и ту же EPUB, получают одну и ту же `book.id` — это норм. Поле `deduplicated: true` в `UploadResponse` подсказывает фронту "не показывай прогресс-бар обработки, всё уже готово".
5. **CORS закрыт по умолчанию.** Перед запуском фронта установи `PLUM_CORS_ALLOWED_ORIGINS=https://app.example.com,http://localhost:5173`. По умолчанию same-origin only.
6. **Все timestamp'ы — ISO 8601 UTC** (`2026-05-27T20:00:00Z`).
7. **`status` и `markup_status` — стабильные enum-строки** (см. [state-machines.md](./state-machines.md)). Фронт matches на `"uploaded" | "processing" | "ready" | "failed"`.

## Quick start (15 минут до первой работающей страницы)

```bash
# 1. Поднять Postgres + сервер
docker compose up -d postgres
cd server && ./gradlew bootRun

# 2. Register
TOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"longerthan12chars","name":"You"}' \
  | jq -r .token)

# 3. Upload какой-нибудь EPUB
curl -s -X POST http://localhost:8080/api/v1/books/upload \
  -H "Authorization: Bearer $TOK" \
  -F "file=@/path/to/book.epub" | jq

# 4. Подождать ~2s, читать
curl -s -H "Authorization: Bearer $TOK" \
  http://localhost:8080/api/v1/books | jq

curl -s -H "Authorization: Bearer $TOK" \
  http://localhost:8080/api/v1/books/1/pages/0 | jq -r .xhtml
```

## Зачем такие конкретные DTO

DTO построены так, чтобы агент интеграции мог:
- Сгенерить TS-типы из `openapi.yaml`.
- Не угадывать nullable-контракт — он зафиксирован в [dtos.md](./dtos.md).
- Не парсить error-message на фронте — все ошибки имеют стабильный `error` enum-код (см. [errors.md](./errors.md)).

См. [pipeline.md](./pipeline.md) для UX-сценариев "пользователь загрузил книгу — что показывать в UI пока она обрабатывается".
