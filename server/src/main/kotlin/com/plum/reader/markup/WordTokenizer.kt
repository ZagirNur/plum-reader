package com.plum.reader.markup

import org.apache.commons.text.StringEscapeUtils
import java.util.Locale

/**
 * Word tokenizer for the vocabulary pipeline.
 *
 * Pipeline:
 *   1. Strip HTML tags via regex.
 *   2. Unescape HTML entities (`&mdash;`, `&#x2014;`, `&amp;` etc) so they
 *      don't leak into the vocabulary as fake tokens like `mdash` or `amp`.
 *   3. Split on anything that isn't a letter or any apostrophe (ASCII `'`
 *      or curly `U+2018` / `U+2019` — typesetters routinely use curly
 *      quotes, and treating them as separators would shred every English
 *      contraction).
 *   4. Trim leading/trailing apostrophes, lowercase via `Locale.ROOT`.
 *   5. Discard tokens shorter than [minLength] (default 2) and tokens
 *      that contain no letters (pure digits etc).
 *
 * Targets EN + Cyrillic at minimum; `\p{L}` covers both. Stopwords and
 * stemming/lemmatization are deliberately not done here — that's a future
 * follow-up once the basic vocabulary surface is shipped.
 */
object WordTokenizer {

    private val TAG_STRIPPER = Regex("<[^>]+>")
    // Keep ASCII apostrophe plus curly apostrophes — they're part of words.
    private val NON_WORD = Regex("[^\\p{L}'‘’]+")
    private val APOSTROPHE_CHARS = charArrayOf('\'', '‘', '’')
    private const val DEFAULT_MIN_LENGTH = 2

    fun tokenize(text: String, minLength: Int = DEFAULT_MIN_LENGTH): List<String> {
        val stripped = TAG_STRIPPER.replace(text, " ")
        val decoded = StringEscapeUtils.unescapeHtml4(stripped)
        return NON_WORD.split(decoded)
            .asSequence()
            .map { it.trim(*APOSTROPHE_CHARS).lowercase(Locale.ROOT) }
            .filter { it.length >= minLength && it.any { ch -> ch.isLetter() } }
            .toList()
    }

    /** Aggregate token list into a frequency map. */
    fun frequencyOf(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (w in tokenize(text)) {
            out.merge(w, 1, Int::plus)
        }
        return out
    }
}
