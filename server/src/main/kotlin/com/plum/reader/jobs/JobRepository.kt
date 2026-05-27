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
     * Atomically pick the next pending job (oldest scheduled_at), mark it
     * `running`, bump `attempts`, set `locked_by/locked_until`. Returns the
     * row or `null` if the queue is empty.
     *
     * Uses `FOR UPDATE SKIP LOCKED` so multiple workers race without blocking.
     * Backed by the partial index `idx_processing_jobs_queue` from V1.
     *
     * Executed via PreparedStatement.execute() because Spring's
     * `jdbc.query(... RowMapper, args)` is built for SELECT-style queries and
     * doesn't reliably surface the ResultSet from an `UPDATE ... RETURNING`.
     */
    fun claimNext(workerId: String, lockFor: Duration, kind: JobKind? = null): Job? {
        val kindFilter = if (kind != null) "AND kind = ?" else ""
        val sql = """
            WITH next AS (
              SELECT id FROM processing_jobs
              WHERE state IN ('pending','failed')
                AND scheduled_at <= now()
                $kindFilter
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
        return jdbc.execute<Job?>(sql) { ps ->
            var i = 1
            if (kind != null) ps.setString(i++, kind.value)
            ps.setString(i++, workerId)
            ps.setString(i++, lockFor.toString())  // 'PT5M' parsed as PG interval
            val rs = ps.executeQuery()
            rs.use { if (it.next()) mapRow(it) else null }
        }
    }

    fun markDone(id: Long) {
        jdbc.update(
            "UPDATE processing_jobs SET state = 'done', locked_by = NULL, locked_until = NULL, error = NULL WHERE id = ?",
            id,
        )
    }

    /** Mark as failed (terminal). Worker decides whether to terminal-fail based on attempts. */
    fun markFailed(id: Long, error: String) {
        jdbc.update(
            "UPDATE processing_jobs SET state = 'failed', locked_by = NULL, locked_until = NULL, error = ? WHERE id = ?",
            error.take(2000), id,
        )
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
