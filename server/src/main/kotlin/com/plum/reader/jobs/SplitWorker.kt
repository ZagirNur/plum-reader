package com.plum.reader.jobs

import com.plum.reader.books.BookRepository
import com.plum.reader.books.BookStatus
import com.plum.reader.books.EpubSplitter
import com.plum.reader.books.InvalidEpubException
import com.plum.reader.pages.PageRepository
import com.plum.reader.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * Polls `processing_jobs` for `split_epub` work, reads the EPUB blob via
 * [StorageService], runs [EpubSplitter], and inserts the resulting pages.
 *
 * Concurrency model:
 * - Multiple workers (multiple JVMs / threads) race via
 *   `FOR UPDATE SKIP LOCKED` in [JobRepository.claimNext].
 * - Each claim sets `locked_until = now() + lockTimeout`. If this worker
 *   crashes, [releaseExpiredLocks] sweeps the row back to `pending`.
 * - On terminal failure (`attempts >= maxAttempts`) the job stays `failed`
 *   and the book is marked `failed` with the error message.
 *
 * Idempotency:
 * - On retry [PageRepository.deleteByBook] clears any partial state from a
 *   previous attempt; pages have `UNIQUE(book_id, idx)`.
 */
@Component
open class SplitWorker(
    private val jobs: JobRepository,
    private val books: BookRepository,
    private val pages: PageRepository,
    private val storage: StorageService,
    private val props: WorkerProperties,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val workerId: String = "${hostname()}-${UUID.randomUUID().toString().take(8)}"

@Scheduled(fixedDelay = 2000)
    open fun poll() {
        val job = jobs.claimNext(workerId, props.lockTimeout, JobKind.SPLIT_EPUB) ?: return
        log.info("split.claim jobId={} bookId={} attempts={}", job.id, job.bookId, job.attempts)
        runSplit(job)
    }

    @Scheduled(fixedDelay = 60_000)
    open fun releaseExpiredLocks() {
        val count = jobs.releaseExpiredLocks()
        if (count > 0) log.warn("split.requeue_stale count={}", count)
    }

    private fun runSplit(job: Job) {
        val book = books.findById(job.bookId)
        if (book == null) {
            log.warn("split.book_missing jobId={} bookId={}", job.id, job.bookId)
            jobs.markFailed(job.id, "book ${job.bookId} not found")
            return
        }
        try {
            tx.executeWithoutResult {
                books.updateStatus(book.id, BookStatus.PROCESSING)
            }
            val bytes = storage.openRead(book.storageKey).use { it.readBytes() }
            val epubPages = EpubSplitter.split(bytes)
            tx.executeWithoutResult {
                pages.deleteByBook(book.id)
                pages.insertAll(book.id, epubPages.map { Triple(it.idx, it.xhtml, it.textLen) })
                books.updateStatus(book.id, BookStatus.READY, pageCount = epubPages.size)
            }
            jobs.markDone(job.id)
            log.info("split.done jobId={} bookId={} pages={}", job.id, book.id, epubPages.size)
        } catch (ex: InvalidEpubException) {
            // Bad EPUB — non-retriable.
            terminalFail(job, book.id, "invalid_epub: ${ex.message}")
        } catch (ex: Exception) {
            log.warn("split.failed jobId={} bookId={} attempt={} error={}", job.id, book.id, job.attempts, ex.message)
            if (job.attempts >= props.maxAttempts) {
                terminalFail(job, book.id, ex.message ?: ex.javaClass.simpleName)
            } else {
                // Transient — leave book status as `processing` so retry can pick it up.
                jobs.markFailed(job.id, ex.message ?: ex.javaClass.simpleName)
            }
        }
    }

    private fun terminalFail(job: Job, bookId: Long, reason: String) {
        tx.executeWithoutResult { books.updateStatus(bookId, BookStatus.FAILED, error = reason) }
        jobs.markFailed(job.id, reason)
        log.error("split.terminal_fail jobId={} bookId={} reason={}", job.id, bookId, reason)
    }

    private fun hostname(): String = try {
        ManagementFactory.getRuntimeMXBean().name.substringAfter('@', "unknown")
    } catch (_: Throwable) {
        "unknown"
    }
}

@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig
