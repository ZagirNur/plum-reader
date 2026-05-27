package com.plum.reader.auth

import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val users: UserRepository,
    private val passwords: PasswordEncoder,
    private val tokens: JwtTokenProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Pre-computed bcrypt of a fixed random string. Used to keep login()
    // wall-clock time symmetric between "email unknown" and "password wrong" —
    // otherwise the response delay leaks whether an email is registered.
    private val dummyHash: String = passwords.encode("dummy-password-for-timing-equalization")

    fun register(req: RegisterRequest): AuthResponse {
        val email = req.email.trim().lowercase()
        val hash = passwords.encode(req.password)
        val user = try {
            users.create(email = email, passwordHash = hash, name = req.name)
        } catch (_: DuplicateKeyException) {
            log.info("auth.register.duplicate email={}", email)
            throw EmailAlreadyTakenException(email)
        }
        log.info("auth.register.success userId={} email={}", user.id, user.email)
        return AuthResponse(
            token = tokens.issue(user.id, user.email, user.name),
            user = MeResponse(user.id, user.email, user.name),
        )
    }

    fun login(req: LoginRequest): AuthResponse {
        val email = req.email.trim().lowercase()
        val user = users.findByEmail(email)
        if (user == null) {
            // run bcrypt anyway to equalize timing with the wrong-password branch.
            passwords.matches(req.password, dummyHash)
            log.warn("auth.login.failed reason=unknown_email email={}", email)
            throw InvalidCredentialsException()
        }
        if (!passwords.matches(req.password, user.passwordHash)) {
            log.warn("auth.login.failed reason=bad_password userId={}", user.id)
            throw InvalidCredentialsException()
        }
        log.info("auth.login.success userId={}", user.id)
        return AuthResponse(
            token = tokens.issue(user.id, user.email, user.name),
            user = MeResponse(user.id, user.email, user.name),
        )
    }
}
