package com.plum.reader.markup

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class BookWordRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper<BookWord> { rs, _ -> mapRow(rs) }

    /**
     * Bulk-insert the entire frequency map. Caller holds [DELETE FROM book_words
     * WHERE book_id=?] + insertAll in a single transaction for idempotent retry.
     */
    fun insertAll(bookId: Long, frequencies: Map<String, Int>) {
        if (frequencies.isEmpty()) return
        val args = frequencies.map { (w, f) -> arrayOf<Any>(bookId, w, f) }
        jdbc.batchUpdate(
            "INSERT INTO book_words(book_id, word, frequency) VALUES (?, ?, ?)",
            args,
        )
    }

    fun deleteByBook(bookId: Long): Int =
        jdbc.update("DELETE FROM book_words WHERE book_id = ?", bookId)

    fun countByBook(bookId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM book_words WHERE book_id = ?", Int::class.java, bookId) ?: 0

    /** Top words by frequency, descending; ties broken by `word ASC` for stable order. */
    fun topByBook(bookId: Long, limit: Int, offset: Int = 0): List<BookWord> =
        jdbc.query(
            """
            SELECT id, book_id, word, frequency, created_at
            FROM book_words
            WHERE book_id = ?
            ORDER BY frequency DESC, word ASC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            rowMapper, bookId, limit, offset,
        )

    fun findOne(bookId: Long, word: String): BookWord? = jdbc.query(
        """
        SELECT id, book_id, word, frequency, created_at
        FROM book_words WHERE book_id = ? AND word = ?
        """.trimIndent(),
        rowMapper, bookId, word.lowercase(),
    ).firstOrNull()

    private fun mapRow(rs: ResultSet): BookWord = BookWord(
        id = rs.getLong("id"),
        bookId = rs.getLong("book_id"),
        word = rs.getString("word"),
        frequency = rs.getInt("frequency"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
