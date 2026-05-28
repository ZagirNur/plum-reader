package com.plum.reader.translate

import com.plum.reader.auth.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Контекстный перевод одного слова или фразы внутри страницы.
 *
 * Контракт:
 *   POST /api/v1/books/{id}/translate
 *   body: { pageIdx, start, end, targetLang? }
 *   200 → { word, translation, context, cached, model, lang, createdAt }
 *
 * Семантика `start/end` — символьные смещения в `page.xhtml` (тот самый
 * текст, который фронт получил из GET /pages/{idx}). Сервер сам зачистит
 * HTML-теги при выделении слова и контекста.
 */
@RestController
@RequestMapping("/api/v1/books")
class TranslationController(private val service: TranslationService) {

    @PostMapping("/{id}/translate")
    fun translate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: Long,
        @Valid @RequestBody req: TranslateRequest,
    ): TranslationResponse = service.translate(
        userId = principal.userId,
        bookId = id,
        pageIdx = req.pageIdx,
        start = req.start,
        end = req.end,
        targetLangRaw = req.targetLang,
    )
}

data class TranslateRequest(
    @field:Min(0) val pageIdx: Int,
    @field:Min(0) val start: Int,
    @field:Min(0) val end: Int,
    val targetLang: String? = null,
)

/** Ответ /translate. */
data class TranslationResponse(
    val word: String,
    val translation: String,
    val context: String,
    val targetLang: String,
    val model: String,
    /** true если ответ выдан из кэша, false если только что вызвали LLM. */
    val cached: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun cached(word: String, context: String, t: WordTranslation) = TranslationResponse(
            word = word,
            translation = t.translation,
            context = context,
            targetLang = t.targetLang,
            model = t.model,
            cached = true,
            createdAt = t.createdAt,
        )

        fun fresh(word: String, context: String, t: WordTranslation) = TranslationResponse(
            word = word,
            translation = t.translation,
            context = context,
            targetLang = t.targetLang,
            model = t.model,
            cached = false,
            createdAt = t.createdAt,
        )
    }
}

@Configuration
@EnableConfigurationProperties(TranslationProperties::class)
class TranslationConfig
