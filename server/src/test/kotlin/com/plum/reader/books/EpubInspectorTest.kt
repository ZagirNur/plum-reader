package com.plum.reader.books

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EpubInspectorTest {

    @Test
    fun `parses title author language from minimal valid epub`() {
        val bytes = buildEpub(
            mimetype = "application/epub+zip",
            opfPath = "OEBPS/content.opf",
            opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Pride and Prejudice</dc:title>
                    <dc:creator>Jane Austen</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                </package>
            """.trimIndent(),
        )
        val m = EpubInspector.inspect(bytes)
        assertEquals("Pride and Prejudice", m.title)
        assertEquals("Jane Austen", m.author)
        assertEquals("en", m.language)
    }

    @Test
    fun `missing PK header is rejected`() {
        val notZip = "this is not a zip file".toByteArray()
        val ex = assertFailsWith<InvalidEpubException> { EpubInspector.inspect(notZip) }
        assertEquals("not a ZIP archive (missing PK header)", ex.message)
    }

    @Test
    fun `wrong mimetype is rejected`() {
        val bytes = buildEpub(
            mimetype = "application/zip",
            opfPath = "OEBPS/content.opf",
            opf = minimalOpf(),
        )
        assertFailsWith<InvalidEpubException> { EpubInspector.inspect(bytes) }
    }

    @Test
    fun `missing container xml is rejected`() {
        val bytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { z ->
                z.putNextEntry(ZipEntry("mimetype"))
                z.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
                z.closeEntry()
            }
            baos.toByteArray()
        }
        val ex = assertFailsWith<InvalidEpubException> { EpubInspector.inspect(bytes) }
        assertEquals("META-INF/container.xml missing", ex.message)
    }

    @Test
    fun `null metadata fields when DC elements missing`() {
        val bytes = buildEpub(
            mimetype = "application/epub+zip",
            opfPath = "OEBPS/content.opf",
            opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"/>
                </package>
            """.trimIndent(),
        )
        val m = EpubInspector.inspect(bytes)
        assertNull(m.title)
        assertNull(m.author)
        assertNull(m.language)
    }

    private fun minimalOpf() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>X</dc:title>
          </metadata>
        </package>
    """.trimIndent()

    private fun buildEpub(mimetype: String, opfPath: String, opf: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write(mimetype.toByteArray(Charsets.US_ASCII)); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write(
                """<?xml version="1.0"?>
                   <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                     <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
                   </container>""".trimIndent().toByteArray(),
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry(opfPath)); z.write(opf.toByteArray()); z.closeEntry()
        }
        return baos.toByteArray()
    }
}
