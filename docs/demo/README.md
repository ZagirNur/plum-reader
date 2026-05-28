# Plum Reader — End-to-End Demo

Прогон [`docs/api/examples/happy-path.sh`](../api/examples/happy-path.sh) на популярной книжке из Project Gutenberg — _Pride and Prejudice_ Джейн Остин (~24 MB EPUB).

## Что показывает

Через единую цепочку curl-запросов:

1. Register → JWT
2. `/me`
3. Upload EPUB (24 MB)
4. Polling `GET /books/{id}` пока `status="ready"` и `markupStatus="ready"`
5. Library list
6. Page index list
7. First page xhtml
8. PATCH progress = 5
9. Top-10 vocabulary
10. Single-word lookup на 4-х главных персонажей (Elizabeth, Darcy, Bennet, Wickham)

## Воспроизведение

```bash
# 1. Скачать EPUB (один раз):
mkdir -p /tmp/epubs
curl -sL -o /tmp/epubs/pride.epub https://www.gutenberg.org/ebooks/1342.epub.images

# 2. Поднять стек:
docker compose up -d postgres
cd server && ./gradlew bootRun &
# подождать пока в логах появится "Started PlumReaderApplication"

# 3. Прогнать e2e:
docs/api/examples/happy-path.sh /tmp/epubs/pride.epub
```

## Результат last-known-good

См. [`sample-output.txt`](./sample-output.txt) — полный вывод последнего успешного прогона.

### Сводка по показателям

| Что замерили | Значение |
|--------------|----------|
| Размер EPUB | 24 847 035 байт (~24 MB) |
| Время от upload до `status="ready"` | ~2 секунды (одна итерация polling-цикла) |
| Время до `markup_status="ready"` | в той же секунде, что и split |
| Spine pages (= рядов в `pages`) | 17 |
| Unique words (= рядов в `book_words`) | 7 083 |
| Top word | `the` × 4 843 |

### Frequencies for the main characters

Соответствуют ожиданиям читателя — Elizabeth Bennet главная героиня, Darcy главный мужской персонаж:

| word | frequency |
|------|-----------|
| elizabeth | 596 |
| darcy | 385 |
| bennet | 309 |
| wickham | 168 |

*Числа меняются от запуска к запуску при изменении токенайзера: например, после добавления curly-apostrophe handling частоты слов вроде `elizabeth's` стали отдельными от `elizabeth`. Это правильное поведение.*

### Что подтверждает прогон

- ✅ JWT auth flow (register + Bearer)
- ✅ Multipart upload 24 MB
- ✅ Async pipeline: split_epub → page rows → markup → book_words
- ✅ Status polling через `GET /books/{id}`
- ✅ Progress update endpoint
- ✅ Vocabulary endpoint с пагинацией (limit/offset/rank)
- ✅ Per-word lookup
- ✅ Все эндпоинты возвращают типизированные JSON, соответствующие `docs/api/openapi.yaml`

## Что дальше для агента интеграции

С этим прогоном на руках агент интеграции фронта/мобилки видит:
1. Полный цикл данных от EPUB до отрисовываемой страницы.
2. Реальные ответы сервера (а не только OpenAPI-схемы) — можно мокать в тестах фронта.
3. Сколько времени занимает пайплайн на типичной книге → как настраивать UX ожидания.
