# server

Spring Boot 3 (Kotlin) backend. Владеет схемой БД через Flyway, API, очередью `processing_jobs`.

## Сборка и запуск

Требуется JDK 21 и запущенный Postgres из корневого `docker-compose.yml`.

```bash
docker compose up -d postgres        # из корня репо, разово
cd server
./gradlew bootRun                    # сам поднимет миграции через Flyway
```

После старта доступны:

```bash
curl http://localhost:8080/api/v1/health         # публичный health → {"status":"ok"}
curl http://localhost:8080/actuator/health       # actuator (k8s probes), отражает состояние БД
```

## Тесты

```bash
./gradlew test
```

Интеграционные тесты используют Testcontainers (`postgres:16-alpine`) — нужен запущенный Docker daemon. `@WebMvcTest`-срезы (например, `HealthControllerTest`) контейнеров не требуют.

## Схема БД

Владеет миграциями только этот модуль (`src/main/resources/db/migration/V*.sql`).
Python markup worker читает ту же БД, но миграции **не пишет** — это контракт.

## Структура пакетов

Feature-sliced, по доменам — все новые controller/service/repository кладём в свой пакет, а не в `controller/`/`service/`-слои:

```
com.plum.reader
├── health/        # liveness ping для бота/мобилки
├── auth/          # Telegram WebApp HMAC, accounts (PR #4)
├── books/         # книги + загрузка EPUB (PR #5)
├── pages/         # страницы книги (PR #5+)
├── jobs/          # processing_jobs очередь (PR #6+)
├── storage/       # файлы EPUB на диске (PR #5)
└── common/        # утилиты, GlobalExceptionHandler, errors
```
