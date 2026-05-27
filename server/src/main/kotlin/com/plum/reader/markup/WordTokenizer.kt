package com.plum.reader.markup

/**
 * Word tokenizer for the vocabulary pipeline.
 *
 * Design choices (kept intentionally simple — this is MVP, not full NLP):
 * - Strips HTML/XML tags first via regex.
 * - Splits on anything that isn't a letter or apostrophe (so `don't` survives
 *   intact but commas, parens, em-dashes, digits all split).
 * - Lower-cases via `String.lowercase(Locale.ROOT)` — locale-stable.
 * - Keeps Latin AND Cyrillic letters (Plum Reader targets ru/en books at
 *   minimum). Unicode property `\p{L}` would be ideal but Kotlin/JVM regex
 *   on JDK 21 supports it: `[^\p{L}']+` is the split pattern.
 * - Discards tokens shorter than [minLength] (default 2) — single letters
 *   are noise for vocabulary purposes.
 * - Discards purely-apostrophe artefacts (e.g. trailing `'` from quotes).
 */
object WordTokenizer {

    private val TAG_STRIPPER = Regex("<[^>]+>")
    private val NON_WORD = Regex("[^\\p{L}']+")
    private const val DEFAULT_MIN_LENGTH = 2

    fun tokenize(text: String, minLength: Int = DEFAULT_MIN_LENGTH): List<String> {
        val stripped = TAG_STRIPPER.replace(text, " ")
        return NON_WORD.split(stripped)
            .asSequence()
            .map { it.trim('\'').lowercase(java.util.Locale.ROOT) }
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
