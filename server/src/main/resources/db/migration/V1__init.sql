-- Plum Reader: initial schema.
-- Owned by Spring Boot / Flyway. Python markup worker reads the same schema
-- but never applies migrations.

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    name            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE books (
    id              BIGSERIAL   PRIMARY KEY,
    title           TEXT,
    author          TEXT,
    language        TEXT,
    owner_id        BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    raw_epub        BYTEA       NOT NULL,
    raw_sha256      TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'uploaded',
    page_count      INTEGER,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_books_owner ON books(owner_id);
CREATE INDEX idx_books_status ON books(status);

CREATE TABLE user_books (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_page_idx   INTEGER,
    UNIQUE (user_id, book_id)
);

CREATE TABLE pages (
    id              BIGSERIAL   PRIMARY KEY,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    idx             INTEGER     NOT NULL,
    xhtml           TEXT        NOT NULL,
    text_len        INTEGER     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, idx)
);

CREATE TABLE assets (
    id              BIGSERIAL   PRIMARY KEY,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    path            TEXT        NOT NULL,
    mime            TEXT        NOT NULL,
    bytes           BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, path)
);

CREATE TABLE processing_jobs (
    id              BIGSERIAL   PRIMARY KEY,
    kind            TEXT        NOT NULL,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    state           TEXT        NOT NULL DEFAULT 'pending',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    error           TEXT,
    locked_by       TEXT,
    locked_until    TIMESTAMPTZ,
    scheduled_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Очередь: воркеры тянут pending/failed задачи в порядке scheduled_at
-- через FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_processing_jobs_queue
    ON processing_jobs(scheduled_at)
    WHERE state IN ('pending', 'failed');
