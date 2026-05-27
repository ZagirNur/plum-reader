package com.plum.reader.books

import com.plum.reader.books.EpubArchive.OPF_NS
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
 * Splits an EPUB into spine-ordered pages. ZIP / XML safety guarantees come
 * from [EpubArchive]; this object owns OPF-specific logic only.
 *
 * Note: one "page" here = one spine XHTML document, not a UI-paginated screen.
 * Real reader pagination happens client-side or in a later worker.
 */
object EpubSplitter {
    private val log = LoggerFactory.getLogger(javaClass)
    private val TAG_STRIPPER = Regex("<[^>]+>")

    fun split(bytes: ByteArray): List<EpubPage> {
        EpubArchive.assertZipHeader(bytes)
        val entries = EpubArchive.readZipEntries(bytes)
        val mimetype = entries["mimetype"]?.toString(Charsets.US_ASCII)?.trim()
        if (mimetype != "application/epub+zip") {
            throw InvalidEpubException("mimetype entry missing or wrong: $mimetype")
        }
        val containerBytes = entries["META-INF/container.xml"]
            ?: throw InvalidEpubException("META-INF/container.xml missing")
        val opfPath = EpubArchive.parseContainer(containerBytes)
        val opfBytes = entries[opfPath]
            ?: throw InvalidEpubException("OPF package missing at $opfPath")
        if (opfBytes.size > EpubArchive.MAX_XML_BYTES) {
            throw InvalidEpubException("OPF exceeds size limit")
        }
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val (manifest, spine) = parseOpf(opfBytes)

        val pages = ArrayList<EpubPage>(spine.size)
        spine.forEachIndexed { idx, idref ->
            val hrefRaw = manifest[idref]
                ?: throw InvalidEpubException("spine itemref '$idref' not in manifest")
            // Strip fragment (#section) and URL-decode (%20 etc).
            val hrefNoFrag = hrefRaw.substringBefore('#')
            val href = URLDecoder.decode(hrefNoFrag, StandardCharsets.UTF_8)
            val resolved = resolveHref(opfDir, href)
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

    /**
     * Find `<package>/<manifest><item/></manifest>` and `<package>/<spine><itemref/></spine>`
     * scoped to their parents — defends against custom `<opf:item>` elements
     * nested elsewhere in the document (e.g. `<collection>` extensions).
     */
    private fun parseOpf(bytes: ByteArray): Pair<Map<String, String>, List<String>> {
        val doc = EpubArchive.parseSafeXml(bytes.inputStream())
        val manifestEl = doc.getElementsByTagNameNS(OPF_NS, "manifest").item(0) as? Element
            ?: throw InvalidEpubException("OPF has no <manifest>")
        val spineEl = doc.getElementsByTagNameNS(OPF_NS, "spine").item(0) as? Element
            ?: throw InvalidEpubException("OPF has no <spine>")

        val manifest = mutableMapOf<String, String>()
        for (item in manifestEl.childElements(OPF_NS, "item")) {
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href
        }
        val spine = mutableListOf<String>()
        for (itemref in spineEl.childElements(OPF_NS, "itemref")) {
            // EPUB spec: linear="no" items are auxiliary; skip them.
            if (itemref.getAttribute("linear") == "no") continue
            val idref = itemref.getAttribute("idref")
            if (idref.isNotEmpty()) spine.add(idref)
        }
        return manifest to spine
    }

    private fun Element.childElements(ns: String, localName: String): List<Element> {
        val out = mutableListOf<Element>()
        val kids = childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.localName == localName && n.namespaceURI == ns) {
                out.add(n as Element)
            }
        }
        return out
    }

    /** Resolve href against OPF directory; absolute path (`/foo`) is rooted at archive top. */
    private fun resolveHref(opfDir: String, href: String): String = when {
        href.startsWith("/") -> href.removePrefix("/")
        opfDir.isEmpty() -> href
        else -> "$opfDir/$href"
    }

    /** Resolve `a/b/../c.xhtml` → `a/c.xhtml`; reject anything escaping the archive root. */
    private fun normalizeZipPath(path: String): String {
        val parts = path.split('/')
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

    /** Approximate plain-text length: strip tags, collapse whitespace runs. */
    private fun plainTextLength(xhtml: String): Int =
        TAG_STRIPPER.replace(xhtml, " ").replace(Regex("\\s+"), " ").trim().length
}
