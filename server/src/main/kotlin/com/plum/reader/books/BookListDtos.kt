package com.plum.reader.books

import jakarta.validation.constraints.Min
import java.time.Instant

/** Summary entry for `GET /api/v1/books` — one per library entry. */
data class BookSummary(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val status: BookStatusDto,
    val pageCount: Int?,
    val lastPageIdx: Int?,
    val addedAt: Instant,
) {
    companion object {
        fun of(entry: UserLibraryEntry): BookSummary = BookSummary(
            id = entry.book.id,
            title = entry.book.title,
            author = entry.book.author,
            language = entry.book.language,
            status = BookStatusDto.of(entry.book.status),
            pageCount = entry.book.pageCount,
            lastPageIdx = entry.lastPageIdx,
            addedAt = entry.addedAt,
        )
    }
}

/** Full book detail for `GET /api/v1/books/{id}`. Adds storage size + sha + error. */
data class BookDetailResponse(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val status: BookStatusDto,
    val pageCount: Int?,
    val lastPageIdx: Int?,
    val sizeBytes: Long,
    val sha256: String,
    val error: String?,
    val addedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(entry: UserLibraryEntry): BookDetailResponse = BookDetailResponse(
            id = entry.book.id,
            title = entry.book.title,
            author = entry.book.author,
            language = entry.book.language,
            status = BookStatusDto.of(entry.book.status),
            pageCount = entry.book.pageCount,
            lastPageIdx = entry.lastPageIdx,
            sizeBytes = entry.book.sizeBytes,
            sha256 = entry.book.sha256,
            error = entry.book.error,
            addedAt = entry.addedAt,
            createdAt = entry.book.createdAt,
            updatedAt = entry.book.updatedAt,
        )
    }
}

/** One page payload. */
data class PageContentResponse(
    val bookId: Long,
    val idx: Int,
    val total: Int,
    val xhtml: String,
    val textLen: Int,
    val prevIdx: Int?,
    val nextIdx: Int?,
)

/** `PATCH /api/v1/books/{id}/progress` body. */
data class ProgressUpdateRequest(
    @field:Min(0) val lastPageIdx: Int,
)

data class ProgressResponse(
    val bookId: Long,
    val lastPageIdx: Int,
)

/** `GET /api/v1/books` envelope (so the contract leaves room for paging later). */
data class BookListResponse(val items: List<BookSummary>)
