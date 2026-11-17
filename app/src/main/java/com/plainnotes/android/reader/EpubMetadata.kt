package com.plainnotes.android.reader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

internal data class EpubBookMetadata(val title: String, val author: String)

/** Edits only the OPF metadata; the identifier, spine, resources and content stay intact. */
internal object EpubMetadata {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val OPF = "http://www.idpf.org/2007/opf"
    private const val XML_LIMIT = 2 * 1024 * 1024
    private const val MIME = "application/epub+zip"

    fun filename(stem: String, original: String): String {
        val suffix = if (original.endsWith(".epub", true)) original.takeLast(5) else ".epub"
        val name = stem.trim().let { if (it.endsWith(".epub", true)) it.dropLast(5).trim() else it }
        require(name.isNotBlank() && name !in listOf(".", "..")) { "Enter a filename." }
        require(name.none { it == '/' || it == '\\' || it.code < 32 }) { "The filename cannot contain slashes or control characters." }
        return name + suffix
    }

    fun read(file: File): EpubBookMetadata = ZipFile(file).use { zip ->
        val document = xml(readEntry(zip, packagePath(zip)))
        val metadata = metadata(document)
        EpubBookMetadata(mainTitle(metadata)?.textContent?.trim().orEmpty(),
            dcChildren(metadata, "creator").map { it.textContent.trim() }.filter { it.isNotBlank() }.joinToString(", "))
    }

    fun rewrite(source: File, output: File, title: String, author: String) {
        require(title.isNotBlank()) { "Enter a title to save EPUB metadata." }
        ZipFile(source).use { zip ->
            require(zip.getEntry("META-INF/signatures.xml") == null) { "Metadata in digitally signed EPUBs cannot be changed." }
            require(String(readEntry(zip, "mimetype"), Charsets.US_ASCII) == MIME) { "This is not a supported EPUB." }
            val path = packagePath(zip)
            val document = xml(readEntry(zip, path))
            val metadata = metadata(document)
            if (metadata.lookupPrefix(DC) == null) metadata.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:dc", DC)
            val titleElement = mainTitle(metadata) ?: document.createElementNS(DC, "dc:title").also { metadata.appendChild(it) }
            titleElement.textContent = title.trim()
            val creators = dcChildren(metadata, "creator")
            if (creators.map { it.textContent.trim() }.filter { it.isNotBlank() }.joinToString(", ") != author.trim()) {
            val removedIds = mutableSetOf<String>()
            creators.drop(if (author.isBlank()) 0 else 1).forEach {
                it.getAttribute("id").takeIf(String::isNotBlank)?.let(removedIds::add)
                metadata.removeChild(it)
            }
            val first = if (author.isBlank()) null else creators.firstOrNull()
                ?: document.createElementNS(DC, "dc:creator").also { metadata.appendChild(it) }
            first?.let {
                it.textContent = author.trim()
                it.removeAttributeNS(OPF, "file-as"); it.removeAttribute("file-as")
                metadata.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:opf", OPF)
                it.setAttributeNS(OPF, "opf:role", "aut")
            }
            val creatorId = first?.getAttribute("id").orEmpty()
            children(metadata).filter { element ->
                val reference = element.getAttribute("refines").removePrefix("#")
                reference in removedIds || (creatorId.isNotBlank() && reference == creatorId &&
                    element.getAttribute("property") in listOf("file-as", "role"))
            }.forEach(metadata::removeChild)
            }
            if (document.documentElement.getAttribute("version").startsWith("3")) {
                val modified = children(metadata).firstOrNull { it.getAttribute("property") == "dcterms:modified" }
                    ?: document.createElementNS(OPF, "meta").also { it.setAttribute("property", "dcterms:modified"); metadata.appendChild(it) }
                modified.textContent = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
            }
            val factory = TransformerFactory.newInstance()
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            val transformed = ByteArrayOutputStream().also { stream ->
                factory.newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
                    .transform(DOMSource(document), StreamResult(stream))
            }.toByteArray()
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.distinct().size == names.size) { "This EPUB has duplicate archive entries." }
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                // EPUB requires the uncompressed mimetype entry first, without extra fields.
                val mime = MIME.toByteArray(Charsets.US_ASCII)
                out.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = mime.size.toLong(); crc = CRC32().apply { update(mime) }.value
                })
                out.write(mime); out.closeEntry()
                zip.entries().asSequence().filter { it.name != "mimetype" }.forEach { original ->
                    out.putNextEntry(ZipEntry(original.name).apply { time = original.time; comment = original.comment })
                    if (original.name == path) out.write(transformed)
                    else zip.getInputStream(original).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        // Fully read the new ZIP, checking its entry CRCs before any user file is touched.
        ZipInputStream(output.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (zip.nextEntry != null) { while (zip.read(buffer) != -1) { }; zip.closeEntry() }
        }
        require(read(output) == EpubBookMetadata(title.trim(), author.trim())) { "The edited EPUB could not be verified." }
    }

    private fun packagePath(zip: ZipFile): String {
        val container = xml(readEntry(zip, "META-INF/container.xml"))
        val root = container.getElementsByTagNameNS("*", "rootfile").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .firstOrNull { it.getAttribute("media-type") == "application/oebps-package+xml" }
        } ?: throw IOException("The EPUB package metadata is missing.")
        val raw = root.getAttribute("full-path")
        val path = if (zip.getEntry(raw) != null) raw else runCatching { URI(raw).path }.getOrNull().orEmpty()
        require(path.isNotBlank() && !path.startsWith('/') && ".." !in path.split('/')) { "The EPUB package path is invalid." }
        return path
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: throw IOException("Missing EPUB entry: $path")
        require(entry.size <= XML_LIMIT) { "The EPUB metadata is too large." }
        return zip.getInputStream(entry).use { input ->
            val bytes = input.readBytesLimited(XML_LIMIT)
            bytes
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(result.size() + count <= limit) { "The EPUB metadata is too large." }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun xml(bytes: ByteArray): Document {
        // Reject declarations before parsing, including UTF-16/32 byte layouts. No DTD or
        // custom entity is needed for an OPF/container, and none may resolve external files.
        val declarations = String(bytes, Charsets.ISO_8859_1).replace("\u0000", "")
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(declarations)) { "External XML declarations are not supported in EPUB metadata." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setXIncludeAware(false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
    private fun metadata(document: Document): Element = children(document.documentElement)
        .firstOrNull { it.localName == "metadata" } ?: throw IOException("The EPUB metadata is missing.")
    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }
    private fun dcChildren(element: Element, name: String) = children(element).filter { it.namespaceURI == DC && it.localName == name }
    private fun mainTitle(metadata: Element): Element? {
        val titles = dcChildren(metadata, "title")
        val mainId = children(metadata).firstOrNull { it.getAttribute("property") == "title-type" && it.textContent.trim() == "main" }
            ?.getAttribute("refines")?.removePrefix("#")
        return titles.firstOrNull { mainId != null && it.getAttribute("id") == mainId } ?: titles.firstOrNull()
    }
}
