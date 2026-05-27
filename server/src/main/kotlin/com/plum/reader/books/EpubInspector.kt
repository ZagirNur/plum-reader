package com.plum.reader.books

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
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
        val opfPath = parseContainer(containerBytes)
        val opfBytes = entries[opfPath]
            ?: throw InvalidEpubException("OPF package missing at $opfPath")
        return parseOpf(opfBytes)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun parseContainer(bytes: ByteArray): String {
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
        if (rootfiles.length == 0) throw InvalidEpubException("container.xml has no rootfile element")
        val first = rootfiles.item(0) as Element
        val fullPath = first.getAttribute("full-path")
        if (fullPath.isNullOrBlank()) throw InvalidEpubException("rootfile has no full-path")
        return fullPath
    }

    private fun parseOpf(bytes: ByteArray): EpubMetadata {
        val doc = parseSafeXml(ByteArrayInputStream(bytes))
        // Dublin Core elements live in the `http://purl.org/dc/elements/1.1/` namespace.
        return EpubMetadata(
            title = doc.firstText("title")?.trim(),
            author = doc.firstText("creator")?.trim(),
            language = doc.firstText("language")?.trim(),
        )
    }

    private fun Document.firstText(localName: String): String? {
        val list: NodeList = getElementsByTagNameNS("*", localName)
        if (list.length == 0) return null
        val node: Node = list.item(0)
        return node.textContent
    }

    /** XXE-safe XML parser — external entities and DOCTYPE disabled. */
    private fun parseSafeXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(input))
    }
}
