package com.plum.reader.books

import com.plum.reader.books.EpubArchive.DC_NS
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

data class EpubMetadata(
    val title: String?,
    val author: String?,
    val language: String?,
)

class InvalidEpubException(message: String) : RuntimeException(message)

/**
 * Reads Dublin Core metadata from an EPUB. Heavy lifting (ZIP / XML safety)
 * lives in [EpubArchive].
 */
object EpubInspector {
    private val log = LoggerFactory.getLogger(javaClass)

    fun inspect(bytes: ByteArray): EpubMetadata {
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
        val doc = EpubArchive.parseSafeXml(opfBytes.inputStream())
        val rootfiles = doc.documentElement
        if (rootfiles.localName != "package") {
            // Not fatal — still try to extract DC.
            log.info("epub.opf_root local={} ns={}", rootfiles.localName, rootfiles.namespaceURI)
        }
        return EpubMetadata(
            title = doc.firstDcText("title"),
            author = doc.firstDcText("creator"),
            language = doc.firstDcText("language"),
        )
    }

    private fun Document.firstDcText(localName: String): String? {
        val list = getElementsByTagNameNS(DC_NS, localName)
        if (list.length == 0) return null
        val node = list.item(0) as Element
        return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}
