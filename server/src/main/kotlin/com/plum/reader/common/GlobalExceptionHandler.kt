package com.plum.reader.common

import com.plum.reader.auth.EmailAlreadyTakenException
import com.plum.reader.auth.InvalidCredentialsException
import com.plum.reader.books.BookNotFoundException
import com.plum.reader.books.FileTooLargeException
import com.plum.reader.books.InvalidEpubException
import com.plum.reader.books.InvalidProgressException
import com.plum.reader.books.PageNotFoundException
import com.plum.reader.books.UnsupportedFileException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

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

    @ExceptionHandler(UnsupportedFileException::class)
    fun handleUnsupportedFile(ex: UnsupportedFileException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse(error = "unsupported_file", message = ex.message ?: "unsupported file"),
        )

    @ExceptionHandler(InvalidEpubException::class)
    fun handleInvalidEpub(ex: InvalidEpubException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse(error = "invalid_epub", message = ex.message ?: "not a valid EPUB"),
        )

    @ExceptionHandler(FileTooLargeException::class)
    fun handleFileTooLarge(ex: FileTooLargeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ErrorResponse(
                error = "file_too_large",
                message = "file exceeds max size",
                details = mapOf("sizeBytes" to ex.sizeBytes.toString(), "maxBytes" to ex.maxBytes.toString()),
            ),
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ErrorResponse(error = "file_too_large", message = "file exceeds configured multipart limit"),
        )

    @ExceptionHandler(BookNotFoundException::class)
    fun handleBookNotFound(ex: BookNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(error = "book_not_found", message = "book ${ex.bookId} not found"),
        )

    @ExceptionHandler(PageNotFoundException::class)
    fun handlePageNotFound(ex: PageNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error = "page_not_found",
                message = "page ${ex.pageIdx} in book ${ex.bookId} not found",
            ),
        )

    @ExceptionHandler(InvalidProgressException::class)
    fun handleInvalidProgress(ex: InvalidProgressException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                error = "invalid_progress",
                message = "lastPageIdx ${ex.idx} out of bounds for book ${ex.bookId}",
                details = mapOf("pageCount" to ex.pageCount.toString()),
            ),
        )
}
