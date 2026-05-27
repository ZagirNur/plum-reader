package com.plum.reader.pages

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class PageRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper<Page> { rs, _ -> mapRow(rs) }

    /** Bulk insert pages for a book. UNIQUE(book_id, idx) protects us from re-runs. */
    fun insertAll(bookId: Long, pages: List<Triple<Int, String, Int>>) {
        if (pages.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO pages(book_id, idx, xhtml, text_len) VALUES (?, ?, ?, ?)",
            pages.map { (idx, xhtml, textLen) -> arrayOf<Any>(bookId, idx, xhtml, textLen) },
        )
    }

    /** Remove any existing pages for a book — used on retry of a failed split. */
    fun deleteByBook(bookId: Long): Int =
        jdbc.update("DELETE FROM pages WHERE book_id = ?", bookId)

    fun countByBook(bookId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM pages WHERE book_id = ?", Int::class.java, bookId) ?: 0

    fun findByBookAndIdx(bookId: Long, idx: Int): Page? =
        jdbc.query(
            "SELECT id, book_id, idx, xhtml, text_len, created_at FROM pages WHERE book_id = ? AND idx = ?",
            rowMapper, bookId, idx,
        ).firstOrNull()

    fun listIdsByBook(bookId: Long): List<Int> =
        jdbc.queryForList(
            "SELECT idx FROM pages WHERE book_id = ? ORDER BY idx",
            Int::class.java, bookId,
        )

    private fun mapRow(rs: ResultSet): Page = Page(
        id = rs.getLong("id"),
        bookId = rs.getLong("book_id"),
        idx = rs.getInt("idx"),
        xhtml = rs.getString("xhtml"),
        textLen = rs.getInt("text_len"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
