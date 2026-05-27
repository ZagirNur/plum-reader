package com.plum.reader.books

import com.fasterxml.jackson.annotation.JsonValue
import com.plum.reader.markup.MarkupStatus
import jakarta.validation.constraints.Min
import java.time.Instant

/** Markup status as published to clients. */
enum class MarkupStatusDto(@get:JsonValue val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun of(value: String): MarkupStatusDto = entries.firstOrNull { it.value == value }
            ?: error("unknown markup status: $value")
    }
}

/**
 * Summary entry for `GET /api/v1/books` — one per library entry.
 *
 * Lifecycle of timestamps:
 * - [addedAt] — when the user added the book to their library (from `user_books`).
 * - [updatedAt] — last change of the Book row itself (parsing/status update).
 *
 * Nullable contract:
 * - `pageCount`: non-null only when [status] == READY.
 * - `lastPageIdx`: non-null only after the user calls `PATCH /progress`.
 * - `title`/`author`/`language`: parsed from EPUB metadata; missing-DC means null.
 */
data class BookSummary(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val status: BookStatusDto,
    val markupStatus: MarkupStatusDto,
    val pageCount: Int?,
    val lastPageIdx: Int?,
    val addedAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(entry: UserLibraryEntry): BookSummary = BookSummary(
            id = entry.book.id,
            title = entry.book.title,
            author = entry.book.author,
            language = entry.book.language,
            status = BookStatusDto.of(entry.book.status),
            markupStatus = MarkupStatusDto.of(entry.book.markupStatus),
            pageCount = entry.book.pageCount,
            lastPageIdx = entry.lastPageIdx,
            addedAt = entry.addedAt,
            updatedAt = entry.book.updatedAt,
        )
    }
}

/**
 * Full book detail for `GET /api/v1/books/{id}`.
 *
 * Nullable contract by status:
 * - UPLOADED → `pageCount=null`, `error=null` (just queued).
 * - PROCESSING → `pageCount=null`, `error=null` (worker mid-flight).
 * - READY → `pageCount` non-null, `error=null`.
 * - FAILED → `pageCount` possibly null, `error` non-null with reason.
 */
data class BookDetailResponse(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val status: BookStatusDto,
    val markupStatus: MarkupStatusDto,
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
            markupStatus = MarkupStatusDto.of(entry.book.markupStatus),
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

/**
 * Single rendered page.
 *
 * `prevIdx == null` ⇔ first page; `nextIdx == null` ⇔ last page. Clients can
 * determine end-of-book purely from `nextIdx == null` (or `idx + 1 == total`).
 */
data class PageContentResponse(
    val bookId: Long,
    val idx: Int,
    val total: Int,
    val xhtml: String,
    val textLen: Int,
    val prevIdx: Int?,
    val nextIdx: Int?,
)

/** `GET /api/v1/books/{id}/pages` — list of page indexes in spine order. */
data class PageIndexListResponse(
    val bookId: Long,
    val total: Int,
    val indexes: List<Int>,
)

/**
 * `PATCH /api/v1/books/{id}/progress` body. `lastPageIdx` is the index of the
 * page the client considers "current" — on the next open of this book on any
 * device, the reader resumes there. Bounded by `[0, book.pageCount)`.
 */
data class ProgressUpdateRequest(
    @field:Min(0) val lastPageIdx: Int,
)

/**
 * `PATCH .../progress` response. [updatedAt] is server-side wall-clock at the
 * moment of the update — clients use it for multi-device last-write-wins.
 */
data class ProgressResponse(
    val bookId: Long,
    val lastPageIdx: Int,
    val updatedAt: Instant,
)

/**
 * `GET /api/v1/books` envelope. `items` is capped at server-side `LIMIT 200`
 * until cursor pagination ships — `more` is a soft hint to the client.
 */
data class BookListResponse(
    val items: List<BookSummary>,
    val more: Boolean,
)
