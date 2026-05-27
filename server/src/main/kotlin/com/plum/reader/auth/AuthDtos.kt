package com.plum.reader.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank @field:Size(min = 8, max = 128) val password: String,
    @field:Size(max = 200) val name: String? = null,
)

data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class AuthResponse(val token: String, val user: MeResponse)

data class MeResponse(val id: Long, val email: String, val name: String?)
