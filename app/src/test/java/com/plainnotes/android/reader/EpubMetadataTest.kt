package com.plainnotes.android.reader

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

internal fun sampleEpub(file: File, metadata: String = "<dc:title id=\"title\">Original</dc:title><dc:creator id=\"author\">Author</dc:creator>", version: String = "3.0", signed: Boolean = false): File {
    file.parentFile!!.mkdirs()
    ZipOutputStream(file.outputStream()).use { out ->
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="$version" unique-identifier="uid"><metadata><dc:identifier id="uid">urn:uuid:keep</dc:identifier>$metadata<dc:contributor>Editor</dc:contributor></metadata><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
            "OPS/chapter.xhtml" to "<html><body>Keep this chapter exactly.</body></html>",
            "OPS/asset.bin" to "\u0000binary asset\u0001"
        )
        if (signed) entries["META-INF/signatures.xml"] = "<signatures/>"
        entries.forEach { (name, value) -> out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry() }
    }
    return file
}

class EpubMetadataTest {
    private fun temp() = Files.createTempDirectory("epub-test").toFile()

    @Test fun rewritesMetadataButPreservesContentAndEpubIdentity() {
        val dir = temp(); val source = sampleEpub(File(dir, "original.epub")); val original = source.readBytes()
        val output = File(dir, "edited.epub")
        EpubMetadata.rewrite(source, output, "New & <title> 日本語", "Writer & Co.")
        assertEquals(EpubBookMetadata("New & <title> 日本語", "Writer & Co."), EpubMetadata.read(output))
        assertArrayEquals(original, source.readBytes())
        ZipFile(source).use { before -> ZipFile(output).use { after ->
            assertEquals(before.entries().asSequence().map { it.name }.toSet(), after.entries().asSequence().map { it.name }.toSet())
            listOf("OPS/chapter.xhtml", "OPS/asset.bin", "META-INF/container.xml").forEach { path ->
                assertArrayEquals(before.getInputStream(before.getEntry(path)).readBytes(), after.getInputStream(after.getEntry(path)).readBytes())
            }
            val first = after.entries().nextElement(); assertEquals("mimetype", first.name); assertEquals(ZipEntry.STORED, first.method)
            val opf = after.getInputStream(after.getEntry("OPS/book.opf")).reader().readText()
            listOf("urn:uuid:keep", "idref=\"c\"", "Editor", "dcterms:modified").forEach { assertTrue(opf.contains(it)) }
        } }
    }

    @Test fun titleOnlyChangePreservesMultipleCreatorsAndRefinements() {
        val dir = temp(); val source = sampleEpub(File(dir, "source.epub"), """<dc:title id="subtitle">Subtitle</dc:title><dc:title id="main">Main</dc:title><meta refines="#main" property="title-type">main</meta><dc:creator id="a">Alice</dc:creator><dc:creator id="b">Bob</dc:creator><meta refines="#a" property="file-as">Alice, A</meta>""")
        val output = File(dir, "out.epub")
        assertEquals(EpubBookMetadata("Main", "Alice, Bob"), EpubMetadata.read(source))
        EpubMetadata.rewrite(source, output, "Changed", "Alice, Bob")
        ZipFile(output).use { zip ->
            val opf = zip.getInputStream(zip.getEntry("OPS/book.opf")).reader().readText()
            listOf("Subtitle", "Alice, A", ">Alice<", ">Bob<").forEach { assertTrue(opf.contains(it)) }
        }
        EpubMetadata.rewrite(source, File(dir, "author.epub"), "Main", "New Author")
        assertEquals(EpubBookMetadata("Main", "New Author"), EpubMetadata.read(File(dir, "author.epub")))
        EpubMetadata.rewrite(source, File(dir, "none.epub"), "Main", "")
        assertEquals("", EpubMetadata.read(File(dir, "none.epub")).author)
    }

    @Test fun supportsMissingFieldsAndEpub2() {
        val dir = temp(); val source = sampleEpub(File(dir, "source.epub"), "", "2.0")
        assertEquals(EpubBookMetadata("", ""), EpubMetadata.read(source))
        val output = File(dir, "out.epub"); EpubMetadata.rewrite(source, output, "Title", "Author")
        assertEquals(EpubBookMetadata("Title", "Author"), EpubMetadata.read(output))
    }

    @Test fun rejectsSignedBooksWithoutChangingSource() {
        val dir = temp(); val source = sampleEpub(File(dir, "signed.epub"), signed = true); val bytes = source.readBytes()
        assertThrows(IllegalArgumentException::class.java) { EpubMetadata.rewrite(source, File(dir, "out.epub"), "Title", "Author") }
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun rejectsExternalEntitiesInUtf8AndUtf16() {
        listOf(Charsets.UTF_8, Charsets.UTF_16).forEach { charset ->
            val file = File(temp(), "unsafe.epub")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("""<?xml version="1.0" encoding="${charset.name()}"?><!DOCTYPE container [<!ENTITY x SYSTEM "file:///etc/passwd">]><container>&x;</container>""".toByteArray(charset)); zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { EpubMetadata.read(file) }
        }
    }

    @Test fun locksExtensionAndRejectsInvalidNames() {
        assertEquals("New name.epub", EpubMetadata.filename(" New name ", "old.epub"))
        assertEquals("New.EPUB", EpubMetadata.filename("New.epub", "old.EPUB"))
        listOf("", " ", ".", "..", "a/b", "a\\b", "bad\u0000name").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { EpubMetadata.filename(name, "old.epub") }
        }
    }
}
