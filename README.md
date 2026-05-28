# plum-reader

Книжный ридер с переводом и аудиосопровождением. Монорепа.

## Структура

- `server/` — Spring Boot 3 / Kotlin / Postgres. Владеет схемой БД (Flyway), API, очередью обработки, EPUB ingest, vocabulary index.
- `markup/` — placeholder для Python-воркера (исходный план; на текущем MVP markup реализован в Kotlin).
- `ios/`, `android/`, `web/` — клиенты ридера.
- `docs/api/` — **полная API-документация для агента интеграции фронта/бека**: OpenAPI 3.1 + markdown по auth, error registry, state machines, DTO contract, pipeline, curl-примеры. Стартовая точка: [`docs/api/README.md`](docs/api/README.md).

## Локальный запуск

```bash
docker compose up -d postgres
cd server && ./gradlew bootRun
```

База поднимается на `localhost:5432`, имя БД `plum_reader`, пользователь/пароль — `plum`/`plum`.

После старта:
- API: http://localhost:8080/api/v1/...
- Health: `GET /api/v1/health` → `{"status":"ok"}`
- Actuator (k8s probes): `/actuator/health/{liveness,readiness}`

## Quick smoke

```bash
docs/api/examples/happy-path.sh /path/to/some.epub
```

Скрипт регистрирует юзера, загружает EPUB, ждёт пайплайн, читает страницы и словарь.

## Документация для агента интеграции

`docs/api/`:

- [README.md](docs/api/README.md) — обзор и quick start
- [openapi.yaml](docs/api/openapi.yaml) — авторитативный OpenAPI 3.1 контракт
- [auth.md](docs/api/auth.md) — JWT-флоу
- [errors.md](docs/api/errors.md) — реестр стабильных кодов ошибок
- [state-machines.md](docs/api/state-machines.md) — статусы книги/markup, как поллить прогресс
- [dtos.md](docs/api/dtos.md) — DTO null-контракт по статусам
- [pipeline.md](docs/api/pipeline.md) — async-пайплайн ingest → vocabulary
- [examples/](docs/api/examples/) — curl + `.http` примеры всех эндпоинтов
