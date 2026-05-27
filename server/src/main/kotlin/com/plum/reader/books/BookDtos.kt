package com.plum.reader.books

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

/**
 * Book status as published to clients. Jackson serializes by [value] — the
 * frontend only ever sees the canonical strings below.
 */
enum class BookStatusDto(@get:JsonValue val value: String) {
    UPLOADED("uploaded"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun of(value: String): BookStatusDto = entries.firstOrNull { it.value == value }
            ?: error("unknown book status: $value")
    }
}

data class BookResponse(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val status: BookStatusDto,
    val sizeBytes: Long,
    val sha256: String,
    val pageCount: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(book: Book): BookResponse = BookResponse(
            id = book.id,
            title = book.title,
            author = book.author,
            language = book.language,
            status = BookStatusDto.of(book.status),
            sizeBytes = book.sizeBytes,
            sha256 = book.sha256,
            pageCount = book.pageCount,
            createdAt = book.createdAt,
            updatedAt = book.updatedAt,
        )
    }
}

data class UploadResponse(
    val book: BookResponse,
    /** true if this exact EPUB (matched by sha256) was already in the system. */
    val deduplicated: Boolean,
    /** Id of the queued `split_epub` job, if one was created. */
    val jobId: Long?,
)
