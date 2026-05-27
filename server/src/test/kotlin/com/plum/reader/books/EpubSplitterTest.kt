package com.plum.reader.books

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpubSplitterTest {

    @Test
    fun `splits spine into pages in order`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            manifest = mapOf("c1" to "ch1.xhtml", "c2" to "ch2.xhtml", "c3" to "ch3.xhtml"),
            spine = listOf("c1" to null, "c2" to null, "c3" to null),
            files = mapOf(
                "OEBPS/ch1.xhtml" to "<html><body><p>One</p></body></html>",
                "OEBPS/ch2.xhtml" to "<html><body><p>Two two</p></body></html>",
                "OEBPS/ch3.xhtml" to "<html><body><p>Three three three</p></body></html>",
            ),
        )
        val pages = EpubSplitter.split(bytes)
        assertEquals(3, pages.size)
        assertEquals(0, pages[0].idx)
        assertEquals(1, pages[1].idx)
        assertEquals(2, pages[2].idx)
        assertContains(pages[0].xhtml, "One")
        assertContains(pages[1].xhtml, "Two two")
        assertContains(pages[2].xhtml, "Three three three")
    }

    @Test
    fun `linear=no items are skipped`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            manifest = mapOf("c1" to "ch1.xhtml", "aux" to "footnotes.xhtml", "c2" to "ch2.xhtml"),
            spine = listOf("c1" to null, "aux" to "no", "c2" to null),
            files = mapOf(
                "OEBPS/ch1.xhtml" to "<html><body>1</body></html>",
                "OEBPS/footnotes.xhtml" to "<html><body>aux</body></html>",
                "OEBPS/ch2.xhtml" to "<html><body>2</body></html>",
            ),
        )
        val pages = EpubSplitter.split(bytes)
        assertEquals(2, pages.size)
        assertContains(pages[0].xhtml, "1")
        assertContains(pages[1].xhtml, "2")
    }

    @Test
    fun `href is URL-decoded and fragment stripped`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            // href has a space (encoded) and a fragment
            manifest = mapOf("c1" to "chapter%20one.xhtml#start"),
            spine = listOf("c1" to null),
            files = mapOf("OEBPS/chapter one.xhtml" to "<html><body>hi</body></html>"),
        )
        val pages = EpubSplitter.split(bytes)
        assertEquals(1, pages.size)
        assertContains(pages[0].xhtml, "hi")
    }

    @Test
    fun `path traversal in href is rejected`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            manifest = mapOf("c1" to "../../etc/passwd"),
            spine = listOf("c1" to null),
            files = emptyMap(),
        )
        val ex = assertFailsWith<InvalidEpubException> { EpubSplitter.split(bytes) }
        assertContains(ex.message ?: "", "escapes archive")
    }

    @Test
    fun `missing manifest item for spine itemref fails`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            manifest = mapOf("a" to "a.xhtml"),
            spine = listOf("ghost" to null),
            files = mapOf("OEBPS/a.xhtml" to "<x/>"),
        )
        val ex = assertFailsWith<InvalidEpubException> { EpubSplitter.split(bytes) }
        assertContains(ex.message ?: "", "not in manifest")
    }

    @Test
    fun `empty spine fails`() {
        val bytes = buildEpub(
            opfPath = "OEBPS/content.opf",
            manifest = emptyMap(),
            spine = emptyList(),
            files = emptyMap(),
        )
        val ex = assertFailsWith<InvalidEpubException> { EpubSplitter.split(bytes) }
        assertContains(ex.message ?: "", "spine is empty")
    }

    @Test
    fun `text_len strips tags and collapses whitespace`() {
        val bytes = buildEpub(
            opfPath = "content.opf",
            manifest = mapOf("c" to "ch.xhtml"),
            spine = listOf("c" to null),
            files = mapOf(
                "ch.xhtml" to "<html><body><p>Hello    <b>brave</b>\nnew world</p></body></html>",
            ),
        )
        val page = EpubSplitter.split(bytes).single()
        // "Hello brave new world" → 21 chars
        assertEquals(21, page.textLen)
    }

    @Test
    fun `OPF item inside collection is ignored (scoped to manifest)`() {
        // Hand-craft OPF where an extra <opf:item id="ghost"> lives outside
        // <manifest>. EpubSplitter must scope item lookup to manifest and
        // refuse to resolve <itemref idref="ghost">.
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="$OPF_NS" version="3.0">
              <manifest>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ghost"/>
              </spine>
              <collection role="dictionary">
                <item id="ghost" href="ghost.xhtml" media-type="application/xhtml+xml"/>
              </collection>
            </package>
        """.trimIndent().trim()
        val bytes = buildEpubWithRawOpf(
            opfPath = "content.opf",
            opf = opf,
            files = mapOf(
                "ch1.xhtml" to "<x/>",
                "ghost.xhtml" to "<x/>",
            ),
        )
        val ex = assertFailsWith<InvalidEpubException> { EpubSplitter.split(bytes) }
        assertContains(ex.message ?: "", "not in manifest")
    }

    private val OPF_NS = "http://www.idpf.org/2007/opf"

    private fun buildEpub(
        opfPath: String,
        manifest: Map<String, String>,
        spine: List<Pair<String, String?>>,
        files: Map<String, String>,
    ): ByteArray {
        val manifestXml = manifest.entries.joinToString("\n") { (id, href) ->
            """<item id="$id" href="$href" media-type="application/xhtml+xml"/>"""
        }
        val spineXml = spine.joinToString("\n") { (idref, linear) ->
            if (linear != null) """<itemref idref="$idref" linear="$linear"/>"""
            else """<itemref idref="$idref"/>"""
        }
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="$OPF_NS" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"/>
              <manifest>
                $manifestXml
              </manifest>
              <spine>
                $spineXml
              </spine>
            </package>
        """.trimIndent().trim()
        return buildEpubWithRawOpf(opfPath, opf, files)
    }

    private fun buildEpubWithRawOpf(opfPath: String, opf: String, files: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("mimetype"))
            z.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            z.closeEntry()

            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write(
                """<?xml version="1.0"?>
                   <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                     <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""".trimIndent().toByteArray(),
            )
            z.closeEntry()

            z.putNextEntry(ZipEntry(opfPath))
            z.write(opf.toByteArray())
            z.closeEntry()

            for ((path, content) in files) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        // Sanity check we used `assertTrue` somewhere in test — silence lint.
        assertTrue(baos.size() > 0)
        return baos.toByteArray()
    }
}
