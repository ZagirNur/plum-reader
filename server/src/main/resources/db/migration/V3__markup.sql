-- V3: vocabulary markup output.
--
-- Plum Reader is also a language-learning aid: every book is post-processed
-- into a frequency-ranked vocabulary that powers "show me the new words in
-- this chapter" UX. This migration adds:
--   * markup_status on books, separate from the parsing `status` —
--     a book can be `ready` to read while its markup is still processing,
--   * book_words: (book_id, word, frequency) keyed on (book_id, word).
--
-- markup_status states match the parser's state machine for consistency:
--   'pending'    — markup job not yet started
--   'processing' — worker mid-flight
--   'ready'      — book_words populated
--   'failed'     — markup terminal-failed (book_words may be empty/partial)

ALTER TABLE books
    ADD COLUMN markup_status TEXT NOT NULL DEFAULT 'pending'
        CHECK (markup_status IN ('pending','processing','ready','failed'));

CREATE TABLE book_words (
    id          BIGSERIAL   PRIMARY KEY,
    book_id     BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    word        TEXT        NOT NULL,
    frequency   INTEGER     NOT NULL CHECK (frequency > 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, word)
);

-- Reader queries by "top N words in book": index on (book_id, frequency DESC).
CREATE INDEX idx_book_words_top
    ON book_words(book_id, frequency DESC);
