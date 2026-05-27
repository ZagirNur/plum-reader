# plum-reader

Книжный ридер с переводом и аудиосопровождением. Монорепа.

## Структура

- `server/` — Spring Boot 3 / Kotlin / Postgres. Владеет схемой БД (Flyway), API, очередью обработки.
- `markup/` — Python-воркер (uv): ингест EPUB, разметка spaCy, LLM-обогащение, TTS.
- `ios/`, `android/`, `web/` — клиенты ридера.
- `docs/` — архитектурные документы и фиксированные решения.

## Локальный запуск

```bash
docker compose up -d postgres
```

База поднимается на `localhost:5432`, имя БД `plum_reader`, пользователь/пароль — `plum`/`plum`.
