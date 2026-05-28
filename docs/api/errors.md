# Error Registry

Все ошибки сериализуются одинаково:

```json
{
  "error": "<stable_snake_case_code>",
  "message": "<human-readable, не для парсинга>",
  "details": { /* optional, structured */ }
}
```

**Контракт:** `error` — стабильный enum-код. Фронт matches на него, не на `message`. `details` — структурированные данные конкретно для этого error code (см. таблицу ниже).

## Реестр

| HTTP | `error`                  | Когда                                                                                | `details`                                  |
|------|--------------------------|--------------------------------------------------------------------------------------|--------------------------------------------|
| 400  | `validation_failed`      | DTO не прошёл `@Valid` — bean-validation                                             | `{ "<field>": "<bean-validation-message>" }` |
| 400  | `invalid_progress`       | `lastPageIdx >= book.pageCount` или `< 0`                                            | `{ "pageCount": "17" }`                    |
| 401  | `invalid_credentials`    | `/login` — неверный email или пароль (тайминг выровнен)                              | —                                          |
| 401  | —                        | Authed endpoint без `Authorization` / с невалидным JWT (empty body, `HttpStatusEntryPoint`) | —                                          |
| 404  | `book_not_found`         | книга не существует ИЛИ не в библиотеке текущего юзера                               | —                                          |
| 404  | `page_not_found`         | страница с таким `idx` отсутствует у книги                                           | —                                          |
| 404  | `word_not_found`         | слова нет в `book_words` (lookup'ы lowercase)                                        | —                                          |
| 409  | `email_already_taken`    | `/register` — email уже занят                                                         | —                                          |
| 409  | `book_not_ready`         | pages/progress endpoint вызван когда `books.status != "ready"`                       | `{ "status": "<uploaded|processing|failed>" }` |
| 409  | `markup_not_ready`       | vocabulary endpoint вызван когда `books.markup_status != "ready"`                    | `{ "markupStatus": "<pending|processing|failed>" }` |
| 413  | `file_too_large`         | upload > `spring.servlet.multipart.max-file-size` (50MB по умолчанию)               | `{ "sizeBytes": "...", "maxBytes": "..." }` |
| 422  | `unsupported_file`       | upload файл не `.epub` / пустой                                                       | —                                          |
| 422  | `invalid_epub`           | EPUB не парсится: нет ZIP-заголовка / нет mimetype / нет container.xml / ...        | —                                          |

## Стабильность

Этот список — часть публичного контракта v1. Новые коды могут добавляться. Существующие — не удаляются и не переименовываются без новой версии API.

## Логи vs ответ

`message` может содержать конкретику ("book 42 not found"), удобную для логов и debug-overlay. Не парси.

## Примеры

### 400 validation_failed
```http
POST /api/v1/auth/register
{"email":"not-an-email","password":"short"}
```
→
```json
{
  "error": "validation_failed",
  "message": "request body invalid",
  "details": {
    "email": "must be a well-formed email address",
    "password": "size must be between 12 and 128"
  }
}
```

### 409 book_not_ready
```http
GET /api/v1/books/42/pages/0
```
Книга только что загружена, split ещё не отработал.
→
```json
{
  "error": "book_not_ready",
  "message": "book 42 is in status uploaded; wait for status=ready",
  "details": { "status": "uploaded" }
}
```

### 409 markup_not_ready
```http
GET /api/v1/books/42/vocabulary
```
Pages готовы, словарь ещё нет.
→
```json
{
  "error": "markup_not_ready",
  "message": "book 42 markup is processing",
  "details": { "markupStatus": "processing" }
}
```

### 422 invalid_epub
```http
POST /api/v1/books/upload
file=<not-actually-an-epub>
```
→
```json
{
  "error": "invalid_epub",
  "message": "not a ZIP archive (missing PK header)",
  "details": null
}
```
