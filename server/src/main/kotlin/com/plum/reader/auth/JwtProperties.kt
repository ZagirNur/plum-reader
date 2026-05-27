package com.plum.reader.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "plum.jwt")
data class JwtProperties(
    val secret: String,
    val expirationMinutes: Long,
    val clockSkewSeconds: Long = 30,
) {
    companion object {
        const val DEV_DEFAULT_SECRET =
            "dev-only-do-not-use-in-production-replace-me-with-real-secret"
    }
}
