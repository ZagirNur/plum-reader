package com.plum.reader.books

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared EPUB / OCF utilities used by both [EpubInspector] (metadata) and
 * [EpubSplitter] (pages). Centralised so the zip-bomb / XXE / duplicate-entry
 * defences live in exactly one place.
 */
object EpubArchive {

    /** Hard cap on uncompressed bytes across all ZIP entries (zip-bomb guard). */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024  // 200 MB

    /** Hard cap on number of entries. */
    const val MAX_ENTRIES: Int = 10_000

    /** Hard cap on a single OPF / container.xml size. */
    const val MAX_XML_BYTES: Int = 5 * 1024 * 1024  // 5 MB

    const val DC_NS = "http://purl.org/dc/elements/1.1/"
    const val OPF_NS = "http://www.idpf.org/2007/opf"
    const val OCF_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

    /** Reject non-ZIP up front via PK header check. */
    fun assertZipHeader(bytes: ByteArray) {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw InvalidEpubException("not a ZIP archive (missing PK header)")
        }
    }

    /**
     * Read all entries into memory, honouring:
     * - [MAX_ENTRIES] cap (defeat archives with millions of entries),
     * - [MAX_TOTAL_UNCOMPRESSED_BYTES] running cap (defeat zip bombs),
     * - duplicate-entry rejection (defeat OCF zip-confusion attacks).
     */
    fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var entryCount = 0
        var totalUncompressed = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw InvalidEpubException("archive has too many entries (>$MAX_ENTRIES)")
                }
                if (!entry.isDirectory) {
                    val data = readBounded(zip, MAX_TOTAL_UNCOMPRESSED_BYTES - totalUncompressed)
                    totalUncompressed += data.size
                    if (out.putIfAbsent(entry.name, data) != null) {
                        throw InvalidEpubException("duplicate ZIP entry: ${entry.name}")
                    }
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Read at most [remaining] bytes from [input]; one over and we throw. */
    fun readBounded(input: InputStream, remaining: Long): ByteArray {
        if (remaining <= 0) throw InvalidEpubException("archive exceeds uncompressed size limit")
        val buf = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var left = remaining
        while (true) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left + 1).toInt())
            if (n < 0) break
            if (n.toLong() > left) {
                throw InvalidEpubException("archive exceeds uncompressed size limit ($MAX_TOTAL_UNCOMPRESSED_BYTES bytes)")
            }
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    /** Parse META-INF/container.xml → OPF rootfile full-path. */
    fun parseContainer(bytes: ByteArray): String {
        if (bytes.size > MAX_XML_BYTES) throw InvalidEpubException("container.xml exceeds size limit")
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        val rootfiles = doc.getElementsByTagNameNS(OCF_NS, "rootfile")
        if (rootfiles.length == 0) throw InvalidEpubException("container.xml has no rootfile element")
        val first = rootfiles.item(0) as Element
        val fullPath = first.getAttribute("full-path")
        if (fullPath.isNullOrBlank()) throw InvalidEpubException("rootfile has no full-path")
        return fullPath
    }

    /** XXE-safe XML parser — DOCTYPE/external entities/XInclude disabled. */
    fun parseSafeXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(InputSource(input))
    }
}
