# State Machines

Что показывать пользователю в каждом состоянии и какие переходы возможны.

## `books.status` — статус парсинга EPUB на страницы

```
        ┌───────────┐
        │ uploaded  │  ← после POST /upload (моментально)
        └─────┬─────┘
              │  worker claim'нул split_epub job
              ▼
        ┌───────────┐
        │processing │  ← split в процессе
        └─────┬─────┘
       ┌──────┴──────┐
       ▼             ▼
  ┌─────────┐   ┌─────────┐
  │  ready  │   │ failed  │  ← (после maxAttempts retry)
  └─────────┘   └─────────┘
```

| status | pageCount | error | UX |
|--------|-----------|-------|----|
| `uploaded` | null | null | "В очереди на обработку..." |
| `processing` | null | null | "Обрабатываем книгу...", прогресс-индикатор |
| `ready` | int | null | Открыть пагинатор, показать `pages/0` |
| `failed` | null or partial | non-null | "Не удалось обработать: {error}". Кнопка "удалить из библиотеки". |

**Pages и progress endpoints** (`/pages`, `/pages/{idx}`, `PATCH /progress`) отдают **409 `book_not_ready`** для всех статусов кроме `ready`.

## `books.markup_status` — статус словаря

Независим от `books.status`. Книгу можно читать (`status=ready`) пока словарь ещё `processing`.

```
        ┌─────────┐
        │ pending │  ← дефолт после upload
        └────┬────┘
             │  markup worker claim'нул job
             ▼
        ┌────────────┐
        │processing  │
        └─────┬──────┘
       ┌──────┴──────┐
       ▼             ▼
  ┌─────────┐   ┌─────────┐
  │  ready  │   │ failed  │
  └─────────┘   └─────────┘
```

| markup_status | UX |
|---------------|----|
| `pending` | "Словарь будет готов через несколько секунд" |
| `processing` | "Строим словарь...", optional progress dot |
| `ready` | Доступны `/vocabulary` и `/words/{word}` |
| `failed` | Скрыть vocabulary-секцию; reading работает. Опциально кнопка retry (TODO). |

**Vocabulary endpoints** отдают **409 `markup_not_ready`** для всех кроме `ready`.

## `processing_jobs.state` — внутренний state job'ов в очереди

Не отдаётся фронту напрямую. Только для бекенда / admin-tools.

```
  pending ──► running ──► done
     ▲          │
     │          ▼
     │      failed (с attempts++)
     │          │
     └──────────┘  (если attempts < maxAttempts → возвращается в очередь)
                │
                ▼  (если attempts >= maxAttempts → terminal)
            failed (final)
```

- `pending` — ждёт claim.
- `running` — worker взял лок (`locked_by`, `locked_until = now() + lockTimeout`).
- `done` — terminal success.
- `failed` — может быть retryable или terminal. `claimNext` отсекает по `attempts < maxAttempts`.
- `cancelled` — зарезервировано (manual cancel в admin tool, не используется в MVP).

### Stale-lock recovery

Если worker крашится между claim и done, его lock истекает (`locked_until < now()`). Sweep-task раз в минуту переводит такие row обратно в `pending`. Другой worker подбирает (`attempts` сохраняется — старый attempt считается).

### Two active jobs of same kind on same book — невозможно

V2 partial unique index: `UNIQUE (kind, book_id) WHERE state IN ('pending','running')`. Второй `INSERT` валится `DuplicateKeyException`. В коде это значит: после `splitWorker.runSplit` нельзя запустить второй split той же книги, пока первый не завершился.

## Поллинг прогресса с фронта

Простой подход — polling `GET /api/v1/books/{id}` каждые 1-2 сек пока `status != "ready"` и `markup_status != "ready"`:

```js
async function pollBookReady(bookId, token) {
  for (let i = 0; i < 60; i++) {
    const r = await fetch(`/api/v1/books/${bookId}`, {
      headers: { "Authorization": `Bearer ${token}` }
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const book = await r.json();
    if (book.status === "ready" && book.markupStatus === "ready") return book;
    if (book.status === "failed") throw new Error(book.error);
    await new Promise(r => setTimeout(r, 1000));
  }
  throw new Error("timeout");
}
```

Долгосрочно — webhooks/SSE/WebSocket. Не в MVP.
