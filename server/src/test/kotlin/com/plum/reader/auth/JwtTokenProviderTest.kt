package com.plum.reader.auth

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.security.SignatureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtTokenProviderTest {

    private val secret = "0".repeat(48)
    private val provider = JwtTokenProvider(secret = secret, expirationMinutes = 60)

    @Test
    fun `issue then parse returns the same principal`() {
        val token = provider.issue(userId = 42, email = "alice@example.com")
        val principal = provider.parse(token)
        assertEquals(42, principal.userId)
        assertEquals("alice@example.com", principal.email)
    }

    @Test
    fun `parsing with wrong secret fails`() {
        val token = provider.issue(userId = 1, email = "x@x")
        val other = JwtTokenProvider(secret = "1".repeat(48), expirationMinutes = 60)
        assertFailsWith<SignatureException> { other.parse(token) }
    }

    @Test
    fun `expired token fails`() {
        val expired = JwtTokenProvider(secret = secret, expirationMinutes = -1)
        val token = expired.issue(userId = 1, email = "x@x")
        assertFailsWith<ExpiredJwtException> { provider.parse(token) }
    }
}
