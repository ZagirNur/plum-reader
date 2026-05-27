package com.plum.reader.books

class UnsupportedFileException(message: String) : RuntimeException(message)
class FileTooLargeException(val sizeBytes: Long, val maxBytes: Long) :
    RuntimeException("file too large: $sizeBytes > $maxBytes")
class BookNotFoundException(val bookId: Long) :
    RuntimeException("book not found: $bookId")
