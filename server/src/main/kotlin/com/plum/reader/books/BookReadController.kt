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

/**
 * Read-side API for clients. All endpoints are scoped to the authenticated
 * user — a book that isn't in the user's library answers 404.
 */
@RestController
@RequestMapping("/api/v1/books")
class BookReadController(
    private val userBooks: UserBookRepository,
    private val pages: PageRepository,
) {

    /** List books in the current user's library, newest-added first. */
    @GetMapping
    fun list(@AuthenticationPrincipal principal: JwtPrincipal): BookListResponse =
        BookListResponse(items = userBooks.listForUser(principal.userId).map(BookSummary::of))

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
    ): Map<String, Any> {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        val indexes = pages.listIdsByBook(entry.book.id)
        return mapOf("bookId" to entry.book.id, "total" to indexes.size, "indexes" to indexes)
    }

    /** Single page payload. */
    @GetMapping("/{id}/pages/{idx}")
    fun page(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @PathVariable idx: Int,
    ): PageContentResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        val page = pages.findByBookAndIdx(entry.book.id, idx) ?: throw PageNotFoundException(id, idx)
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

    /** Update reading progress. `last_page_idx` is bounded by `book.page_count`. */
    @PatchMapping("/{id}/progress")
    fun updateProgress(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @Valid @RequestBody body: ProgressUpdateRequest,
    ): ProgressResponse {
        val entry = userBooks.getForUser(principal.userId, id) ?: throw BookNotFoundException(id)
        val total = entry.book.pageCount
        if (total != null && body.lastPageIdx >= total) {
            throw InvalidProgressException(id, body.lastPageIdx, total)
        }
        userBooks.updateProgress(principal.userId, id, body.lastPageIdx)
        return ProgressResponse(bookId = id, lastPageIdx = body.lastPageIdx)
    }
}
