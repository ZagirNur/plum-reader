package com.plum.reader.translate

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class TranslationRepository(private val jdbc: JdbcTemplate) {

    private val rowMapper = RowMapper<WordTranslation> { rs, _ -> mapRow(rs) }

    fun findCached(bookId: Long, targetLang: String, contextHash: String): WordTranslation? = try {
        jdbc.queryForObject(
            """
            SELECT id, book_id, page_idx, word, target_lang, context_hash,
                   context_preview, translation, model, created_at
            FROM word_translations
            WHERE book_id = ? AND target_lang = ? AND context_hash = ?
            """.trimIndent(),
            rowMapper, bookId, targetLang, contextHash,
        )
    } catch (_: EmptyResultDataAccessException) {
        null
    }

    fun save(
        bookId: Long,
        pageIdx: Int,
        word: String,
        targetLang: String,
        contextHash: String,
        contextPreview: String,
        translation: String,
        model: String,
    ): WordTranslation {
        val id = jdbc.queryForObject(
            """
            INSERT INTO word_translations(book_id, page_idx, word, target_lang,
                                          context_hash, context_preview, translation, model)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (book_id, target_lang, context_hash) DO UPDATE
                SET translation = EXCLUDED.translation,
                    model       = EXCLUDED.model
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            bookId, pageIdx, word, targetLang, contextHash, contextPreview, translation, model,
        ) ?: error("INSERT ... RETURNING id returned null")
        return jdbc.queryForObject(
            """
            SELECT id, book_id, page_idx, word, target_lang, context_hash,
                   context_preview, translation, model, created_at
            FROM word_translations WHERE id = ?
            """.trimIndent(),
            rowMapper, id,
        )!!
    }

    private fun mapRow(rs: ResultSet): WordTranslation = WordTranslation(
        id = rs.getLong("id"),
        bookId = rs.getLong("book_id"),
        pageIdx = rs.getInt("page_idx"),
        word = rs.getString("word"),
        targetLang = rs.getString("target_lang"),
        contextHash = rs.getString("context_hash"),
        contextPreview = rs.getString("context_preview"),
        translation = rs.getString("translation"),
        model = rs.getString("model"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}
