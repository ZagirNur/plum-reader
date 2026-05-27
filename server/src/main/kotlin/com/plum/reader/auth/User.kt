package com.plum.reader.auth

import java.time.Instant

data class User(
    val id: Long,
    val email: String,
    val passwordHash: String,
    val name: String?,
    val createdAt: Instant,
)
