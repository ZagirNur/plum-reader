package com.plum.reader.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordTokenizerTest {

    @Test
    fun `strips html and tokenizes by non-letters`() {
        val out = WordTokenizer.tokenize("<p>Hello, <b>brave</b> new world!</p>")
        assertEquals(listOf("hello", "brave", "new", "world"), out)
    }

    @Test
    fun `lowercases via ROOT locale`() {
        val out = WordTokenizer.tokenize("ABC abc Abc")
        assertEquals(listOf("abc", "abc", "abc"), out)
    }

    @Test
    fun `preserves apostrophes inside words but trims trailing`() {
        val out = WordTokenizer.tokenize("don't 'quoted' Mr.O'Brien")
        // Don't break "don't" or "o'brien"; standalone-apostrophe tokens are
        // trimmed and the empty string is filtered out.
        assertEquals(listOf("don't", "quoted", "mr", "o'brien"), out)
    }

    @Test
    fun `keeps Cyrillic`() {
        val out = WordTokenizer.tokenize("Привет, мир! Hello world.")
        assertEquals(listOf("привет", "мир", "hello", "world"), out)
    }

    @Test
    fun `discards single-letter tokens by default`() {
        val out = WordTokenizer.tokenize("I am a writer.")
        assertEquals(listOf("am", "writer"), out)
    }

    @Test
    fun `discards digits-only tokens (no letters)`() {
        val out = WordTokenizer.tokenize("hello 123 world 456")
        assertEquals(listOf("hello", "world"), out)
    }

    @Test
    fun `frequencyOf aggregates token counts`() {
        val freq = WordTokenizer.frequencyOf("hello world hello brave new world hello")
        assertEquals(3, freq["hello"])
        assertEquals(2, freq["world"])
        assertEquals(1, freq["brave"])
        assertEquals(1, freq["new"])
        assertEquals(4, freq.size)
    }

    @Test
    fun `curly apostrophes do not split words`() {
        // U+2019 RIGHT SINGLE QUOTATION MARK is the typographically-correct
        // apostrophe most EPUB publishers use. We must keep `don't` intact.
        val out = WordTokenizer.tokenize("don’t ‘quoted’ it’s")
        assertEquals(listOf("don’t", "quoted", "it’s"), out)
    }

    @Test
    fun `html entities are decoded before splitting`() {
        // &mdash; / &#x2014; should become "—" and act as a separator,
        // NOT become a token "mdash" in the vocabulary.
        val out = WordTokenizer.tokenize("Mr. Bennet&mdash;a witty father&#8212;said nothing.")
        assertEquals(listOf("mr", "bennet", "witty", "father", "said", "nothing"), out)
        // &amp; → "&" is a non-letter, splits.
        val out2 = WordTokenizer.tokenize("Bread &amp; butter")
        assertEquals(listOf("bread", "butter"), out2)
    }

    @Test
    fun `frequencyOf handles realistic xhtml chunk`() {
        val xhtml = """
            <html><body>
              <h1>Chapter One</h1>
              <p>It is a truth universally acknowledged, that a single man
                 in possession of a good fortune must be in want of a wife.</p>
            </body></html>
        """.trimIndent()
        val freq = WordTokenizer.frequencyOf(xhtml)
        // "a" is single-letter → filtered; "in" appears twice.
        assertEquals(2, freq["in"])
        assertEquals(1, freq["fortune"])
        assertEquals(null, freq["a"])
        assertTrue(freq.values.all { it >= 1 })
    }
}
