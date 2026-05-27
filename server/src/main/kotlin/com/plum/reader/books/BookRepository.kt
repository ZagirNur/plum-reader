package com.plum.reader.books

import com.plum.reader.markup.MarkupStatus
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class BookRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper<Book> { rs, _ -> mapRow(rs) }

    fun findById(id: Long): Book? = try {
        jdbc.queryForObject("$SELECT WHERE id = ?", rowMapper, id)
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun findBySha256(sha256: String): Book? = try {
        jdbc.queryForObject("$SELECT WHERE sha256 = ?", rowMapper, sha256)
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun insert(
        title: String?,
        author: String?,
        language: String?,
        ownerId: Long,
        storageKey: String,
        sizeBytes: Long,
        sha256: String,
    ): Book {
        val id = jdbc.queryForObject(
            """
            INSERT INTO books(title, author, language, owner_id, storage_key, size_bytes, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            title, author, language, ownerId, storageKey, sizeBytes, sha256,
        ) ?: error("INSERT ... RETURNING id returned null")
        return findById(id) ?: error("book $id disappeared right after insert")
    }

    fun updateStatus(id: Long, status: BookStatus, pageCount: Int? = null, error: String? = null) {
        jdbc.update(
            "UPDATE books SET status = ?, page_count = COALESCE(?, page_count), error = ? WHERE id = ?",
            status.value, pageCount, error, id,
        )
    }

    fun updateMarkupStatus(id: Long, status: MarkupStatus) {
        jdbc.update(
            "UPDATE books SET markup_status = ? WHERE id = ?",
            status.value, id,
        )
    }

    private fun mapRow(rs: ResultSet): Book = Book(
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

    companion object {
        private const val SELECT = """
            SELECT id, title, author, language, owner_id, storage_key, size_bytes, sha256,
                   status, page_count, error, markup_status, created_at, updated_at
            FROM books
        """
    }
}
