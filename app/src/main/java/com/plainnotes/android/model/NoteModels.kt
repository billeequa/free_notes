package com.plainnotes.android.model

import android.net.Uri
import java.time.OffsetDateTime
import java.time.LocalDate

enum class NoteType { NORMAL, DOUBLE_X_DAY }
enum class NoteCategory { NOTES, JOURNAL }

data class NoteDocument(
    val documentUri: Uri,
    val filename: String,
    val title: String,
    val body: String,
    val createdAt: OffsetDateTime,
    val modifiedAt: OffsetDateTime,
    val isTrashed: Boolean,
    val noteType: NoteType = NoteType.NORMAL,
    val doubleXDate: LocalDate? = null,
    val category: NoteCategory = if (noteType == NoteType.DOUBLE_X_DAY) NoteCategory.JOURNAL else NoteCategory.NOTES,
    val journalDate: LocalDate? = doubleXDate,
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
    val noteType: NoteType = NoteType.NORMAL,
    val doubleXDate: LocalDate? = null,
    val category: NoteCategory = if (noteType == NoteType.DOUBLE_X_DAY) NoteCategory.JOURNAL else NoteCategory.NOTES,
    val journalDate: LocalDate? = doubleXDate,
) {
    fun toDocument(): NoteDocument = NoteDocument(
        documentUri = documentUri,
        filename = filename,
        title = title,
        body = body,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        isTrashed = isTrashed,
        noteType = noteType,
        doubleXDate = doubleXDate,
        category = category,
        journalDate = journalDate,
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
    val noteType: NoteType = NoteType.NORMAL,
    val doubleXDate: LocalDate? = null,
    val category: NoteCategory = if (noteType == NoteType.DOUBLE_X_DAY) NoteCategory.JOURNAL else NoteCategory.NOTES,
    val journalDate: LocalDate? = doubleXDate,
)
