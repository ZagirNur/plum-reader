package com.plum.reader.translate

import java.time.Instant

data class WordTranslation(
    val id: Long,
    val bookId: Long,
    val pageIdx: Int,
    val word: String,
    val targetLang: String,
    val contextHash: String,
    val contextPreview: String,
    val translation: String,
    val model: String,
    val createdAt: Instant,
)
