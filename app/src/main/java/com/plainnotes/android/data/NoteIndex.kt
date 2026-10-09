package com.plainnotes.android.data

import android.net.Uri
import android.util.AtomicFile
import com.plainnotes.android.model.NoteCategory
import com.plainnotes.android.model.NoteDocument
import com.plainnotes.android.model.NoteType
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class IndexedNote(
    val note: NoteDocument,
    val modifiedMillis: Long?,
    val size: Long?,
    val checkedAt: Long,
) {
    fun matches(entry: DocumentEntry, time: Long): Boolean =
        entry.modifiedMillis != null && entry.modifiedMillis > 0 && entry.size != null &&
            note.filename == entry.name && modifiedMillis == entry.modifiedMillis && size == entry.size &&
            time >= checkedAt && time - checkedAt < MAX_AGE

    companion object { private const val MAX_AGE = 24 * 60 * 60 * 1000L }
}

internal data class NoteIndexSnapshot(val folderUri: String, val folderName: String, val notes: List<NoteDocument>)

/** Disposable, body-free cache. User-owned note files and their format remain authoritative. */
internal class NoteIndex(private val directory: File, val folderUri: String, var folderName: String) {
    val entries = linkedMapOf<String, IndexedNote>()
    val doubleXDates = mutableMapOf<LocalDate, String>()
    private val file = AtomicFile(File(directory, "${UUID.nameUUIDFromBytes(folderUri.toByteArray())}.json"))

    fun put(record: IndexedNote) {
        remove(record.note.id)
        entries[record.note.id] = record
        if (record.note.noteType == NoteType.DOUBLE_X_DAY) record.note.doubleXDate?.let {
            doubleXDates.putIfAbsent(it, record.note.id)
        }
    }

    fun remove(uri: String) {
        val previous = entries.remove(uri) ?: return
        val date = previous.note.doubleXDate ?: return
        if (doubleXDates[date] == uri) {
            doubleXDates.remove(date)
            entries.values.firstOrNull { it.note.noteType == NoteType.DOUBLE_X_DAY && it.note.doubleXDate == date }
                ?.let { doubleXDates[date] = it.note.id }
        }
    }

    fun snapshot() = NoteIndexSnapshot(folderUri, folderName, entries.values.map { it.note })

    fun load(): Boolean = runCatching {
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1 && json.getString("folder") == folderUri)
        require(json.getString("zone") == java.time.ZoneId.systemDefault().id)
        val records = json.getJSONArray("notes")
        val loaded = linkedMapOf<String, IndexedNote>()
        for (i in 0 until records.length()) {
            val item = records.getJSONObject(i)
            val note = NoteDocument(Uri.parse(item.getString("uri")), item.getString("filename"),
                item.getString("title"), OffsetDateTime.parse(item.getString("created")),
                OffsetDateTime.parse(item.getString("modified")), false,
                NoteType.valueOf(item.getString("type")), item.optionalDate("doubleXDate"),
                NoteCategory.valueOf(item.getString("category")), item.optionalDate("journalDate"),
                item.getInt("template"))
            require(loaded.put(note.id, IndexedNote(note, item.optionalLong("stamp"),
                item.optionalLong("size"), item.getLong("checked"))) == null)
        }
        folderName = json.getString("name")
        entries.clear(); entries.putAll(loaded); rebuildDates()
        true
    }.getOrDefault(false)

    /** A cache failure must never turn a verified note save into a failed save. */
    fun persist() {
        var stream: java.io.FileOutputStream? = null
        try {
            directory.mkdirs()
            val records = JSONArray()
            entries.values.forEach { record ->
                val note = record.note
                records.put(JSONObject().put("uri", note.id).put("filename", note.filename)
                    .put("title", note.title).put("created", note.createdAt.toString())
                    .put("modified", note.modifiedAt.toString()).put("type", note.noteType.name)
                    .put("doubleXDate", note.doubleXDate?.toString() ?: JSONObject.NULL)
                    .put("category", note.category.name).put("journalDate", note.journalDate?.toString() ?: JSONObject.NULL)
                    .put("template", note.doubleXTemplateVersion)
                    .put("stamp", record.modifiedMillis ?: JSONObject.NULL)
                    .put("size", record.size ?: JSONObject.NULL).put("checked", record.checkedAt))
            }
            val json = JSONObject().put("version", 1).put("folder", folderUri).put("name", folderName)
                .put("zone", java.time.ZoneId.systemDefault().id).put("notes", records)
            stream = file.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            // An old cache would be stale after this mutation. Force reconstruction next launch.
            file.delete()
        }
    }

    private fun rebuildDates() {
        doubleXDates.clear()
        entries.values.forEach { record ->
            if (record.note.noteType == NoteType.DOUBLE_X_DAY) record.note.doubleXDate?.let {
                doubleXDates.putIfAbsent(it, record.note.id)
            }
        }
    }

    private fun JSONObject.optionalDate(key: String): LocalDate? =
        if (isNull(key)) null else LocalDate.parse(getString(key))
    private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else getLong(key)
}
