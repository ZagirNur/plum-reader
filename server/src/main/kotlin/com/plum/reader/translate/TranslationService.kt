package com.plum.reader.translate

import com.plum.reader.books.BookNotFoundException
import com.plum.reader.books.BookNotReadyException
import com.plum.reader.books.BookStatus
import com.plum.reader.books.UserBookRepository
import com.plum.reader.pages.PageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class TranslationService(
    private val userBooks: UserBookRepository,
    private val pages: PageRepository,
    private val repo: TranslationRepository,
    private val gemini: GeminiClient,
    private val props: TranslationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Перевод слова/фразы с учётом окружения. Кэш по
     * `(book_id, target_lang, sha256(book||page||lower(word)||lower(context)||lang))`.
     */
    fun translate(
        userId: Long,
        bookId: Long,
        pageIdx: Int,
        start: Int,
        end: Int,
        targetLangRaw: String?,
    ): TranslationResponse {
        val entry = userBooks.getForUser(userId, bookId) ?: throw BookNotFoundException(bookId)
        if (entry.book.status != BookStatus.READY.value) {
            throw BookNotReadyException(bookId, entry.book.status)
        }
        val page = pages.findByBookAndIdx(entry.book.id, pageIdx)
            ?: throw TranslationException(
                "page_not_found",
                "page $pageIdx not found in book $bookId",
            )
        if (start < 0 || end <= start || end > page.xhtml.length) {
            throw TranslationException(
                "invalid_range",
                "start/end out of range [0, ${page.xhtml.length})",
            )
        }
        val targetLang = normalizeLang(targetLangRaw)

        // Извлекаем сам токен и его контекст из visible-text. Если span попал
        // на HTML-тег, мы расширяем до ближайшей буквы (heuristic).
        val word = extractWord(page.xhtml, start, end)
        val context = extractContext(page.xhtml, start, end, props.contextRadius)

        val hash = sha256(buildString {
            append(entry.book.id); append('|')
            append(pageIdx); append('|')
            append(word.lowercase()); append('|')
            append(context.text.lowercase()); append('|')
            append(targetLang)
        })

        repo.findCached(entry.book.id, targetLang, hash)?.let { cached ->
            log.debug("translate.cache_hit bookId={} hash={}", entry.book.id, hash.take(8))
            return TranslationResponse.cached(word, context.text, cached)
        }

        log.info("translate.miss bookId={} page={} word='{}' lang={}", entry.book.id, pageIdx, word, targetLang)
        val translation = gemini.translate(
            word = word,
            context = context.marked,
            sourceLang = entry.book.language,
            targetLang = targetLang,
        )
        val saved = repo.save(
            bookId = entry.book.id,
            pageIdx = pageIdx,
            word = word,
            targetLang = targetLang,
            contextHash = hash,
            contextPreview = context.text,
            translation = translation,
            model = props.gemini.model,
        )
        return TranslationResponse.fresh(word, context.text, saved)
    }

    private fun normalizeLang(raw: String?): String {
        val v = (raw ?: props.defaultTargetLanguage).trim().lowercase()
        if (props.allowedTargetLanguages.isNotEmpty() && v !in props.allowedTargetLanguages) {
            throw TranslationException(
                "unsupported_target_language",
                "targetLang '$v' is not in ${props.allowedTargetLanguages}",
            )
        }
        return v
    }

    /** [start, end) — границы внутри xhtml; вернём чистое слово без тегов. */
    private fun extractWord(xhtml: String, start: Int, end: Int): String {
        val raw = xhtml.substring(start, end)
        return TAG_STRIPPER.replace(raw, "").trim().trim('"', '\'', '‘', '’', '«', '»', '.', ',', ':', ';', '!', '?')
    }

    private data class WordContext(val text: String, val marked: String)

    /** Берём ±radius символов вокруг слова из xhtml; чистим теги, оборачиваем target в `<T>...</T>` для LLM. */
    private fun extractContext(xhtml: String, start: Int, end: Int, radius: Int): WordContext {
        val left = (start - radius).coerceAtLeast(0)
        val right = (end + radius).coerceAtMost(xhtml.length)
        val rawBefore = xhtml.substring(left, start)
        val rawWord = xhtml.substring(start, end)
        val rawAfter = xhtml.substring(end, right)

        val before = TAG_STRIPPER.replace(rawBefore, " ").replace(WS, " ").trim()
        val target = TAG_STRIPPER.replace(rawWord, "").trim()
        val after = TAG_STRIPPER.replace(rawAfter, " ").replace(WS, " ").trim()

        val plain = listOf(before, target, after).joinToString(" ").replace(WS, " ").trim()
        val marked = "$before <T>$target</T> $after".replace(WS, " ").trim()
        return WordContext(text = plain, marked = marked)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(md.size * 2)
        for (b in md) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        val TAG_STRIPPER = Regex("<[^>]+>")
        val WS = Regex("\\s+")
        val HEX = "0123456789abcdef".toCharArray()
    }
}

class TranslationException(val code: String, message: String) : RuntimeException(message)
