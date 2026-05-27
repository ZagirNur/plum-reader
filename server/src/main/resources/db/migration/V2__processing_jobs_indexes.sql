-- V2: tighten processing_jobs queue indexes.
--
-- Two changes:
-- 1. The original `idx_processing_jobs_queue` keyed only on `scheduled_at` —
--    fine while there's a single kind, but with more job kinds the index
--    scan picks rows of unrelated kinds and discards them in the SeqFilter.
--    Rebuild with `(kind, scheduled_at)` so each kind has its own scan.
-- 2. Prevent two concurrent active jobs on the same (kind, book_id) — this
--    is the application-level invariant that lets the worker safely run
--    `DELETE FROM pages WHERE book_id = ?` + `INSERT` without racing
--    another worker that's also splitting the same book.

DROP INDEX IF EXISTS idx_processing_jobs_queue;

CREATE INDEX idx_processing_jobs_queue
    ON processing_jobs(kind, scheduled_at)
    WHERE state IN ('pending', 'failed');

CREATE UNIQUE INDEX idx_processing_jobs_one_active
    ON processing_jobs(kind, book_id)
    WHERE state IN ('pending', 'running');
