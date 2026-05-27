package com.plum.reader.books

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A single rendered page extracted from the EPUB spine.
 *
 * `idx` is 0-based and matches the order of `<spine><itemref/></spine>`
 * in the OPF — the reader (mobile/web) advances through pages in this order.
 */
data class EpubPage(
    val idx: Int,
    val xhtml: String,
    val textLen: Int,
)

/**
 * Splits an EPUB into spine-ordered pages. Reuses the same zip-bomb and
 * XXE-safe parsing posture as [EpubInspector].
 */
object EpubSplitter {
    private val log = LoggerFactory.getLogger(javaClass)

    private const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 200L * 1024 * 1024
    private const val MAX_ENTRIES: Int = 10_000
    private const val MAX_XML_BYTES: Int = 5 * 1024 * 1024
    private const val OPF_NS = "http://www.idpf.org/2007/opf"
    private const val OCF_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
    private val TAG_STRIPPER = Regex("<[^>]+>")

    fun split(bytes: ByteArray): List<EpubPage> {
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
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val (manifest, spine) = parseOpf(opfBytes)

        val pages = ArrayList<EpubPage>(spine.size)
        spine.forEachIndexed { idx, idref ->
            val href = manifest[idref]
                ?: throw InvalidEpubException("spine itemref '$idref' not in manifest")
            val resolved = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val normalized = normalizeZipPath(resolved)
            val pageBytes = entries[normalized]
                ?: throw InvalidEpubException("spine item not in archive: $normalized")
            val xhtml = pageBytes.toString(Charsets.UTF_8)
            pages.add(EpubPage(idx = idx, xhtml = xhtml, textLen = plainTextLength(xhtml)))
        }
        if (pages.isEmpty()) {
            throw InvalidEpubException("spine is empty — no pages to render")
        }
        log.debug("epub.split pages={}", pages.size)
        return pages
    }

    private fun parseOpf(bytes: ByteArray): Pair<Map<String, String>, List<String>> {
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        val manifest = mutableMapOf<String, String>()
        val manifestEls = doc.getElementsByTagNameNS(OPF_NS, "item")
        for (i in 0 until manifestEls.length) {
            val el = manifestEls.item(i) as Element
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }
        val spine = mutableListOf<String>()
        val spineEls = doc.getElementsByTagNameNS(OPF_NS, "itemref")
        for (i in 0 until spineEls.length) {
            val el = spineEls.item(i) as Element
            val linear = el.getAttribute("linear")
            // EPUB spec: linear="no" items are auxiliary; skip them.
            if (linear == "no") continue
            val idref = el.getAttribute("idref")
            if (idref.isNotEmpty()) spine.add(idref)
        }
        return manifest to spine
    }

    private fun parseContainer(bytes: ByteArray): String {
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        val rootfiles = doc.getElementsByTagNameNS(OCF_NS, "rootfile")
        if (rootfiles.length == 0) throw InvalidEpubException("container.xml has no rootfile element")
        val first = rootfiles.item(0) as Element
        val fullPath = first.getAttribute("full-path")
        if (fullPath.isNullOrBlank()) throw InvalidEpubException("rootfile has no full-path")
        return fullPath
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

    /** Resolve `a/b/../c.xhtml` → `a/c.xhtml`; reject anything trying to escape the archive root. */
    private fun normalizeZipPath(path: String): String {
        val parts = path.split('/').toMutableList()
        val out = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) throw InvalidEpubException("path escapes archive: $path")
                    out.removeLast()
                }
                else -> out.addLast(p)
            }
        }
        return out.joinToString("/")
    }

    /** Approximate plain-text length: strip tags, drop runs of whitespace. */
    private fun plainTextLength(xhtml: String): Int =
        TAG_STRIPPER.replace(xhtml, " ").replace(Regex("\\s+"), " ").trim().length

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
        return factory.newDocumentBuilder().parse(InputSource(input))
    }
}
