package com.plum.reader.common

import com.plum.reader.auth.EmailAlreadyTakenException
import com.plum.reader.auth.InvalidCredentialsException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val error: String, val message: String, val details: Map<String, String>? = null)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyTakenException::class)
    fun handleEmailTaken(ex: EmailAlreadyTakenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(error = "email_already_taken", message = "email already registered"),
        )

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleBadCreds(ex: InvalidCredentialsException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(error = "invalid_credentials", message = "invalid email or password"),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = ex.bindingResult.fieldErrors.associate { fe ->
            fe.field to (fe.defaultMessage ?: "invalid")
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(error = "validation_failed", message = "request body invalid", details = fields),
        )
    }
}
