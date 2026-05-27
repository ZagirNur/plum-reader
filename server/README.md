# server

Spring Boot 3 (Kotlin) backend. Владеет схемой БД через Flyway, API, очередью `processing_jobs`.

## Сборка и запуск

Требуется JDK 21.

```bash
cd server
./gradlew bootRun
```

После старта доступен healthcheck:

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

## Тесты

```bash
./gradlew test
```
