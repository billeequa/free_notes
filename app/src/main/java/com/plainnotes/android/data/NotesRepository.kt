package com.plainnotes.android.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.plainnotes.android.model.EditableNote
import com.plainnotes.android.model.ExportResult
import com.plainnotes.android.model.FolderInfo
import com.plainnotes.android.model.NoteDocument
import com.plainnotes.android.model.NoteTextContent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class NotesRepository(private val context: Context) {
    private val settingsRepository = AppSettingsRepository(context)
    private val contentResolver: ContentResolver = context.contentResolver
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val exportStampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.US)

    fun rootFolderUri(): Flow<Uri?> = settingsRepository.rootFolderUri

    fun themeMode(): Flow<String> = settingsRepository.themeMode

    fun fontScale(): Flow<Float> = settingsRepository.fontScale

    fun noteSortMode(): Flow<String> = settingsRepository.noteSortMode

    suspend fun persistRootFolder(uri: Uri) {
        withContext(Dispatchers.IO) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settingsRepository.setRootFolderUri(uri)
            ensureAppStructure()
        }
    }

    suspend fun setThemeMode(themeMode: String) {
        settingsRepository.setThemeMode(themeMode)
    }

    suspend fun setFontScale(scale: Float) {
        settingsRepository.setFontScale(scale)
    }

    suspend fun setNoteSortMode(sortMode: String) {
        settingsRepository.setNoteSortMode(sortMode)
    }

    suspend fun ensureAppStructure() {
        withContext(Dispatchers.IO) {
            val root = requireRootDirectory()
            ensureDirectory(root, TRASH_DIRECTORY_NAME)
            ensureDirectory(root, EXPORTS_DIRECTORY_NAME)
        }
    }

    suspend fun getFolderInfo(): FolderInfo? = withContext(Dispatchers.IO) {
        rootDirectoryOrNull()?.let { FolderInfo(displayName = it.name ?: "PlainNotes") }
    }

    suspend fun listActiveNotes(): List<NoteDocument> = withContext(Dispatchers.IO) {
        val root = rootDirectoryOrNull() ?: return@withContext emptyList()
        root.listFiles()
            .asSequence()
            .filter { it.isFile && isNoteFile(it) && it.name != TodoFileParser.FILE_NAME }
            .mapNotNull { readNoteDocument(it, isTrashed = false) }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    suspend fun listTrashedNotes(): List<NoteDocument> = withContext(Dispatchers.IO) {
        val trashDirectory = trashDirectoryOrNull() ?: return@withContext emptyList()
        trashDirectory.listFiles()
            .asSequence()
            .filter { it.isFile && isNoteFile(it) && it.name != TodoFileParser.FILE_NAME }
            .mapNotNull { readNoteDocument(it, isTrashed = true) }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    suspend fun createBlankNote(): EditableNote = withContext(Dispatchers.IO) {
        val root = requireRootDirectory()
        val now = now()
        val fileName = uniqueFileName(
            directory = root,
            preferredName = "${fileStampFormatter.format(now)}-${slugify("")}.txt",
        )
        val file = root.createFile(TEXT_MIME_TYPE, fileName)
            ?: throw IOException("Unable to create note file.")
        val content = NoteTextContent(
            title = "",
            createdAt = now,
            modifiedAt = now,
            body = "",
        )
        writeText(file.uri, NoteFileParser.serialize(content))
        readEditableNote(file, isTrashed = false)
            ?: throw IOException("Unable to read the note after creating it.")
    }

    suspend fun loadEditableNote(uriString: String): EditableNote? = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uriString)) ?: return@withContext null
        val original = readEditableNote(file, isTrashed = false) ?: return@withContext null
        val draft = draftFile(file.uri)
        if (!draft.baseFile.exists()) return@withContext original
        val parsed = NoteFileParser.parse(
            draft.openRead().use { it.readBytes().toString(StandardCharsets.UTF_8) },
            original.filename, file.lastModified(), now(),
        )
        // Recover a failed provider write on the next open.
        original.copy(title = parsed.title, body = parsed.body, modifiedAt = parsed.modifiedAt)
    }

    suspend fun saveNote(note: EditableNote): EditableNote = withContext(Dispatchers.IO) {
        val updated = note.copy(modifiedAt = now())
        val file = resolveCurrentFile(updated)
            ?: throw IOException("Note file is no longer available.")
        val serialized = NoteFileParser.serialize(
            NoteTextContent(
                title = updated.title,
                createdAt = updated.createdAt,
                modifiedAt = updated.modifiedAt,
                body = updated.body,
            ),
        )
        // Keep the URI and filename stable while typing. SAF renames can invalidate both.
        val draft = draftFile(file.uri)
        val output = draft.startWrite()
        try {
            output.write(serialized.toByteArray(StandardCharsets.UTF_8))
            draft.finishWrite(output)
        } catch (error: Exception) {
            draft.failWrite(output)
            throw error
        }
        writeText(file.uri, serialized)
        if (readText(file.uri) != serialized) throw IOException("The saved note could not be verified. Your draft is kept on this device; retry saving.")
        draft.delete()
        updated.copy(documentUri = file.uri, filename = file.name ?: updated.filename)
    }

    private fun draftFile(uri: Uri): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir, "note-drafts/$key.txt").also { it.parentFile?.mkdirs() })
    }

    suspend fun hasPendingDraft(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        draftFile(uri).baseFile.exists()
    }

    suspend fun loadTodos(): List<TodoItem> = withContext(Dispatchers.IO) {
        val root = requireRootDirectory()
        val draft = draftFile(root.uri)
        if (draft.baseFile.exists()) {
            return@withContext TodoFileParser.parse(draft.openRead().use { it.readBytes().toString(StandardCharsets.UTF_8) })
        }
        val file = root.findFile(TodoFileParser.FILE_NAME) ?: return@withContext emptyList()
        TodoFileParser.parse(readText(file.uri) ?: throw IOException("Unable to read the to-do list."))
    }

    suspend fun saveTodos(items: List<TodoItem>) = withContext(Dispatchers.IO) {
        val root = requireRootDirectory()
        val serialized = TodoFileParser.serialize(items)
        val draft = draftFile(root.uri)
        val output = draft.startWrite()
        try {
            output.write(serialized.toByteArray(StandardCharsets.UTF_8))
            draft.finishWrite(output)
        } catch (error: Exception) {
            draft.failWrite(output)
            throw error
        }
        val file = root.findFile(TodoFileParser.FILE_NAME)
            ?: root.createFile(TEXT_MIME_TYPE, TodoFileParser.FILE_NAME)
            ?: throw IOException("Unable to create the to-do file.")
        writeText(file.uri, serialized)
        if (readText(file.uri) != serialized) throw IOException("Unable to verify the to-do file. A local draft is kept; tap Retry.")
        draft.delete()
    }

    suspend fun renameNote(uriString: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            val note = loadEditableNote(uriString)
                ?: throw IOException("The note could not be found.")
            saveNote(note.copy(title = newTitle))
        }
    }

    suspend fun moveToTrash(uriString: String) {
        withContext(Dispatchers.IO) {
            val source = DocumentFile.fromSingleUri(context, Uri.parse(uriString))
                ?: throw IOException("The note could not be found.")
            val trash = requireTrashDirectory()
            copyDocumentToDirectory(source, trash)
            if (!source.delete()) {
                throw IOException("The note could not be deleted after copying to trash.")
            }
        }
    }

    suspend fun restoreFromTrash(uriString: String) {
        withContext(Dispatchers.IO) {
            val source = DocumentFile.fromSingleUri(context, Uri.parse(uriString))
                ?: throw IOException("The trashed note could not be found.")
            val root = requireRootDirectory()
            copyDocumentToDirectory(source, root)
            if (!source.delete()) {
                throw IOException("The trashed note could not be removed after restoring it.")
            }
        }
    }

    suspend fun deletePermanently(uriString: String) {
        withContext(Dispatchers.IO) {
            val file = DocumentFile.fromSingleUri(context, Uri.parse(uriString))
                ?: throw IOException("The trashed note could not be found.")
            if (!file.delete()) {
                throw IOException("The note could not be deleted.")
            }
        }
    }

    suspend fun exportActiveNotes(): ExportResult = withContext(Dispatchers.IO) {
        val root = requireRootDirectory()
        val exportsDirectory = requireExportsDirectory()
        val exportName = "notes-export-${exportStampFormatter.format(now())}.zip"
        val exportFile = exportsDirectory.createFile(ZIP_MIME_TYPE, exportName)
            ?: throw IOException("Unable to create the export zip.")

        ZipOutputStream(
            BufferedOutputStream(
                contentResolver.openOutputStream(exportFile.uri)
                    ?: throw IOException("Unable to open the export zip for writing."),
            ),
        ).use { zipStream ->
            root.listFiles()
                .asSequence()
                .filter { it.isFile && isNoteFile(it) }
                .sortedBy { it.name ?: "" }
                .forEach { file ->
                    val entryName = file.name ?: "note.txt"
                    zipStream.putNextEntry(ZipEntry(entryName))
                    BufferedInputStream(
                        contentResolver.openInputStream(file.uri)
                            ?: throw IOException("Unable to read ${file.name}."),
                    ).use { input ->
                        input.copyTo(zipStream)
                    }
                    zipStream.closeEntry()
                }
        }

        ExportResult(
            fileName = exportFile.name ?: exportName,
            documentUri = exportFile.uri,
        )
    }

    private suspend fun rootDirectoryOrNull(): DocumentFile? {
        val uri = settingsRepository.rootFolderUri.first() ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    private suspend fun requireRootDirectory(): DocumentFile {
        return rootDirectoryOrNull()
            ?: throw IOException("Choose a notes folder before using the app.")
    }

    private suspend fun trashDirectoryOrNull(): DocumentFile? {
        return rootDirectoryOrNull()?.let { safeFindFile(it, TRASH_DIRECTORY_NAME) }
    }

    private suspend fun requireTrashDirectory(): DocumentFile {
        return ensureDirectory(requireRootDirectory(), TRASH_DIRECTORY_NAME)
    }

    private suspend fun requireExportsDirectory(): DocumentFile {
        return ensureDirectory(requireRootDirectory(), EXPORTS_DIRECTORY_NAME)
    }

    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        return safeFindFile(parent, name)
            ?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IOException("Unable to create the $name directory.")
    }

    private fun isNoteFile(file: DocumentFile): Boolean {
        val lowerName = file.name?.lowercase(Locale.US).orEmpty()
        return lowerName.endsWith(".txt") || file.type == TEXT_MIME_TYPE
    }

    private fun readEditableNote(file: DocumentFile, isTrashed: Boolean): EditableNote? {
        val document = readNoteDocument(file, isTrashed) ?: return null
        return EditableNote(
            documentUri = document.documentUri,
            filename = document.filename,
            title = document.title,
            body = document.body,
            createdAt = document.createdAt,
            modifiedAt = document.modifiedAt,
            isTrashed = document.isTrashed,
        )
    }

    private fun readNoteDocument(file: DocumentFile, isTrashed: Boolean): NoteDocument? {
        val text = readText(file.uri) ?: return null
        val parsed = NoteFileParser.parse(
            rawText = text,
            fallbackFileName = file.name,
            fallbackLastModifiedMillis = file.lastModified(),
            now = now(),
        )
        return NoteDocument(
            documentUri = file.uri,
            filename = file.name ?: "note.txt",
            title = parsed.title,
            body = parsed.body,
            createdAt = parsed.createdAt,
            modifiedAt = parsed.modifiedAt,
            isTrashed = isTrashed,
        )
    }

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
    }.getOrNull()

    private fun writeText(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
        } ?: throw IOException("Unable to open the note file for writing.")
    }

    private fun copyDocumentToDirectory(source: DocumentFile, targetDirectory: DocumentFile): DocumentFile {
        val targetName = uniqueFileName(
            directory = targetDirectory,
            preferredName = source.name ?: "note.txt",
        )
        val target = targetDirectory.createFile(source.type ?: TEXT_MIME_TYPE, targetName)
            ?: throw IOException("Unable to copy ${source.name}.")

        val input = contentResolver.openInputStream(source.uri)
            ?: throw IOException("Unable to read ${source.name}.")
        val output = contentResolver.openOutputStream(target.uri)
            ?: throw IOException("Unable to write ${target.name}.")
        input.use { sourceStream ->
            output.use { targetStream ->
                sourceStream.copyTo(targetStream)
            }
        }

        return target
    }

    private fun resolveCurrentFile(
        note: EditableNote,
    ): DocumentFile? {
        return DocumentFile.fromSingleUri(context, note.documentUri)?.takeIf { it.exists() }
    }

    private fun uniqueFileName(
        directory: DocumentFile,
        preferredName: String,
        excludingName: String? = null,
    ): String {
        val normalized = preferredName.ifBlank { "note.txt" }
        val dotIndex = normalized.lastIndexOf('.')
        val baseName = if (dotIndex > 0) normalized.substring(0, dotIndex) else normalized
        val extension = if (dotIndex > 0) normalized.substring(dotIndex) else ""

        var candidate = normalized
        var counter = 2
        while (safeFindFile(directory, candidate) != null && candidate != excludingName) {
            candidate = "$baseName-$counter$extension"
            counter += 1
        }
        return candidate
    }

    private fun safeFindFile(directory: DocumentFile, name: String): DocumentFile? {
        return runCatching { directory.findFile(name) }.getOrNull()
    }

    private fun slugify(title: String): String {
        val base = title
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)

        return if (base.isBlank()) "untitled" else base
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneId.systemDefault())

    companion object {
        private const val TEXT_MIME_TYPE = "text/plain"
        private const val ZIP_MIME_TYPE = "application/zip"
        private const val TRASH_DIRECTORY_NAME = "Trash"
        private const val EXPORTS_DIRECTORY_NAME = "Exports"
        private const val MAX_SLUG_LENGTH = 40
    }

}
