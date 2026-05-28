package com.plum.reader.markup

import com.plum.reader.auth.JwtPrincipal
import com.plum.reader.books.BookNotFoundException
import com.plum.reader.books.UserBookRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Reader-facing vocabulary API.
 *
 * Same per-user scoping as [com.plum.reader.books.BookReadController]:
 * the user must own the book (via `user_books`) and the book's
 * `markupStatus` must be `READY`.
 */
@RestController
@RequestMapping("/api/v1/books")
class VocabularyController(
    private val userBooks: UserBookRepository,
    private val words: BookWordRepository,
) {

    /**
     * Top-N words by frequency in a single book.
     * Default `limit=100`, max `500`. Pagination via `?offset=`.
     */
    @GetMapping("/{id}/vocabulary")
    fun vocabulary(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Int,
    ): VocabularyResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        if (entry.book.markupStatus != MarkupStatus.READY.value) {
            throw MarkupNotReadyException(id, entry.book.markupStatus)
        }
        val offsetSan = offset.coerceAtLeast(0)
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        val rows = words.topByBook(entry.book.id, cappedLimit, offsetSan)
        val total = words.countByBook(entry.book.id)
        return VocabularyResponse(
            bookId = entry.book.id,
            total = total,
            offset = offsetSan,
            limit = cappedLimit,
            items = rows.mapIndexed { i, row ->
                WordEntry(word = row.word, frequency = row.frequency, rank = offsetSan + i + 1)
            },
        )
    }

    /** Look up a single word's frequency in a book. */
    @GetMapping("/{id}/words/{word}")
    fun word(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @PathVariable word: String,
    ): WordEntry {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        if (entry.book.markupStatus != MarkupStatus.READY.value) {
            throw MarkupNotReadyException(id, entry.book.markupStatus)
        }
        val row = words.findOne(entry.book.id, word)
            ?: throw WordNotFoundException(id, word.lowercase())
        // Single-word lookups don't expose a rank — clients use /vocabulary for ranking.
        return WordEntry(word = row.word, frequency = row.frequency, rank = null)
    }

    companion object {
        private const val MAX_LIMIT = 500
    }
}

data class VocabularyResponse(
    val bookId: Long,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<WordEntry>,
)

data class WordEntry(
    val word: String,
    val frequency: Int,
    /** 1-based position in the top-frequency listing. null for single-word lookups. */
    val rank: Int?,
)

class WordNotFoundException(val bookId: Long, val word: String) :
    RuntimeException("word '$word' not found in book $bookId")

class MarkupNotReadyException(val bookId: Long, val markupStatus: String) :
    RuntimeException("book $bookId markup is $markupStatus; wait for markup_status=ready")
