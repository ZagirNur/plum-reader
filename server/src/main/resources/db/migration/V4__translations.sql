-- V4: contextual word translation cache.
--
-- Word-Wise UX: пользователь тапает на слово в странице → бэкенд возвращает
-- перевод этого слова С УЧЁТОМ КОНТЕКСТА (соседнего предложения). Один и тот
-- же word может иметь разные переводы в разных контекстах (`run a company`
-- ≠ `run a mile`) — поэтому ключ кэша включает context_hash.
--
-- Логика:
--   1. Юзер тапает на слово в page N.
--   2. Фронт шлёт {pageIdx, start, end, targetLang} → бэкенд.
--   3. Бэкенд извлекает word + ±200 символов контекста.
--   4. context_hash = sha256(book_id || page_idx || lower(word) || lower(context) || lang_to).
--   5. lookup в кэше: hit → return; miss → LLM → save → return.

CREATE TABLE word_translations (
    id              BIGSERIAL   PRIMARY KEY,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    page_idx        INTEGER     NOT NULL,
    word            TEXT        NOT NULL,
    target_lang     TEXT        NOT NULL,
    context_hash    CHAR(64)    NOT NULL,
    context_preview TEXT        NOT NULL,
    translation     TEXT        NOT NULL,
    model           TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_word_translations_lookup
    ON word_translations(book_id, target_lang, context_hash);

-- Доп. индекс для "все переводы этой книги" / админских запросов.
CREATE INDEX idx_word_translations_book
    ON word_translations(book_id, created_at DESC);
