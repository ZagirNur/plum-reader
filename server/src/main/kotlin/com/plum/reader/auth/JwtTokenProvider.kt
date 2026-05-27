package com.plum.reader.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(props: JwtProperties) {
    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray(StandardCharsets.UTF_8))
    private val ttl: Duration = Duration.ofMinutes(props.expirationMinutes)
    private val clockSkew: Long = props.clockSkewSeconds

    fun issue(userId: Long, email: String, name: String?): String {
        val now = Instant.now()
        val builder = Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
        if (name != null) builder.claim("name", name)
        return builder.compact()
    }

    fun parse(token: String): JwtPrincipal {
        val claims = Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(clockSkew)
            .build()
            .parseSignedClaims(token)
            .payload
        return JwtPrincipal(
            userId = claims.subject.toLong(),
            email = claims.get("email", String::class.java),
            name = claims.get("name", String::class.java),
        )
    }
}

data class JwtPrincipal(val userId: Long, val email: String, val name: String?)
