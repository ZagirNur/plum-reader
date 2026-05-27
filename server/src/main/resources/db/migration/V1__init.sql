-- Plum Reader: initial schema (V1).
-- Owned by the Spring Boot module via Flyway. The Python markup worker
-- reads the same schema but never applies migrations.

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    name            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN users.password_hash IS
    'Spring Security DelegatingPasswordEncoder format (e.g. {bcrypt}$2a$...). Never plaintext or unsalted digest.';

CREATE TABLE books (
    id              BIGSERIAL   PRIMARY KEY,
    title           TEXT,
    author          TEXT,
    language        TEXT,
    owner_id        BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    storage_key     TEXT        NOT NULL,
    size_bytes      BIGINT      NOT NULL CHECK (size_bytes > 0),
    sha256          CHAR(64)    NOT NULL UNIQUE,
    status          TEXT        NOT NULL DEFAULT 'uploaded'
                    CHECK (status IN ('uploaded','processing','ready','failed')),
    page_count      INTEGER     CHECK (page_count IS NULL OR page_count >= 0),
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN books.storage_key IS
    'Opaque key into the storage layer (FS path or S3 object key). The storage module owns the bytes; DB only holds metadata.';
COMMENT ON COLUMN books.sha256 IS
    'Lowercase hex SHA-256 of the original EPUB. UNIQUE enables upload dedup across users.';

CREATE INDEX idx_books_owner ON books(owner_id);
CREATE INDEX idx_books_status ON books(status);

CREATE TABLE user_books (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_page_idx   INTEGER     CHECK (last_page_idx IS NULL OR last_page_idx >= 0),
    UNIQUE (user_id, book_id)
);

CREATE TABLE pages (
    id              BIGSERIAL   PRIMARY KEY,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    idx             INTEGER     NOT NULL CHECK (idx >= 0),
    xhtml           TEXT        NOT NULL,
    text_len        INTEGER     NOT NULL CHECK (text_len >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, idx)
);

CREATE TABLE assets (
    id              BIGSERIAL   PRIMARY KEY,
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    path            TEXT        NOT NULL,
    mime            TEXT        NOT NULL,
    storage_key     TEXT        NOT NULL,
    size_bytes      BIGINT      NOT NULL CHECK (size_bytes > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, path)
);

CREATE TABLE processing_jobs (
    id              BIGSERIAL   PRIMARY KEY,
    kind            TEXT        NOT NULL
                    CHECK (kind IN ('split_epub','markup')),
    book_id         BIGINT      NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    state           TEXT        NOT NULL DEFAULT 'pending'
                    CHECK (state IN ('pending','running','done','failed','cancelled')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error           TEXT,
    locked_by       TEXT,
    locked_until    TIMESTAMPTZ,
    scheduled_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Worker queue: pending/failed jobs ordered by scheduled_at, claimed via
-- FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_processing_jobs_queue
    ON processing_jobs(scheduled_at)
    WHERE state IN ('pending', 'failed');

-- Failover: scan running jobs whose lock has expired so another worker can
-- requeue them.
CREATE INDEX idx_processing_jobs_locked_until
    ON processing_jobs(locked_until)
    WHERE state = 'running';

-- Shared updated_at maintenance trigger.
CREATE FUNCTION set_updated_at() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_books_updated_at
    BEFORE UPDATE ON books
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_processing_jobs_updated_at
    BEFORE UPDATE ON processing_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
