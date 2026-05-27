package com.plum.reader.books

import com.plum.reader.auth.JwtPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI

@RestController
@RequestMapping("/api/v1/books")
class BookController(private val bookService: BookService) {

    /**
     * Upload an EPUB file. The book row is created (or matched by sha256 for
     * dedup) and a `split_epub` job is enqueued for the worker to split the
     * file into `pages`.
     *
     * Response: `201 Created` with `UploadResponse{book, deduplicated, jobId}`
     * and a `Location: /api/v1/books/{id}` header.
     */
    @PostMapping("/upload", consumes = ["multipart/form-data"])
    fun upload(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<UploadResponse> {
        // Empty-file validation lives in BookService (single source of truth).
        val response = bookService.upload(
            ownerId = principal.userId,
            originalFilename = file.originalFilename,
            bytes = file.bytes,
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/books/${response.book.id}"))
            .body(response)
    }
}
