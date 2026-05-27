package com.plum.reader.books

import com.plum.reader.jobs.JobKind
import com.plum.reader.jobs.JobRepository
import com.plum.reader.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
class BookService(
    private val books: BookRepository,
    private val userBooks: UserBookRepository,
    private val storage: StorageService,
    private val jobs: JobRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun upload(ownerId: Long, originalFilename: String?, bytes: ByteArray): UploadResponse {
        if (bytes.isEmpty()) throw UnsupportedFileException("file is empty")
        if (originalFilename != null && !originalFilename.endsWith(".epub", ignoreCase = true)) {
            throw UnsupportedFileException("filename must end with .epub")
        }
        val metadata = EpubInspector.inspect(bytes)  // throws InvalidEpubException on bad EPUB
        val sha = sha256Hex(bytes)

        val existing = books.findBySha256(sha)
        if (existing != null) {
            userBooks.link(ownerId, existing.id)
            log.info("book.upload.dedup userId={} bookId={} sha={}", ownerId, existing.id, sha)
            return UploadResponse(BookResponse.of(existing), deduplicated = true, jobId = null)
        }

        val storageKey = storage.put(bytes, sha)
        val book = try {
            books.insert(
                title = metadata.title,
                author = metadata.author,
                language = metadata.language,
                ownerId = ownerId,
                storageKey = storageKey,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha,
            )
        } catch (_: DuplicateKeyException) {
            // race: another upload of the same sha won. Fall back to the existing row.
            val winner = books.findBySha256(sha)
                ?: error("duplicate key on sha256=$sha but row not found")
            userBooks.link(ownerId, winner.id)
            return UploadResponse(BookResponse.of(winner), deduplicated = true, jobId = null)
        }
        userBooks.link(ownerId, book.id)
        val jobId = jobs.enqueue(JobKind.SPLIT_EPUB, book.id)
        log.info(
            "book.upload.success userId={} bookId={} sha={} size={} jobId={}",
            ownerId, book.id, sha, bytes.size, jobId,
        )
        return UploadResponse(BookResponse.of(book), deduplicated = false, jobId = jobId)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
