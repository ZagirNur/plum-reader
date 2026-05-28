package com.plum.reader.translate

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * Минимальный клиент к Google Gemini `generateContent`.
 *
 * Просим переводить **именно с учётом контекста** — это load-bearing
 * требование Plum Reader (одно слово в разных контекстах = разные
 * переводы). Промт явно говорит модели вернуть ТОЛЬКО перевод, без
 * пояснений, чтобы JSON-парсинг был детерминированным.
 */
@Component
class GeminiClient(
    private val props: TranslationProperties,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(props.gemini.timeoutSeconds))
        .build()

    fun translate(word: String, context: String, sourceLang: String?, targetLang: String): String {
        check(props.gemini.apiKey.isNotBlank()) {
            "plum.translate.gemini.api-key is blank — set PLUM_GEMINI_API_KEY in .env"
        }
        val prompt = buildPrompt(word = word, context = context, sourceLang = sourceLang, targetLang = targetLang)

        val body = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
            generationConfig = GenerationConfig(temperature = 0.2, maxOutputTokens = 64),
        )
        val url = "${props.gemini.baseUrl}/models/${props.gemini.model}:generateContent?key=${props.gemini.apiKey}"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(props.gemini.timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()

        val res = http.send(req, BodyHandlers.ofString())
        if (res.statusCode() / 100 != 2) {
            log.warn("gemini.error status={} body={}", res.statusCode(), res.body().take(500))
            throw TranslationProviderException(
                "Gemini returned HTTP ${res.statusCode()}",
                providerStatus = res.statusCode(),
            )
        }
        val parsed: GeminiResponse = mapper.readValue(res.body(), GeminiResponse::class.java)
        val text = parsed.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?.trim()
            ?: throw TranslationProviderException("empty Gemini response", providerStatus = res.statusCode())

        // Гемини иногда оборачивает в кавычки или добавляет точку — почистим.
        return text.trim().trim('"', '‘', '’', '«', '»').trim('.').trim()
    }

    private fun buildPrompt(word: String, context: String, sourceLang: String?, targetLang: String): String {
        val src = sourceLang ?: "auto-detect"
        // Делаем промт явно short-form, single-token-answer-ish. Ключевое —
        // напоминание, что перевод должен подходить **именно** под этот контекст.
        return buildString {
            appendLine("You are a bilingual reader's assistant.")
            appendLine("Task: translate ONE WORD OR PHRASE in the context of the surrounding sentence.")
            appendLine()
            appendLine("Source language: $src")
            appendLine("Target language: $targetLang")
            appendLine()
            appendLine("Context (the target span is wrapped in <T>...</T>):")
            appendLine(context)
            appendLine()
            appendLine("Word to translate: \"$word\"")
            appendLine()
            appendLine("Rules:")
            appendLine("- Output ONLY the translation, nothing else.")
            appendLine("- No explanations, no quotes, no part-of-speech tags, no transliteration.")
            appendLine("- If the word is part of an idiom or phrasal verb, translate the whole construct, not the literal word.")
            appendLine("- If the word is a proper noun (a name), return it transliterated into the target language.")
            appendLine("- Be concise: usually 1-3 words.")
            appendLine("- The translation MUST fit THIS context. The same word in a different sentence might be translated differently.")
        }
    }

    // --- Request / response DTOs ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GenerationConfig? = null,
    )

    data class GeminiContent(val parts: List<GeminiPart>)
    data class GeminiPart(val text: String)
    data class GenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
    )

    data class GeminiResponse(val candidates: List<Candidate> = emptyList())
    data class Candidate(val content: GeminiContent? = null)
}

class TranslationProviderException(
    message: String,
    val providerStatus: Int,
) : RuntimeException(message)
