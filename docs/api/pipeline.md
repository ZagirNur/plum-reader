# Pipeline — от EPUB до vocabulary

Описание async-пайплайна. Используй это, чтобы понимать что показывать пользователю пока книга обрабатывается.

## Timeline (на типичной 24MB EPUB)

```
t=0    POST /upload (synchronous request)
       ├─ EPUB validation in-memory  ........  ~50ms
       ├─ sha256 + metadata extract  ........  ~100ms
       ├─ blob write to disk         ........  ~150ms
       ├─ DB INSERT book + user_books........   ~10ms
       └─ enqueue split_epub job     ........    ~5ms
       ◀────── 201 + UploadResponse ───── total ~300ms
                                                     book.status = "uploaded"
                                                     book.markupStatus = "pending"

t≈1s  SplitWorker polls (default 2s)
                                                     book.status = "processing"

t≈2s  SplitWorker.runSplit done
                                                     book.status = "ready"
                                                     book.pageCount = 17
                                                     pages table populated
                                                     → enqueue markup job (in-tx)

t≈3s  MarkupWorker polls
                                                     book.markupStatus = "processing"

t≈3.1s MarkupWorker.runMarkup done
                                                     book.markupStatus = "ready"
                                                     book_words populated (6984 rows)

t>3s  All endpoints fully available
```

Маленькие EPUB обычно укладываются в 2–4 секунды; 50MB книги — до минуты (доминирует split).

## UX-сценарий "ожидание после upload"

```
+──────────────────────────────────────────+
|  Pride and Prejudice                     |
|  Jane Austen                             |
|                                          |
|  [████████░░░░░░░] Обрабатываем книгу…  |  ← status=processing
|                                          |
+──────────────────────────────────────────+
```

После `book.status === "ready"`:

```
+──────────────────────────────────────────+
|  Pride and Prejudice                     |
|  Jane Austen                             |
|  17 страниц · продолжить с 5             |  ← lastPageIdx=5, ready
|                                          |
|  [Читать]    [Словарь*]                  |  ← * disabled пока markupStatus≠ready
+──────────────────────────────────────────+
```

После `markupStatus === "ready"`:

```
[Читать]    [Словарь · 6984 слова]
```

## Опрос прогресса

Простой polling — см. [state-machines.md](./state-machines.md). Альтернативы (вне MVP):
- SSE на `/api/v1/books/{id}/events`
- Webhooks от воркера
- WebSocket pub/sub на `book.<id>`

## Dedup

```
Юзер A:  POST /upload book.epub
         → 201 { book.id=42, deduplicated: false, jobId: 7 }

(пайплайн отрабатывает для book 42)

Юзер B:  POST /upload book.epub  (тот же sha256)
         → 201 { book.id=42, deduplicated: true, jobId: null }
```

`deduplicated=true` — UX: клиент **не показывает прогресс-бар**, потому что:
- Если `book.status="ready"` уже сейчас → книга сразу доступна.
- Если книга всё ещё в обработке у юзера A → `book.status` отразит это, и UI тот же polling-flow.

`jobId=null` для дедупа — нет новой job в очереди, юзер B "подключился" к существующей обработке.

## Идемпотентность retry

Если worker крашится посередине split:
1. `book.status` остаётся `processing` (не откатывается на disk).
2. `processing_jobs.state` остаётся `running` с истёкшим `locked_until`.
3. Sweep раз в минуту возвращает в `pending`.
4. Другой worker claim'ит, `attempts++`.
5. На retry: `pages.deleteByBook(book.id)` чистит partial → re-insert.

Та же логика для markup.

## Terminal failures

После `maxAttempts` (по умолчанию 5) ретраев:
- `processing_jobs.state = 'failed'` (final)
- `books.status = 'failed'` (для split fail) или `markup_status = 'failed'` (для markup fail)
- `books.error` хранит причину

UX: показать "Не удалось обработать книгу: {error}" + кнопка "Удалить из библиотеки".

В будущем — кнопка "Попробовать ещё раз" (manual re-enqueue), сейчас в roadmap.
