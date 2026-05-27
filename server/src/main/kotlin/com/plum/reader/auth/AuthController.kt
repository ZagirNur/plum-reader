package com.plum.reader.auth

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class AuthController(private val authService: AuthService) {

    @PostMapping("/auth/register")
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req))

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody req: LoginRequest): AuthResponse =
        authService.login(req)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: JwtPrincipal): MeResponse =
        MeResponse(principal.userId, principal.email, principal.name)
}
