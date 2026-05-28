package com.plum.reader.translate

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "plum.translate")
data class TranslationProperties(
    val provider: String = "gemini",
    val gemini: Gemini = Gemini(),
    /** ±N символов контекста вокруг слова, что шлём в LLM. */
    val contextRadius: Int = 250,
    /** Языки, которые разрешены как targetLang. Если пусто — любой 2-3 буквенный код. */
    val allowedTargetLanguages: List<String> = listOf("ru", "en", "es", "de", "fr"),
    /** Дефолтный язык-цель если клиент не прислал ?lang. */
    val defaultTargetLanguage: String = "ru",
) {
    data class Gemini(
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val timeoutSeconds: Long = 15,
    )
}
