# server

Spring Boot 3 (Kotlin) backend. Владеет схемой БД через Flyway, API, очередью `processing_jobs`.

Миграции запускает только сервер — Python-воркер из `markup/` к DDL не прикасается.

## Сборка и запуск

Требуется JDK 21 и поднятый Postgres (см. корневой `docker-compose.yml`):

```bash
docker compose up -d postgres
cd server
./gradlew bootRun
```

Flyway при старте накатит миграции из `src/main/resources/db/migration/`.

Healthcheck:

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

## Тесты

```bash
./gradlew test
```

Тесты сейчас slice-уровня (`@WebMvcTest`) и БД не требуют. Интеграционный
прогон против реального Postgres появится в финальном PR Фазы 1
(Testcontainers).
