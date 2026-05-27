package com.plum.reader.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${plum.jwt.secret}") secret: String,
    @Value("\${plum.jwt.expiration-minutes}") private val expirationMinutes: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    private val ttl: Duration = Duration.ofMinutes(expirationMinutes)

    fun issue(userId: Long, email: String): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun parse(token: String): JwtPrincipal {
        val claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
        return JwtPrincipal(
            userId = claims.subject.toLong(),
            email = claims["email"] as String,
        )
    }
}

data class JwtPrincipal(val userId: Long, val email: String)
