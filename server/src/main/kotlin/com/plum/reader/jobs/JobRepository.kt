package com.plum.reader.jobs

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

enum class JobKind(val value: String) {
    SPLIT_EPUB("split_epub"),
    MARKUP("markup"),
}

@Repository
class JobRepository(private val jdbc: JdbcTemplate) {

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
}
