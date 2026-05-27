package com.plum.reader.pages

import java.time.Instant

data class Page(
    val id: Long,
    val bookId: Long,
    val idx: Int,
    val xhtml: String,
    val textLen: Int,
    val createdAt: Instant,
)
