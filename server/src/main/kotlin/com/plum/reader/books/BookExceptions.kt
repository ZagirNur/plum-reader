package com.plum.reader.books

class UnsupportedFileException(message: String) : RuntimeException(message)
class FileTooLargeException(val sizeBytes: Long, val maxBytes: Long) :
    RuntimeException("file too large: $sizeBytes > $maxBytes")
class BookNotFoundException(val bookId: Long) :
    RuntimeException("book not found: $bookId")
class PageNotFoundException(val bookId: Long, val pageIdx: Int) :
    RuntimeException("book $bookId page $pageIdx not found")
class InvalidProgressException(val bookId: Long, val idx: Int, val pageCount: Int) :
    RuntimeException("lastPageIdx $idx out of bounds [0, $pageCount) for book $bookId")

class BookNotReadyException(val bookId: Long, val status: String) :
    RuntimeException("book $bookId is in status $status; pages and progress are only available when status=ready")
