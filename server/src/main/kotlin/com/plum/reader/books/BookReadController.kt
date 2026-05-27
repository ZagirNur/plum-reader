package com.plum.reader.books

import com.plum.reader.auth.JwtPrincipal
import com.plum.reader.pages.PageRepository
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Read-side API for clients. All endpoints are scoped to the authenticated
 * user via `user_books` — a book that isn't in the user's library answers 404
 * (not 403, to avoid leaking existence).
 *
 * Pages and progress are only available once `status == READY`. Earlier
 * states answer 409 `book_not_ready` so clients don't persist meaningless
 * indexes against an empty/partial page set.
 */
@RestController
@RequestMapping("/api/v1/books")
class BookReadController(
    private val userBooks: UserBookRepository,
    private val pages: PageRepository,
) {

    /** List books in the current user's library, newest-added first. */
    @GetMapping
    fun list(@AuthenticationPrincipal principal: JwtPrincipal): BookListResponse {
        val limit = 200
        val items = userBooks.listForUser(principal.userId, limit).map(BookSummary::of)
        return BookListResponse(items = items, more = items.size == limit)
    }

    /** Full detail for one book. 404 if not in the user's library. */
    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
    ): BookDetailResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        return BookDetailResponse.of(entry)
    }

    /** All page indexes in a book, in spine order. Useful for a TOC scrubber. */
    @GetMapping("/{id}/pages")
    fun pageIndexes(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
    ): PageIndexListResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        if (entry.book.status != BookStatus.READY.value) {
            throw BookNotReadyException(id, entry.book.status)
        }
        val indexes = pages.listIdsByBook(entry.book.id)
        return PageIndexListResponse(bookId = entry.book.id, total = indexes.size, indexes = indexes)
    }

    /** Single page payload. 409 if book is not yet `ready`. */
    @GetMapping("/{id}/pages/{idx}")
    fun page(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @PathVariable idx: Int,
    ): PageContentResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        if (entry.book.status != BookStatus.READY.value) {
            throw BookNotReadyException(id, entry.book.status)
        }
        val page = pages.findByBookAndIdx(entry.book.id, idx) ?: throw PageNotFoundException(id, idx)
        // pageCount is non-null once status == READY (worker writes it).
        val total = entry.book.pageCount ?: pages.countByBook(entry.book.id)
        return PageContentResponse(
            bookId = entry.book.id,
            idx = page.idx,
            total = total,
            xhtml = page.xhtml,
            textLen = page.textLen,
            prevIdx = (page.idx - 1).takeIf { it >= 0 },
            nextIdx = (page.idx + 1).takeIf { it < total },
        )
    }

    /**
     * Update reading progress. 409 if book is not yet `ready`, 400 if
     * `lastPageIdx` is out of bounds.
     */
    @PatchMapping("/{id}/progress")
    fun updateProgress(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @Valid @RequestBody body: ProgressUpdateRequest,
    ): ProgressResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        if (entry.book.status != BookStatus.READY.value) {
            throw BookNotReadyException(id, entry.book.status)
        }
        // pageCount is non-null once status == READY.
        val total = entry.book.pageCount!!
        if (body.lastPageIdx >= total) {
            throw InvalidProgressException(id, body.lastPageIdx, total)
        }
        // Guard against TOCTOU: book was unlinked between getForUser and the
        // UPDATE. updateProgress returns false → answer 404, don't lie 200.
        val ok = userBooks.updateProgress(principal.userId, id, body.lastPageIdx)
        if (!ok) throw BookNotFoundException(id)
        return ProgressResponse(
            bookId = id,
            lastPageIdx = body.lastPageIdx,
            updatedAt = Instant.now(),
        )
    }
}
