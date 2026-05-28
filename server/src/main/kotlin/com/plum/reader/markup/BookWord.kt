package com.plum.reader.markup

import java.time.Instant

data class BookWord(
    val id: Long,
    val bookId: Long,
    val word: String,
    val frequency: Int,
    val createdAt: Instant,
)

enum class MarkupStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun of(value: String): MarkupStatus = entries.firstOrNull { it.value == value }
            ?: error("unknown markup status: $value")
    }
}
