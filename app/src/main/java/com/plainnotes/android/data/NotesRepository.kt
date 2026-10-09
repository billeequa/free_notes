package com.plainnotes.android.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.plainnotes.android.model.EditableNote
import com.plainnotes.android.model.ExportResult
import com.plainnotes.android.model.FolderInfo
import com.plainnotes.android.model.NoteDocument
import com.plainnotes.android.model.NoteTextContent
import com.plainnotes.android.model.NoteType
import com.plainnotes.android.model.NoteCategory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NotesRepository(private val context: Context) {
    private val storageMutex = Mutex()
    private val todoStorageMutex = Mutex()
    private val documents = DocumentDirectory(context.contentResolver)
    private var index: NoteIndex? = null
    private var indexReady = false
    private val _noteIndex = MutableStateFlow<NoteIndexSnapshot?>(null)
    internal val noteIndex = _noteIndex.asStateFlow()
    private val settingsRepository = AppSettingsRepository(context)
    private val contentResolver: ContentResolver = context.contentResolver
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val exportStampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.US)

    fun rootFolderUri(): Flow<Uri?> = settingsRepository.rootFolderUri

    fun themeMode(): Flow<String> = settingsRepository.themeMode

    fun fontScale(): Flow<Float> = settingsRepository.fontScale

    fun noteSortMode(): Flow<String> = settingsRepository.noteSortMode
    fun doubleXEnabled(): Flow<Boolean> = settingsRepository.doubleXEnabled
    fun showReader(): Flow<Boolean> = settingsRepository.showReader
    suspend fun setDoubleXEnabled(enabled: Boolean) = settingsRepository.setDoubleXEnabled(enabled)
    suspend fun setShowReader(enabled: Boolean) = settingsRepository.setShowReader(enabled)

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

    /** This path reads only the private index; it never contacts the document provider. */
    internal suspend fun loadCachedNotes(folderUri: Uri): NoteIndexSnapshot? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val cached = indexFor(folderUri)
            if (indexReady) cached.snapshot().also { _noteIndex.value = it } else null
        }
    }

    suspend fun listActiveNotes(): List<NoteDocument> = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val root = rootDirectoryOrNull() ?: return@withContext emptyList()
            reconcileIndex(root)
            index!!.snapshot().notes
        }
    }

    suspend fun listTrashedNotes(): List<NoteDocument> = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val trash = trashDirectoryOrNull() ?: return@withContext emptyList()
            documents.children(trash).filter { it.isNote }.mapNotNull { entry ->
                readSummary(entry, true)
            }.sortedByDescending { it.modifiedAt }
        }
    }

    private fun indexFor(folderUri: Uri): NoteIndex {
        val key = if (android.provider.DocumentsContract.isTreeUri(folderUri))
            android.provider.DocumentsContract.buildTreeDocumentUri(folderUri.authority,
                android.provider.DocumentsContract.getTreeDocumentId(folderUri)).toString()
            else folderUri.toString()
        if (index?.folderUri != key) {
            index = NoteIndex(java.io.File(context.filesDir, "note-index"), key, "PlainNotes")
            indexReady = index!!.load()
        }
        return index!!
    }

    private fun publishIndex() {
        if (indexReady) {
            index!!.persist()
            _noteIndex.value = index!!.snapshot()
        }
    }

    private fun reconcileIndex(root: DocumentFile) {
        val cached = indexFor(root.uri)
        val children = documents.children(root).filter { it.isNote }
        val time = System.currentTimeMillis()
        val present = children.mapTo(hashSetOf()) { it.uri.toString() }
        children.forEach { entry ->
            val previous = cached.entries[entry.uri.toString()]
            if (previous == null || !previous.matches(entry, time) || hasRecovery(entry.uri)) {
                readSummary(entry, false)?.let {
                    cached.put(IndexedNote(it, entry.modifiedMillis, entry.size, time))
                }
            }
        }
        cached.entries.keys.filter { it !in present }.forEach(cached::remove)
        cached.folderName = root.name ?: cached.folderName
        indexReady = true
        publishIndex()
    }

    private fun recordNote(note: EditableNote, previousUri: Uri? = null) {
        val cached = index ?: return
        if (previousUri != null && previousUri != note.documentUri) cached.remove(previousUri.toString())
        val stamp = runCatching { documents.stat(note.documentUri) }.getOrNull()
        cached.put(IndexedNote(note.toDocument(), stamp?.modifiedMillis, stamp?.size, System.currentTimeMillis()))
        publishIndex()
    }

    suspend fun createBlankNote(): EditableNote = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val root = requireRootDirectory()
            indexFor(root.uri)
            val now = now().withNano(0)
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
            editableFromContent(file.uri, file.name ?: fileName, content, false).also { recordNote(it) }
        }
    }

    suspend fun createJournalNote(): EditableNote = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val root = requireRootDirectory()
            ensureIndex(root)
            createJournalFile(root, LocalDate.now(), index!!.snapshot().notes)
        }
    }

    private fun ensureIndex(root: DocumentFile) {
        indexFor(root.uri)
        if (!indexReady) reconcileIndex(root)
    }

    private fun createJournalFile(root: DocumentFile, date: LocalDate, notes: List<NoteDocument>): EditableNote {
        val now = now().withNano(0)
        val title = JournalEntries.nextTitle(date, notes.map { it.title })
        val filename = uniqueFileName(root, "${fileStampFormatter.format(now)}-${slugify(title)}.txt")
        val file = root.createFile(TEXT_MIME_TYPE, filename) ?: throw IOException("Unable to create journal entry.")
        val content = NoteTextContent(title, now, now, "", category = NoteCategory.JOURNAL, journalDate = date)
        writeText(file.uri, NoteFileParser.serialize(content))
        return editableFromContent(file.uri, file.name ?: filename, content, false).also { recordNote(it) }
    }

    /** Serialized with other note writes; repeated opens reuse the same daily journal. */
    suspend fun findOrCreateDoubleXNote(date: LocalDate): EditableNote = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val root = requireRootDirectory()
            ensureIndex(root)
            val notes = index!!.snapshot().notes
            val existing = index!!.doubleXDates[date]?.let { index!!.entries[it]?.note }
            val dailySummary = existing ?: notes.filter { it.category == NoteCategory.JOURNAL && it.journalDate == date }
                .minWithOrNull(compareBy<NoteDocument> { it.createdAt.toInstant() }
                    .thenBy { JournalEntries.sequenceNumber(it.title) })
            val daily = if (dailySummary == null) createJournalFile(root, date, notes) else
                readEditableNote(DocumentFile.fromSingleUri(context, dailySummary.documentUri)
                    ?: throw IOException("Unable to open journal entry."), false)
                    ?: throw IOException("Unable to open journal entry.")
            val updated = withDoubleXTemplate(daily.copy(
                title = if (existing != null) daily.title else JournalEntries.doubleXTitle(date, daily.title),
                modifiedAt = if (daily.noteType == NoteType.DOUBLE_X_DAY) daily.modifiedAt else now(),
                noteType = NoteType.DOUBLE_X_DAY, doubleXDate = date,
                category = NoteCategory.JOURNAL, journalDate = date,
            ), root)
            writeText(updated.documentUri, NoteFileParser.serialize(updated.textContent()))
            updated.also { recordNote(it) }
        }
    }

    suspend fun setNoteCategory(uriString: String, category: NoteCategory): EditableNote {
        val note = loadEditableNote(uriString) ?: throw IOException("The note could not be found.")
        return saveNote(note.copy(
            category = category,
            journalDate = note.journalDate ?: if (category == NoteCategory.JOURNAL)
                JournalEntries.dateFromTitle(note.title) ?: note.createdAt.toLocalDate() else null,
        ))
    }

    suspend fun hasDoubleXNote(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            ensureIndex(requireRootDirectory())
            index!!.doubleXDates.containsKey(date)
        }
    }

    suspend fun loadEditableNote(uriString: String): EditableNote? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val file = DocumentFile.fromSingleUri(context, Uri.parse(uriString)) ?: return@withContext null
            val original = readEditableNote(file, isTrashed = false) ?: return@withContext null
            // Upgrade old Double X entries only when opened, under the same
            // write lock. Persist the marker before editing so reopening never
            // duplicates the template or replaces user-edited headings.
            val updated = if (original.noteType == NoteType.DOUBLE_X_DAY && original.doubleXTemplateVersion < 1)
                withDoubleXTemplate(original, requireRootDirectory()) else original
            if (updated != original) writeText(file.uri, NoteFileParser.serialize(updated.textContent()))
            indexFor(requireRootDirectory().uri)
            updated.also { recordNote(it) }
        }
    }

    suspend fun saveNote(note: EditableNote): EditableNote = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val updated = note.copy(modifiedAt = now())
            val directory = if (updated.isTrashed) requireTrashDirectory() else requireRootDirectory()
            if (!updated.isTrashed) indexFor(directory.uri)
            val file = resolveCurrentFile(updated, directory)
                ?: throw IOException("Note file is no longer available.")
            val target = prepareSaveTarget(
                file = file,
                directory = directory,
                note = updated,
            )
            val serialized = NoteFileParser.serialize(
                NoteTextContent(
                    title = updated.title,
                    createdAt = updated.createdAt,
                    modifiedAt = updated.modifiedAt,
                    body = updated.body,
                    noteType = updated.noteType,
                    doubleXDate = updated.doubleXDate,
                    category = updated.category,
                    journalDate = updated.journalDate,
                    doubleXTemplateVersion = updated.doubleXTemplateVersion,
                ),
            )
            writeText(
                target.file.uri,
                serialized,
            )
            target.previousFileToDelete?.let { previous ->
                if (previous.exists()) {
                    previous.delete()
                }
            }
            // writeText already verified the complete bytes. Do not reread them twice more.
            val saved = updated.copy(documentUri = target.file.uri, filename = target.file.name ?: updated.filename,
                createdAt = updated.createdAt.withNano(0), modifiedAt = updated.modifiedAt.withNano(0))
            if (!saved.isTrashed) recordNote(saved, note.documentUri)
            saved
        }
    }

    suspend fun renameNote(uriString: String, newTitle: String): EditableNote =
        withContext(Dispatchers.IO) {
            val note = loadEditableNote(uriString)
                ?: throw IOException("The note could not be found.")
            saveNote(note.copy(title = newTitle))
        }

    suspend fun moveToTrash(uriString: String) {
        withContext(Dispatchers.IO) {
            storageMutex.withLock {
            val source = DocumentFile.fromSingleUri(context, Uri.parse(uriString))
                ?: throw IOException("The note could not be found.")
            val trash = requireTrashDirectory()
            indexFor(requireRootDirectory().uri)
            copyDocumentToDirectory(source, trash)
            if (!source.delete()) {
                throw IOException("The note could not be deleted after copying to trash.")
            }
            index!!.remove(uriString)
            publishIndex()
            }
        }
    }

    suspend fun restoreFromTrash(uriString: String) {
        withContext(Dispatchers.IO) {
            storageMutex.withLock {
            val source = DocumentFile.fromSingleUri(context, Uri.parse(uriString))
                ?: throw IOException("The trashed note could not be found.")
            val original = readEditableNote(source, true) ?: throw IOException("Unable to read the trashed note.")
            val date = original.doubleXDate
            val root = requireRootDirectory()
            ensureIndex(root)
            if (date != null && index!!.doubleXDates.containsKey(date)) {
                throw IOException("A Double X Day note already exists for $date.")
            }
            val restored = copyDocumentToDirectory(source, root)
            if (!source.delete()) {
                throw IOException("The trashed note could not be removed after restoring it.")
            }
            recordNote(original.copy(documentUri = restored.uri, filename = restored.name ?: original.filename, isTrashed = false))
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
        storageMutex.withLock {
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
                    .filter { it.isFile && (isNoteFile(it) || it.name == TodoFileParser.FILE_NAME) }
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
    }

    suspend fun loadTodos(): List<TodoItem> = withContext(Dispatchers.IO) {
        todoStorageMutex.withLock {
            readTodos(requireRootDirectory())
        }
    }

    private fun readTodos(root: DocumentFile): List<TodoItem> {
        val file = safeFindFile(root, TodoFileParser.FILE_NAME) ?: return emptyList()
        return TodoFileParser.parse(readRecoverableText(file.uri) ?: throw IOException("Unable to read the to-do list."))
    }

    private suspend fun withDoubleXTemplate(note: EditableNote, root: DocumentFile): EditableNote {
        if (note.doubleXTemplateVersion >= 1 || note.noteType != NoteType.DOUBLE_X_DAY || note.doubleXDate == null) return note
        val content = DoubleXDay.withTemplate(note.textContent(), todoStorageMutex.withLock { readTodos(root) })
        return note.copy(body = content.body, doubleXTemplateVersion = content.doubleXTemplateVersion)
    }

    private fun EditableNote.textContent() = NoteTextContent(
        title, createdAt, modifiedAt, body, noteType, doubleXDate, category, journalDate, doubleXTemplateVersion,
    )

    suspend fun saveTodos(items: List<TodoItem>) = withContext(Dispatchers.IO) {
        todoStorageMutex.withLock {
            val root = requireRootDirectory()
            val file = safeFindFile(root, TodoFileParser.FILE_NAME)
                ?: root.createFile(TEXT_MIME_TYPE, TodoFileParser.FILE_NAME)
                ?: throw IOException("Unable to create the to-do list.")
            writeText(file.uri, TodoFileParser.serialize(items))
        }
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
        if (file.name == TodoFileParser.FILE_NAME) return false
        val lowerName = file.name?.lowercase(Locale.US).orEmpty()
        return lowerName.endsWith(".txt") || file.type == TEXT_MIME_TYPE
    }

    /** Headers plus the first meaningful body line suffice for the legacy title fallback. */
    private fun readSummary(entry: DocumentEntry, trashed: Boolean): NoteDocument? {
        if (hasRecovery(entry.uri)) return readEditableNote(entry, trashed)?.toDocument()
        val prefix = contentResolver.openInputStream(entry.uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
            buildString {
                var separatorSeen = false
                var titleSeen = false
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line); append('\n')
                    if (line.startsWith("Title:", ignoreCase = true)) titleSeen = true
                    if (line.isBlank() && titleSeen) break
                    if (separatorSeen && line.isNotBlank()) break
                    if (line.isBlank()) separatorSeen = true
                    // A headerless note's first line is already its inferred title.
                    if (!separatorSeen && !NoteFileParser.isMetadataLine(line)) break
                }
            }
        } ?: return null
        val parsed = NoteFileParser.parse(prefix, entry.name, entry.modifiedMillis ?: 0L, now())
        return editableFromContent(entry.uri, entry.name, parsed, trashed).toDocument()
    }

    private fun readEditableNote(file: DocumentFile, isTrashed: Boolean): EditableNote? =
        documents.stat(file.uri)?.let { readEditableNote(it, isTrashed) }

    private fun readEditableNote(entry: DocumentEntry, isTrashed: Boolean): EditableNote? {
        val text = readRecoverableText(entry.uri) ?: return null
        val parsed = NoteFileParser.parse(text, entry.name, entry.modifiedMillis ?: 0L, now())
        return editableFromContent(entry.uri, entry.name, parsed, isTrashed)
    }

    private fun editableFromContent(uri: Uri, filename: String, content: NoteTextContent, trashed: Boolean) =
        EditableNote(uri, filename, content.title, content.body, content.createdAt,
            content.modifiedAt, trashed, content.noteType, content.doubleXDate,
            content.category, content.journalDate, content.doubleXTemplateVersion)

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(StandardCharsets.UTF_8).readText()
        }
    }.getOrNull()

    private fun recoveryFile(uri: Uri): android.util.AtomicFile {
        val directory = java.io.File(context.filesDir, "pending-writes").apply { mkdirs() }
        val key = java.util.UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString()
        return android.util.AtomicFile(java.io.File(directory, "$key.txt"))
    }

    private fun hasRecovery(uri: Uri): Boolean {
        val recovery = recoveryFile(uri)
        return recovery.baseFile.exists() || java.io.File(recovery.baseFile.path + ".bak").exists()
    }

    private fun readRecoverableText(uri: Uri): String? {
        val recovery = recoveryFile(uri)
        if (recovery.baseFile.exists() || java.io.File(recovery.baseFile.path + ".bak").exists()) {
            val pending = recovery.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            writeText(uri, pending)
            return pending
        }
        return readText(uri)
    }

    private fun writeText(uri: Uri, text: String) {
        // SAF providers do not offer atomic replacement. Keep the attempted write locally
        // until read-back succeeds, so an interrupted/provider-failed write is recoverable.
        val recovery = recoveryFile(uri)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val stream = recovery.startWrite()
        try {
            stream.write(bytes)
            recovery.finishWrite(stream)
        } catch (error: Exception) {
            recovery.failWrite(stream)
            throw error
        }
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: throw IOException("Unable to open the file for writing. A recovery copy is kept on this device.")
        if (readText(uri) != text) throw IOException("Save verification failed. A recovery copy is kept on this device.")
        recovery.delete()
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
        directory: DocumentFile,
    ): DocumentFile? {
        val byUri = DocumentFile.fromSingleUri(context, note.documentUri)
        if (byUri != null && byUri.exists()) return byUri
        val byName = note.filename.takeIf { it.isNotBlank() }?.let { safeFindFile(directory, it) }
        if (byName != null) {
            return byName
        }
        return DocumentFile.fromSingleUri(context, note.documentUri)
    }

    private fun prepareSaveTarget(
        file: DocumentFile,
        directory: DocumentFile,
        note: EditableNote,
    ): SaveTarget {
        val currentName = file.name ?: return SaveTarget(file)
        if (currentName == desiredFileName(note)) return SaveTarget(file)
        val desiredName = uniqueFileName(
            directory = directory,
            preferredName = desiredFileName(note),
            excludingName = currentName,
        )
        if (currentName == desiredName) {
            return SaveTarget(file)
        }

        val replacement = directory.createFile(TEXT_MIME_TYPE, desiredName)
            ?: throw IOException("Unable to create the renamed note file.")
        return SaveTarget(
            file = replacement,
            previousFileToDelete = file,
        )
    }

    private fun desiredFileName(note: EditableNote): String {
        return "${fileStampFormatter.format(note.createdAt)}-${slugify(note.title)}.txt"
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

        val names = documents.children(directory).mapTo(hashSetOf()) { it.name }
        var candidate = normalized
        var counter = 2
        while (candidate in names && candidate != excludingName) {
            candidate = "$baseName-$counter$extension"
            counter += 1
        }
        return candidate
    }

    private fun safeFindFile(directory: DocumentFile, name: String): DocumentFile? {
        val entry = documents.children(directory).firstOrNull { it.name == name } ?: return null
        return if (entry.uri.scheme == "file") DocumentFile.fromFile(java.io.File(entry.uri.path!!))
            else if (entry.isDirectory) directory.listFiles().firstOrNull { it.uri == entry.uri }
            else DocumentFile.fromSingleUri(context, entry.uri)
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

    private data class SaveTarget(
        val file: DocumentFile,
        val previousFileToDelete: DocumentFile? = null,
    )
}
