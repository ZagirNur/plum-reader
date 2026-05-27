# server

Spring Boot 3 (Kotlin) backend. Владеет схемой БД через Flyway, API, очередью `processing_jobs`.

## Сборка и запуск

Требуется JDK 21.

```bash
cd server
./gradlew bootRun
```

После старта доступны:

```bash
curl http://localhost:8080/api/v1/health         # публичный health → {"status":"ok"}
curl http://localhost:8080/actuator/health       # actuator (k8s probes)
```

## Тесты

```bash
./gradlew test
```

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
