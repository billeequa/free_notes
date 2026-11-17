package com.plainnotes.android.reader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.plainnotes.android.data.DocumentDirectory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** A private original and durable journal make interrupted provider writes recoverable. */
internal class BookEdits(
    private val context: Context,
    private val cache: SharedPreferences,
    private val key: (Uri) -> String,
) {
    private val directory = File(context.filesDir, "book-edits")
    private val resolver get() = context.contentResolver
    private val documents = DocumentDirectory(context.contentResolver)

    fun save(book: BookEntry, stem: String, title: String, author: String): Uri {
        recover()
        val filename = EpubMetadata.filename(stem, book.filename)
        val metadataChanged = title.trim() != book.title || author.trim() != book.author
        if (metadataChanged) require(title.isNotBlank()) { "Enter a title to save EPUB metadata." }
        val parent = DocumentFile.fromTreeUri(context, book.parentUri) ?: throw IOException("The books folder is unavailable.")
        val source = parent.findFile(book.filename) ?: throw IOException("The book moved. Refresh the library and try again.")
        require(source.uri == book.uri && stamp(source.uri) == book.sourceStamp) { "The book changed. Refresh the library and try again." }
        val duplicate = parent.findFile(filename)
        require(duplicate == null || duplicate.uri == source.uri) { "Another book already uses that filename." }
        if (!metadataChanged && filename == book.filename) return source.uri

        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val original = File(directory, "$id.original.epub")
        val edited = File(directory, "$id.edited.epub")
        val journalFile = File(directory, "$id.json")
        var journal: JSONObject? = null
        try {
            copyFrom(source.uri, original)
            val originalHash = hash(original)
            if (metadataChanged) EpubMetadata.rewrite(original, edited, title.trim(), author.trim())
            val newHash = if (metadataChanged) hash(edited) else originalHash
            require(hash(source.uri) == originalHash) { "The book changed while saving. Refresh the library and try again." }
            val oldKey = key(book.uri)
            journal = JSONObject().put("id", id).put("phase", "pending")
                .put("parent", book.parentUri.toString()).put("old_uri", book.uri.toString())
                .put("old_name", book.filename).put("new_name", filename)
                .put("original_hash", originalHash).put("new_hash", newHash)
                .put("metadata_changed", metadataChanged).put("title", title.trim()).put("author", author.trim())
                .put("old_meta", JSONObject(cache.getString("meta:$oldKey", "{}")!!))
                .put("locator", cache.getString("locator:$oldKey", null) ?: JSONObject.NULL)
                .put("progress", cache.getFloat("progress:$oldKey", 0f).toDouble())
                .put("last", cache.getLong("last:$oldKey", 0L))
            writeJournal(journalFile, journal)
            if (metadataChanged) {
                writeTo(source.uri, edited)
                require(hash(source.uri) == newHash) { "The saved EPUB could not be verified." }
            }
            if (filename != book.filename && !source.renameTo(filename)) throw IOException("This books folder could not rename the EPUB.")
            // Providers may change the URI when renaming. The journal can also resolve
            // either name if the process stops between rename and this journal write.
            journal.put("new_uri", source.uri.toString()).put("phase", "committed")
            writeJournal(journalFile, journal)
            migrate(journal, source.uri, updated = true)
            cleanup(journalFile, id)
            return source.uri
        } catch (error: Exception) {
            if (journal != null) {
                try {
                    recover(journalFile, journal)
                    if (journal.optString("phase") == "committed") return source.uri
                }
                catch (recovery: Exception) {
                    throw IOException("The save was interrupted. The original EPUB is backed up; allow access to its books folder and refresh to recover it.", recovery)
                }
            } else { original.delete(); edited.delete() }
            throw error
        }
    }

    fun recover() {
        if (!directory.exists()) return
        directory.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { File(directory, it.name.removeSuffix(".bak")) }.distinct().forEach { file ->
                val journal = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
                recover(file, journal)
            }
    }

    private fun recover(file: File, journal: JSONObject) {
        val id = journal.getString("id")
        require(UUID.fromString(id).toString() == id && file.name == "$id.json")
        val parent = DocumentFile.fromTreeUri(context, Uri.parse(journal.getString("parent")))
            ?: throw IOException("Allow access to the books folder to finish recovering the EPUB.")
        val old = parent.findFile(journal.getString("old_name"))
        val renamed = parent.findFile(journal.getString("new_name"))
        // A same-name edit uses the original object. Otherwise a successful rename
        // leaves only the target name. Do not overwrite an unrelated colliding file.
        val target = old ?: renamed ?: throw IOException("The edited book moved. Its original backup is retained.")
        if (journal.getString("phase") == "committed") {
            require(hash(target.uri) == journal.getString("new_hash")) { "The edited book changed; its original backup is retained." }
            migrate(journal, target.uri, updated = true)
        } else {
            val original = File(directory, "$id.original.epub")
            require(original.exists() && hash(original) == journal.getString("original_hash")) { "The original EPUB backup needs recovery." }
            val actual = hash(target.uri)
            // A provider write may have stopped at any byte. Its original-name URI is
            // owned by this transaction; a renamed candidate must have known content.
            if (old == null) require(actual in listOf(journal.getString("original_hash"), journal.getString("new_hash"))) {
                "Another book uses the destination name. The original backup is retained."
            }
            if (actual != journal.getString("original_hash")) writeTo(target.uri, original)
            require(hash(target.uri) == journal.getString("original_hash")) { "The original EPUB could not be restored." }
            if (target.name != journal.getString("old_name") && !target.renameTo(journal.getString("old_name")))
                throw IOException("The original EPUB could not regain its filename; its backup is retained.")
            migrate(journal, target.uri, updated = false)
        }
        cleanup(file, id)
    }

    private fun migrate(journal: JSONObject, uri: Uri, updated: Boolean) {
        val oldUri = Uri.parse(journal.getString("old_uri"))
        val metadata = JSONObject(journal.getJSONObject("old_meta").toString())
        if (updated) metadata.put("title", journal.getString("title")).put("author", journal.getString("author"))
        metadata.put("stamp", stamp(uri)).put("metadata_version", 2)
        val oldKey = key(oldUri); val newKey = key(uri)
        val editor = cache.edit()
        if (oldKey != newKey) listOf("meta:", "locator:", "progress:", "last:").forEach { editor.remove(it + oldKey) }
        editor.putString("meta:$newKey", metadata.toString())
            .putFloat("progress:$newKey", journal.getDouble("progress").toFloat()).putLong("last:$newKey", journal.getLong("last"))
        if (!journal.isNull("locator")) editor.putString("locator:$newKey", journal.getString("locator"))
        check(editor.commit()) { "The book’s reading position could not be saved. Its recovery backup is retained." }
    }

    private fun cleanup(journal: File, id: String) {
        // Delete the journal first: a committed user file must never be rolled back
        // just because removal of a now-unneeded private backup was interrupted.
        AtomicFile(journal).delete()
        File(directory, "$id.original.epub").delete()
        File(directory, "$id.edited.epub").delete()
    }
    private fun writeJournal(file: File, journal: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(journal.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun copyFrom(uri: Uri, file: File) {
        (resolver.openInputStream(uri) ?: throw IOException("Cannot read the EPUB.")).use { input ->
            file.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
        }
    }
    private fun writeTo(uri: Uri, file: File) {
        (resolver.openOutputStream(uri, "rwt") ?: throw IOException("Cannot write to the EPUB. Allow changes to its books folder.")).use { output ->
            file.inputStream().use { it.copyTo(output) }
        }
    }
    private fun stamp(uri: Uri): String = documents.stat(uri)?.let { "${it.modifiedMillis}:${it.size}" }
        ?: throw IOException("Cannot read the EPUB’s file information.")
    private fun hash(uri: Uri): String = (resolver.openInputStream(uri) ?: throw IOException("Cannot read the EPUB.")).use(::hash)
    private fun hash(file: File): String = file.inputStream().use(::hash)
    private fun hash(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
