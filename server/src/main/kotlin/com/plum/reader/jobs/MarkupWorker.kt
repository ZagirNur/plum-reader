package com.plum.reader.jobs

import com.plum.reader.books.BookRepository
import com.plum.reader.markup.BookWordRepository
import com.plum.reader.markup.MarkupStatus
import com.plum.reader.markup.WordTokenizer
import com.plum.reader.pages.PageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * Polls `processing_jobs` for `markup` work. For each ready book, reads all
 * `pages.xhtml`, tokenizes via [WordTokenizer] (HTML strip + locale-stable
 * lowercase + letters/apostrophes only), aggregates word→frequency, and
 * writes [com.plum.reader.markup.BookWordRepository.insertAll] in one tx.
 *
 * Same concurrency / retry / ownership-guard model as [SplitWorker]: claim
 * via FOR UPDATE SKIP LOCKED, exponential backoff, markDone/Failed/Dead are
 * guarded by `WHERE state='running' AND locked_by=?`.
 */
@Component
class MarkupWorker(
    private val jobs: JobRepository,
    private val books: BookRepository,
    private val pages: PageRepository,
    private val words: BookWordRepository,
    private val props: WorkerProperties,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val workerId: String = "${hostname()}-${UUID.randomUUID().toString().take(8)}"

    fun poll() {
        if (!props.enabled) return
        val job = jobs.claimNext(workerId, props.lockTimeout, JobKind.MARKUP, props.maxAttempts) ?: return
        log.info("markup.claim jobId={} bookId={} attempts={}", job.id, job.bookId, job.attempts)
        runMarkup(job)
    }

    private fun runMarkup(job: Job) {
        val book = books.findById(job.bookId)
        if (book == null) {
            jobs.markDead(job.id, workerId, "book ${job.bookId} not found")
            return
        }
        try {
            tx.executeWithoutResult { books.updateMarkupStatus(book.id, MarkupStatus.PROCESSING) }

            val indexes = pages.listIdsByBook(book.id)
            if (indexes.isEmpty()) {
                terminalFail(job, book.id, "no pages to markup (book not split yet)")
                return
            }
            val freq = HashMap<String, Int>()
            for (idx in indexes) {
                val p = pages.findByBookAndIdx(book.id, idx) ?: continue
                for ((w, c) in WordTokenizer.frequencyOf(p.xhtml)) {
                    freq.merge(w, c, Int::plus)
                }
            }
            log.info("markup.tokenized jobId={} bookId={} pages={} words={}", job.id, book.id, indexes.size, freq.size)

            tx.executeWithoutResult {
                words.deleteByBook(book.id)
                words.insertAll(book.id, freq)
                books.updateMarkupStatus(book.id, MarkupStatus.READY)
                if (!jobs.markDone(job.id, workerId)) {
                    throw LockLostException(job.id, workerId)
                }
            }
            log.info("markup.done jobId={} bookId={} words={}", job.id, book.id, freq.size)
        } catch (ex: LockLostException) {
            log.warn("markup.lock_lost jobId={} workerId={}", job.id, workerId)
        } catch (ex: Exception) {
            log.warn("markup.failed jobId={} bookId={} attempt={} error={}", job.id, book.id, job.attempts, ex.message)
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
            books.updateMarkupStatus(bookId, MarkupStatus.FAILED)
            jobs.markDead(job.id, workerId, reason)
        }
        log.error("markup.terminal_fail jobId={} bookId={} reason={}", job.id, bookId, reason)
    }

    private fun exponentialBackoff(attempts: Int): java.time.Duration {
        val base = props.retryBackoffBase
        val maxBackoff = props.retryBackoffMax
        val factor = 1L shl minOf(attempts, 16)
        val computed = base.multipliedBy(factor)
        return if (computed > maxBackoff) maxBackoff else computed
    }

    private fun hostname(): String = try {
        ManagementFactory.getRuntimeMXBean().name.substringAfter('@', "unknown")
    } catch (_: Throwable) {
        "unknown"
    }
}
