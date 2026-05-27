package com.plum.reader.auth

import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val users: UserRepository,
    private val passwords: PasswordEncoder,
    private val tokens: JwtTokenProvider,
) {

    fun register(req: RegisterRequest): AuthResponse {
        val email = req.email.lowercase().trim()
        val hash = passwords.encode(req.password)
        val user = try {
            users.create(email = email, passwordHash = hash, name = req.name)
        } catch (_: DuplicateKeyException) {
            throw EmailAlreadyTakenException(email)
        }
        return AuthResponse(
            token = tokens.issue(user.id, user.email),
            user = MeResponse(user.id, user.email, user.name),
        )
    }

    fun login(req: LoginRequest): AuthResponse {
        val email = req.email.lowercase().trim()
        val user = users.findByEmail(email) ?: throw InvalidCredentialsException()
        if (!passwords.matches(req.password, user.passwordHash)) throw InvalidCredentialsException()
        return AuthResponse(
            token = tokens.issue(user.id, user.email),
            user = MeResponse(user.id, user.email, user.name),
        )
    }
}

class EmailAlreadyTakenException(val email: String) : RuntimeException("email already taken: $email")
class InvalidCredentialsException : RuntimeException("invalid credentials")
