package com.plainnotes.android.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** One provider query per directory, rather than queries for every DocumentFile property. */
internal data class DocumentEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val modifiedMillis: Long?,
    val size: Long?,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    val isNote: Boolean get() = !isDirectory && name != TodoFileParser.FILE_NAME &&
        (name.endsWith(".txt", ignoreCase = true) || mimeType == "text/plain")
}

internal class DocumentDirectory(private val resolver: ContentResolver) {
    fun children(directory: DocumentFile): List<DocumentEntry> {
        if (directory.uri.scheme == "file") {
            val folder = File(requireNotNull(directory.uri.path))
            return (folder.listFiles() ?: throw IOException("Unable to list the notes folder.")).map(::localEntry)
        }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory.uri, DocumentsContract.getDocumentId(directory.uri),
        )
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Unable to list the notes folder.")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(DocumentEntry(
                        DocumentsContract.buildDocumentUriUsingTree(directory.uri, it.getString(0)),
                        it.getString(1).orEmpty(), it.getString(2).orEmpty(),
                        if (it.isNull(3)) null else it.getLong(3),
                        if (it.isNull(4)) null else it.getLong(4),
                    ))
                }
            }
        }
    }

    fun stat(uri: Uri): DocumentEntry? {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            return if (file.exists()) localEntry(file) else null
        }
        return resolver.query(uri, PROJECTION, null, null, null)?.use {
            if (!it.moveToFirst()) null else DocumentEntry(uri, it.getString(1).orEmpty(),
                it.getString(2).orEmpty(), if (it.isNull(3)) null else it.getLong(3),
                if (it.isNull(4)) null else it.getLong(4))
        }
    }

    private fun localEntry(file: File) = DocumentEntry(Uri.fromFile(file), file.name,
        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else
            if (file.extension.equals("txt", true)) "text/plain" else "application/octet-stream",
        file.lastModified(), file.length())

    companion object {
        private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
    }
}
