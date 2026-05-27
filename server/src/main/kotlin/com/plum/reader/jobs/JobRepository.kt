package com.plum.reader.jobs

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration

enum class JobKind(val value: String) {
    SPLIT_EPUB("split_epub"),
    MARKUP("markup"),
}

@Repository
class JobRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper<Job> { rs, _ -> mapRow(rs) }

    /** Enqueue a new pending job. Returns the inserted id. */
    fun enqueue(kind: JobKind, bookId: Long): Long {
        return jdbc.queryForObject(
            """
            INSERT INTO processing_jobs(kind, book_id, state)
            VALUES (?, ?, 'pending')
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            kind.value, bookId,
        ) ?: error("INSERT ... RETURNING id returned null")
    }

    /**
     * Atomically pick the next pending/failed job (oldest scheduled_at) that
     * still has attempts left. Marks it `running`, bumps `attempts`, sets
     * `locked_by/locked_until`. Returns the row or `null` if the queue is empty.
     *
     * Uses `FOR UPDATE SKIP LOCKED` so multiple workers race without blocking.
     * Backed by the `(kind, scheduled_at)` partial index in V2.
     *
     * The `attempts < maxAttempts` predicate is the queue-side enforcement —
     * without it, terminal-failed rows (attempts == maxAttempts) would be
     * re-picked every poll forever.
     */
    fun claimNext(workerId: String, lockFor: Duration, kind: JobKind, maxAttempts: Int): Job? {
        val sql = """
            WITH next AS (
              SELECT id FROM processing_jobs
              WHERE state IN ('pending','failed')
                AND scheduled_at <= now()
                AND kind = ?
                AND attempts < ?
              ORDER BY scheduled_at
              FOR UPDATE SKIP LOCKED
              LIMIT 1
            )
            UPDATE processing_jobs
            SET state = 'running',
                attempts = attempts + 1,
                locked_by = ?,
                locked_until = now() + ?::interval,
                error = NULL
            WHERE id = (SELECT id FROM next)
            RETURNING id, kind, book_id, state, attempts, error, locked_by, locked_until,
                      scheduled_at, created_at, updated_at
        """.trimIndent()
        return jdbc.query(sql, rowMapper, kind.value, maxAttempts, workerId, lockFor.toString())
            .firstOrNull()
    }

    /**
     * Move job to `done` — but only if THIS worker still owns the lock.
     * Returns true on success, false if a stale-lock sweep already requeued
     * the row (in which case the caller must roll back any work it did).
     */
    fun markDone(id: Long, workerId: String): Boolean {
        val rows = jdbc.update(
            """
            UPDATE processing_jobs
            SET state = 'done', locked_by = NULL, locked_until = NULL, error = NULL
            WHERE id = ? AND state = 'running' AND locked_by = ?
            """.trimIndent(),
            id, workerId,
        )
        return rows == 1
    }

    /**
     * Move job back to `failed` with exponential backoff and bump retry
     * scheduling. Guard'ed by ownership.
     */
    fun markFailed(id: Long, workerId: String, error: String, retryAfter: Duration): Boolean {
        val rows = jdbc.update(
            """
            UPDATE processing_jobs
            SET state = 'failed',
                locked_by = NULL,
                locked_until = NULL,
                error = ?,
                scheduled_at = now() + ?::interval
            WHERE id = ? AND state = 'running' AND locked_by = ?
            """.trimIndent(),
            error.take(2000), retryAfter.toString(), id, workerId,
        )
        return rows == 1
    }

    /**
     * Terminal failure: state stays `failed` but with `attempts` already at
     * `maxAttempts`, [claimNext] won't re-pick. Used when the worker has
     * exhausted retries or detected a permanently-bad input.
     */
    fun markDead(id: Long, workerId: String, error: String): Boolean {
        val rows = jdbc.update(
            """
            UPDATE processing_jobs
            SET state = 'failed', locked_by = NULL, locked_until = NULL, error = ?
            WHERE id = ? AND state = 'running' AND locked_by = ?
            """.trimIndent(),
            error.take(2000), id, workerId,
        )
        return rows == 1
    }

    /**
     * Sweep `running` jobs whose lock has expired and put them back into
     * `pending` so another worker can pick them up. Returns count requeued.
     */
    fun releaseExpiredLocks(): Int =
        jdbc.update(
            """
            UPDATE processing_jobs
            SET state = 'pending', locked_by = NULL, locked_until = NULL
            WHERE state = 'running' AND locked_until < now()
            """.trimIndent(),
        )

    fun findById(id: Long): Job? =
        jdbc.query(
            """
            SELECT id, kind, book_id, state, attempts, error, locked_by, locked_until,
                   scheduled_at, created_at, updated_at
            FROM processing_jobs WHERE id = ?
            """.trimIndent(),
            rowMapper, id,
        ).firstOrNull()

    private fun mapRow(rs: ResultSet): Job = Job(
        id = rs.getLong("id"),
        kind = rs.getString("kind"),
        bookId = rs.getLong("book_id"),
        state = rs.getString("state"),
        attempts = rs.getInt("attempts"),
        error = rs.getString("error"),
        lockedBy = rs.getString("locked_by"),
        lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
        scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )
}

class LockLostException(val jobId: Long, val workerId: String) :
    RuntimeException("worker $workerId lost lock on job $jobId before it could finalize")
