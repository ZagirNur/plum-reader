package com.plum.reader.books

import java.time.Instant

data class Book(
    val id: Long,
    val title: String?,
    val author: String?,
    val language: String?,
    val ownerId: Long?,
    val storageKey: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: String,
    val pageCount: Int?,
    val error: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class BookStatus(val value: String) {
    UPLOADED("uploaded"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun of(value: String): BookStatus = entries.firstOrNull { it.value == value }
            ?: error("unknown book status: $value")
    }
}
