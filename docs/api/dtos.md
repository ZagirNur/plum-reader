# DTO Reference

Каждый DTO с поленным null/not-null контрактом. Точный shape — в [openapi.yaml](./openapi.yaml).

## Auth

### RegisterRequest

```json
{
  "email": "alice@example.com",
  "password": "supersecret-pass",
  "name": "Alice"
}
```
| field | type | constraints |
|-------|------|-------------|
| email | string | required, RFC 5322 |
| password | string | required, length 12..128 |
| name | string? | optional, length ≤ 200 |

### LoginRequest

```json
{ "email": "alice@example.com", "password": "supersecret-pass" }
```
| field | type | constraints |
|-------|------|-------------|
| email | string | required |
| password | string | required, not blank |

### AuthResponse / MeResponse

```json
{
  "token": "eyJ…",
  "user": { "id": 1, "email": "alice@example.com", "name": "Alice" }
}
```
| field | type | nullable |
|-------|------|----------|
| token | string | never |
| user.id | int64 | never |
| user.email | string | never |
| user.name | string? | nullable |

## Books

### UploadResponse

```json
{
  "book": { ...BookResponse... },
  "deduplicated": false,
  "jobId": 7
}
```
| field | type | semantics |
|-------|------|-----------|
| book | BookResponse | full book row после upload |
| deduplicated | bool | `true` если sha256 уже был в системе → клиент не показывает progress-bar |
| jobId | int64? | `null` если deduplicated, иначе id `split_epub` job в очереди |

### BookResponse (минимальная форма, возвращается из `/upload`)

| field | type | nullable | semantics |
|-------|------|----------|-----------|
| id | int64 | never | |
| title | string? | по статусу — см. ниже | DC `<title>` |
| author | string? | по статусу | DC `<creator>` |
| language | string? | по статусу | DC `<language>` (e.g. `"en"`) |
| status | enum | never | `uploaded`/`processing`/`ready`/`failed` |
| sizeBytes | int64 | never | |
| sha256 | string | never | lowercase hex |
| pageCount | int? | nullable | non-null когда `status=ready` |
| createdAt | ISO 8601 | never | |
| updatedAt | ISO 8601 | never | |

### BookSummary (для `GET /books`)

Минимум полей для library-screen.

| field | type | nullable |
|-------|------|----------|
| id | int64 | never |
| title / author / language | string? | nullable |
| status | enum | never |
| markupStatus | enum | never |
| pageCount | int? | null когда `status != ready` |
| lastPageIdx | int? | null если юзер ещё не открывал/не сохранял прогресс |
| addedAt | ISO 8601 | never; когда юзер добавил в библиотеку |
| updatedAt | ISO 8601 | never; обновление Book row, не user_books |

### BookDetailResponse (для `GET /books/{id}`)

Все поля Summary + `sizeBytes`, `sha256`, `error`, `createdAt`.

| field | extra over Summary |
|-------|--------------------|
| sizeBytes | int64 |
| sha256 | string (64 hex) |
| error | string?; non-null только при `status=failed` |
| createdAt | ISO 8601 |

### Nullable contract по статусам

| status | title/author/language | pageCount | error |
|--------|------------------------|-----------|-------|
| `uploaded` | nullable (parsed at upload) | null | null |
| `processing` | nullable | null | null |
| `ready` | nullable (EPUB может не иметь DC) | **int** | null |
| `failed` | nullable | nullable | **string** |

### BookListResponse

```json
{
  "items": [ ...BookSummary... ],
  "more": false
}
```
`items` ограничен сервером (LIMIT 200). `more=true` — soft-hint что есть ещё. Cursor pagination — следующий API-релиз.

## Pages

### PageIndexListResponse (`GET /books/{id}/pages`)

```json
{ "bookId": 42, "total": 17, "indexes": [0, 1, 2, ..., 16] }
```

### PageContentResponse (`GET /books/{id}/pages/{idx}`)

```json
{
  "bookId": 42,
  "idx": 0,
  "total": 17,
  "xhtml": "<?xml version=\"1.0\"?>...",
  "textLen": 4321,
  "prevIdx": null,
  "nextIdx": 1
}
```
- `prevIdx === null` ⇔ первая страница
- `nextIdx === null` ⇔ последняя страница
- `xhtml` — raw spine document. Клиент рендерит как HTML/WebView/SwiftUI WebView, **обязательно** в sandbox-окружении (не позволять JS, не подключать internet — EPUB-XHTML может содержать `<script>`, `<iframe>`).

## Progress

### ProgressUpdateRequest

```json
{ "lastPageIdx": 5 }
```
| field | type | constraints |
|-------|------|-------------|
| lastPageIdx | int | `0 <= lastPageIdx < book.pageCount` |

Semantics: "the page the client considers current". На следующий открытии этой книги на любом устройстве reader resume'нет здесь.

### ProgressResponse

```json
{ "bookId": 42, "lastPageIdx": 5, "updatedAt": "2026-05-27T20:00:00Z" }
```
`updatedAt` — server wall-clock, для last-write-wins при multi-device sync.

## Vocabulary

### VocabularyResponse (`GET /books/{id}/vocabulary`)

```json
{
  "bookId": 42,
  "total": 6984,
  "offset": 0,
  "limit": 100,
  "items": [
    { "word": "the", "frequency": 4843, "rank": 1 },
    { "word": "to",  "frequency": 4405, "rank": 2 },
    ...
  ]
}
```
- `total` — сколько unique words в книге.
- `limit`: query param `?limit=` (cap 500, default 100).
- `offset`: query param `?offset=` (default 0).
- `items` отсортированы `frequency DESC, word ASC`.
- `rank` — 1-based position в полном top-N (т.е. `rank = offset + index + 1`). Стабильно при tie-break изменениях.

### WordEntry (`GET /books/{id}/words/{word}`)

```json
{ "word": "elizabeth", "frequency": 632, "rank": null }
```
Single-word lookup. `rank: null` — для одиночных запросов rank не вычисляется (клиент использует `/vocabulary`-эндпоинт для rank). Поле остаётся в shape ради униформности типа.

## Discriminated-union pattern для TS

```ts
type Book =
  | { status: "uploaded"  | "processing"; pageCount: null; error: null }
  | { status: "ready";    pageCount: number; error: null }
  | { status: "failed";   pageCount: number | null; error: string };
```

Markup orthogonal:
```ts
type MarkupStatus = "pending" | "processing" | "ready" | "failed";
```
