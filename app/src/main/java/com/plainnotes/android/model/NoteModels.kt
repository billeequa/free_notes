package com.plainnotes.android.model

import android.net.Uri
import java.time.OffsetDateTime

data class NoteDocument(
    val documentUri: Uri,
    val filename: String,
    val title: String,
    val body: String,
    val createdAt: OffsetDateTime,
    val modifiedAt: OffsetDateTime,
    val isTrashed: Boolean,
) {
    val id: String = documentUri.toString()

    val displayTitle: String
        get() = title.ifBlank { "Untitled" }

    val bodyPreview: String
        get() = body
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
}

data class EditableNote(
    val documentUri: Uri,
    val filename: String,
    val title: String,
    val body: String,
    val createdAt: OffsetDateTime,
    val modifiedAt: OffsetDateTime,
    val isTrashed: Boolean,
) {
    fun toDocument(): NoteDocument = NoteDocument(
        documentUri = documentUri,
        filename = filename,
        title = title,
        body = body,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        isTrashed = isTrashed,
    )
}

data class ExportResult(
    val fileName: String,
    val documentUri: Uri,
)

data class FolderInfo(
    val displayName: String,
)

data class NoteTextContent(
    val title: String,
    val createdAt: OffsetDateTime,
    val modifiedAt: OffsetDateTime,
    val body: String,
)
