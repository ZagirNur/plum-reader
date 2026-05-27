package com.plum.reader.books

import com.plum.reader.jobs.JobKind
import com.plum.reader.jobs.JobRepository
import com.plum.reader.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest

@Service
class BookService(
    private val books: BookRepository,
    private val userBooks: UserBookRepository,
    private val storage: StorageService,
    private val jobs: JobRepository,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Each invocation runs in its own transaction; on exception → rollback only that tx. */
    private val tx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionTemplate.PROPAGATION_REQUIRES_NEW
    }

    fun upload(ownerId: Long, originalFilename: String?, bytes: ByteArray): UploadResponse {
        if (bytes.isEmpty()) throw UnsupportedFileException("file is empty")
        if (originalFilename != null && !originalFilename.endsWith(".epub", ignoreCase = true)) {
            throw UnsupportedFileException("filename must end with .epub")
        }
        // Validation & metadata extraction happen outside any DB transaction.
        val metadata = EpubInspector.inspect(bytes)
        val sha = sha256Hex(bytes)

        // Fast-path dedup: existing book → just link.
        val existing = books.findBySha256(sha)
        if (existing != null) {
            tx.executeWithoutResult { userBooks.link(ownerId, existing.id) }
            log.info("book.upload.dedup userId={} bookId={} sha={}", ownerId, existing.id, sha)
            return UploadResponse(BookResponse.of(existing), deduplicated = true, jobId = null)
        }

        // Storage is idempotent on sha — safe to write before the DB insert.
        // Orphan blobs after terminal failures are tolerated for MVP.
        val storageKey = storage.put(bytes, sha)

        // Atomic insert + link + enqueue. On UNIQUE(sha256) race we exit the
        // tx via DuplicateKeyException → it rolls back cleanly → then we run
        // the dedup link in a fresh tx (above).
        return try {
            tx.execute { createBookTx(ownerId, metadata, storageKey, bytes.size.toLong(), sha) }!!
        } catch (_: DuplicateKeyException) {
            val winner = books.findBySha256(sha)
                ?: error("duplicate key on sha256=$sha but row not found")
            tx.executeWithoutResult { userBooks.link(ownerId, winner.id) }
            log.info("book.upload.dedup_race userId={} bookId={} sha={}", ownerId, winner.id, sha)
            UploadResponse(BookResponse.of(winner), deduplicated = true, jobId = null)
        }
    }

    private fun createBookTx(
        ownerId: Long,
        metadata: EpubMetadata,
        storageKey: String,
        sizeBytes: Long,
        sha: String,
    ): UploadResponse {
        val book = books.insert(
            title = metadata.title,
            author = metadata.author,
            language = metadata.language,
            ownerId = ownerId,
            storageKey = storageKey,
            sizeBytes = sizeBytes,
            sha256 = sha,
        )
        userBooks.link(ownerId, book.id)
        val jobId = jobs.enqueue(JobKind.SPLIT_EPUB, book.id)
        log.info(
            "book.upload.success userId={} bookId={} sha={} size={} jobId={}",
            ownerId, book.id, sha, sizeBytes, jobId,
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
