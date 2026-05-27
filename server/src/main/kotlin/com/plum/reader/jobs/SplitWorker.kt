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
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.UUID

/**
 * Polls `processing_jobs` for `split_epub` work, reads the EPUB blob via
 * [StorageService], runs [EpubSplitter], and inserts the resulting pages.
 *
 * Concurrency model:
 * - Multiple workers (multiple JVMs / threads) race via
 *   `FOR UPDATE SKIP LOCKED` in [JobRepository.claimNext].
 * - Each claim sets `locked_until = now() + lockTimeout`. If this worker
 *   hangs, [JobRepository.releaseExpiredLocks] sweeps the row back to
 *   `pending` and a different worker re-claims it.
 * - Every state-mutating call ([JobRepository.markDone] / [markFailed] /
 *   [markDead]) is guarded by `WHERE state='running' AND locked_by=?`, so a
 *   late-finishing hijacked worker cannot overwrite the new owner's state.
 *   When the guard fails, [LockLostException] rolls back the page-write tx.
 * - At-most-one active job per (kind, book_id) is enforced by the V2 partial
 *   unique index — two workers cannot independently split the same book.
 *
 * Backoff:
 * - Transient failures advance `scheduled_at = now() + base*2^attempts`
 *   capped at 1h, so the queue doesn't hot-loop on a flapping dependency.
 * - After `maxAttempts` the job is `markDead` and the book is `failed`.
 */
@Component
class SplitWorker(
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

    fun poll() {
        if (!props.enabled) return
        val job = jobs.claimNext(workerId, props.lockTimeout, JobKind.SPLIT_EPUB, props.maxAttempts) ?: return
        log.info("split.claim jobId={} bookId={} attempts={}", job.id, job.bookId, job.attempts)
        runSplit(job)
    }

    fun sweepExpiredLocks() {
        if (!props.enabled) return
        val count = jobs.releaseExpiredLocks()
        if (count > 0) log.warn("split.requeue_stale count={}", count)
    }

    private fun runSplit(job: Job) {
        val book = books.findById(job.bookId)
        if (book == null) {
            jobs.markDead(job.id, workerId, "book ${job.bookId} not found")
            return
        }
        try {
            tx.executeWithoutResult { books.updateStatus(book.id, BookStatus.PROCESSING) }
            val bytes = storage.openRead(book.storageKey).use { it.readBytes() }
            val epubPages = EpubSplitter.split(bytes)
            // Page-write + book-finalize + markDone all in one transaction so
            // a lost lock rolls back the page rewrite.
            tx.executeWithoutResult {
                pages.deleteByBook(book.id)
                pages.insertAll(book.id, epubPages.map { Triple(it.idx, it.xhtml, it.textLen) })
                books.updateStatus(book.id, BookStatus.READY, pageCount = epubPages.size)
                if (!jobs.markDone(job.id, workerId)) {
                    throw LockLostException(job.id, workerId)
                }
            }
            log.info("split.done jobId={} bookId={} pages={}", job.id, book.id, epubPages.size)
        } catch (ex: LockLostException) {
            // Page-write tx rolled back automatically. Other worker owns it now.
            log.warn("split.lock_lost jobId={} workerId={}", job.id, workerId)
        } catch (ex: InvalidEpubException) {
            terminalFail(job, book.id, "invalid_epub: ${ex.message}")
        } catch (ex: Exception) {
            log.warn("split.failed jobId={} bookId={} attempt={} error={}", job.id, book.id, job.attempts, ex.message)
            if (job.attempts >= props.maxAttempts) {
                terminalFail(job, book.id, ex.message ?: ex.javaClass.simpleName)
            } else {
                val backoff = exponentialBackoff(job.attempts)
                jobs.markFailed(job.id, workerId, ex.message ?: ex.javaClass.simpleName, backoff)
            }
        }
    }

    private fun terminalFail(job: Job, bookId: Long, reason: String) {
        tx.executeWithoutResult {
            books.updateStatus(bookId, BookStatus.FAILED, error = reason)
            jobs.markDead(job.id, workerId, reason)
        }
        log.error("split.terminal_fail jobId={} bookId={} reason={}", job.id, bookId, reason)
    }

    private fun exponentialBackoff(attempts: Int): Duration {
        val base = props.retryBackoffBase
        val maxBackoff = props.retryBackoffMax
        val factor = 1L shl minOf(attempts, 16)  // cap shift so we don't overflow
        val computed = base.multipliedBy(factor)
        return if (computed > maxBackoff) maxBackoff else computed
    }

    private fun hostname(): String = try {
        ManagementFactory.getRuntimeMXBean().name.substringAfter('@', "unknown")
    } catch (_: Throwable) {
        "unknown"
    }
}

/**
 * Wires [SplitWorker.poll] and [SplitWorker.sweepExpiredLocks] into the
 * scheduler with intervals read from [WorkerProperties]. Using
 * [SchedulingConfigurer] (instead of `@Scheduled(fixedDelay = …)`) is what
 * lets the intervals be configurable via `plum.worker.*` / env vars.
 *
 * Implements [SchedulingConfigurer] only — `@EnableScheduling` lives on the
 * application root.
 */
@Configuration
@EnableConfigurationProperties(WorkerProperties::class)
class WorkerConfig(
    private val worker: SplitWorker,
    private val props: WorkerProperties,
) : SchedulingConfigurer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        if (!props.enabled) {
            log.info("worker.disabled — no scheduled tasks registered")
            return
        }
        registrar.addFixedDelayTask({ worker.poll() }, props.pollInterval)
        registrar.addFixedDelayTask({ worker.sweepExpiredLocks() }, props.staleLockSweepInterval)
        log.info(
            "worker.scheduled poll={} sweep={} lockTimeout={} maxAttempts={}",
            props.pollInterval, props.staleLockSweepInterval, props.lockTimeout, props.maxAttempts,
        )
    }
}
