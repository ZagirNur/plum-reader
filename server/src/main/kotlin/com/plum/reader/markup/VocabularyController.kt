package com.plum.reader.markup

import com.plum.reader.auth.JwtPrincipal
import com.plum.reader.books.BookNotFoundException
import com.plum.reader.books.BookNotReadyException
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
            // Re-use book_not_ready code; details.status distinguishes which pipeline is pending.
            throw BookNotReadyException(id, "markup:${entry.book.markupStatus}")
        }
        val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
        val rows = words.topByBook(entry.book.id, cappedLimit, offset.coerceAtLeast(0))
        val total = words.countByBook(entry.book.id)
        return VocabularyResponse(
            bookId = entry.book.id,
            total = total,
            offset = offset.coerceAtLeast(0),
            limit = cappedLimit,
            items = rows.map { WordEntry(word = it.word, frequency = it.frequency) },
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
            throw BookNotReadyException(id, "markup:${entry.book.markupStatus}")
        }
        val row = words.findOne(entry.book.id, word)
            ?: throw WordNotFoundException(id, word.lowercase())
        return WordEntry(word = row.word, frequency = row.frequency)
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

data class WordEntry(val word: String, val frequency: Int)

class WordNotFoundException(val bookId: Long, val word: String) :
    RuntimeException("word '$word' not found in book $bookId")
