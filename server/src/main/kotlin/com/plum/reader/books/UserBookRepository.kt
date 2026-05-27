package com.plum.reader.books

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** A book in a user's library, joined with their reading progress. */
data class UserLibraryEntry(
    val book: Book,
    val addedAt: Instant,
    val lastPageIdx: Int?,
)

@Repository
class UserBookRepository(private val jdbc: JdbcTemplate) {

    /** Idempotent — duplicates collapse via ON CONFLICT DO NOTHING on (user_id, book_id). */
    fun link(userId: Long, bookId: Long) {
        jdbc.update(
            """
            INSERT INTO user_books(user_id, book_id) VALUES (?, ?)
            ON CONFLICT (user_id, book_id) DO NOTHING
            """.trimIndent(),
            userId, bookId,
        )
    }

    /**
     * Up to [limit] newest books in the user's library. The hard server-side
     * cap defends the JSON response size until cursor pagination is wired in.
     */
    fun listForUser(userId: Long, limit: Int = 200): List<UserLibraryEntry> = jdbc.query(
        """
        SELECT b.id, b.title, b.author, b.language, b.owner_id, b.storage_key,
               b.size_bytes, b.sha256, b.status, b.page_count, b.error, b.markup_status,
               b.created_at, b.updated_at,
               ub.added_at, ub.last_page_idx
        FROM user_books ub
        JOIN books b ON b.id = ub.book_id
        WHERE ub.user_id = ?
        ORDER BY ub.added_at DESC, b.id DESC
        LIMIT ?
        """.trimIndent(),
        LIBRARY_ROW_MAPPER,
        userId, limit,
    )

    /** Fetch one library entry. `null` if the book is not in the user's library. */
    fun getForUser(userId: Long, bookId: Long): UserLibraryEntry? = try {
        jdbc.queryForObject(
            """
            SELECT b.id, b.title, b.author, b.language, b.owner_id, b.storage_key,
                   b.size_bytes, b.sha256, b.status, b.page_count, b.error, b.markup_status,
                   b.created_at, b.updated_at,
                   ub.added_at, ub.last_page_idx
            FROM user_books ub
            JOIN books b ON b.id = ub.book_id
            WHERE ub.user_id = ? AND ub.book_id = ?
            """.trimIndent(),
            LIBRARY_ROW_MAPPER,
            userId, bookId,
        )
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    /**
     * Update reading progress. Returns true if the (user, book) row existed
     * and was updated; false if the user does not own the book.
     */
    fun updateProgress(userId: Long, bookId: Long, lastPageIdx: Int): Boolean {
        val rows = jdbc.update(
            "UPDATE user_books SET last_page_idx = ? WHERE user_id = ? AND book_id = ?",
            lastPageIdx, userId, bookId,
        )
        return rows == 1
    }

    private companion object {
        private val LIBRARY_ROW_MAPPER = RowMapper<UserLibraryEntry> { rs, _ -> mapRow(rs) }

        private fun mapRow(rs: ResultSet): UserLibraryEntry {
            val book = Book(
                id = rs.getLong("id"),
                title = rs.getString("title"),
                author = rs.getString("author"),
                language = rs.getString("language"),
                ownerId = rs.getObject("owner_id") as Long?,
                storageKey = rs.getString("storage_key"),
                sizeBytes = rs.getLong("size_bytes"),
                sha256 = rs.getString("sha256"),
                status = rs.getString("status"),
                pageCount = rs.getObject("page_count") as Int?,
                error = rs.getString("error"),
                markupStatus = rs.getString("markup_status"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
            return UserLibraryEntry(
                book = book,
                addedAt = rs.getTimestamp("added_at").toInstant(),
                lastPageIdx = rs.getObject("last_page_idx") as Int?,
            )
        }
    }
}
