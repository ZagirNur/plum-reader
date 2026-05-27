package com.plum.reader.books

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class EpubMetadata(
    val title: String?,
    val author: String?,
    val language: String?,
)

class InvalidEpubException(message: String) : RuntimeException(message)

/**
 * Lightweight EPUB inspector — opens the file as a ZIP, locates the OPF
 * package document via `META-INF/container.xml`, and reads the Dublin Core
 * metadata (`dc:title`, `dc:creator`, `dc:language`).
 *
 * No external EPUB libraries — keeps the dependency tree minimal and avoids
 * pulling in XML toolkits that have to be CVE-patched separately.
 */
object EpubInspector {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Hard cap on uncompressed bytes across all ZIP entries (zip-bomb guard). */
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024  // 200 MB

    /** Hard cap on number of entries in the archive. */
    private const val MAX_ENTRIES: Int = 10_000

    /** Hard cap on a single OPF / container.xml size. */
    private const val MAX_XML_BYTES: Int = 5 * 1024 * 1024  // 5 MB

    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val OCF_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

    /** Validate the file looks like an EPUB and pull metadata. */
    fun inspect(bytes: ByteArray): EpubMetadata {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw InvalidEpubException("not a ZIP archive (missing PK header)")
        }
        val entries = readZipEntries(bytes)
        val mimetype = entries["mimetype"]?.toString(Charsets.US_ASCII)?.trim()
        if (mimetype != "application/epub+zip") {
            throw InvalidEpubException("mimetype entry missing or wrong: $mimetype")
        }
        val containerBytes = entries["META-INF/container.xml"]
            ?: throw InvalidEpubException("META-INF/container.xml missing")
        if (containerBytes.size > MAX_XML_BYTES) {
            throw InvalidEpubException("container.xml exceeds size limit")
        }
        val opfPath = parseContainer(containerBytes)
        val opfBytes = entries[opfPath]
            ?: throw InvalidEpubException("OPF package missing at $opfPath")
        if (opfBytes.size > MAX_XML_BYTES) {
            throw InvalidEpubException("OPF exceeds size limit")
        }
        return parseOpf(opfBytes)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
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
                    // Read with a running uncompressed cap — do NOT trust entry.size.
                    val data = readBounded(zip, MAX_TOTAL_UNCOMPRESSED_BYTES - totalUncompressed)
                    totalUncompressed += data.size
                    if (out.putIfAbsent(entry.name, data) != null) {
                        // OCF 3.0 §4.1.2 forbids duplicate entries; reject explicitly to defeat
                        // zip-confusion attacks where a benign entry appears before a malicious one.
                        throw InvalidEpubException("duplicate ZIP entry: ${entry.name}")
                    }
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** Read up to [remaining] bytes, throwing if the stream produces more. */
    private fun readBounded(input: InputStream, remaining: Long): ByteArray {
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

    private fun parseContainer(bytes: ByteArray): String {
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        val rootfiles = doc.getElementsByTagNameNS(OCF_NS, "rootfile")
        if (rootfiles.length == 0) throw InvalidEpubException("container.xml has no rootfile element")
        if (rootfiles.length > 1) {
            log.info("epub.multiple_rootfiles count={} (first wins)", rootfiles.length)
        }
        val first = rootfiles.item(0) as Element
        val fullPath = first.getAttribute("full-path")
        if (fullPath.isNullOrBlank()) throw InvalidEpubException("rootfile has no full-path")
        return fullPath
    }

    private fun parseOpf(bytes: ByteArray): EpubMetadata = parseSafeXml(ByteArrayInputStream(bytes)).run {
        EpubMetadata(
            title = firstDcText("title"),
            author = firstDcText("creator"),
            language = firstDcText("language"),
        )
    }

    private fun Document.firstDcText(localName: String): String? {
        val list: NodeList = getElementsByTagNameNS(DC_NS, localName)
        if (list.length == 0) return null
        val node: Node = list.item(0)
        return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** XXE-safe XML parser — external entities and DOCTYPE disabled. */
    private fun parseSafeXml(input: InputStream): Document {
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
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(input))
    }
}
