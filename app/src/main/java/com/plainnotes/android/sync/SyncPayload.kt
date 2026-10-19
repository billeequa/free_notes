package com.plainnotes.android.sync

import android.net.Uri
import com.plainnotes.android.data.TodoFlag
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

object SyncPayload {
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            var field=if(value.optString("kind")=="task" && it=="modified_at")value.get("created_at") else value.get(it)
            if(value.has("kind") && it=="flags")field=JSONArray(TodoFlag.entries.filter {f-> (0 until value.getJSONArray("flags").length()).any {i->value.getJSONArray("flags").getString(i)==f.key}}.map{f->f.key})
            if(value.has("kind") && it in listOf("created_at","modified_at","completed_at") && field is String)field=OffsetDateTime.parse(field).toInstant().toString()
            JSONObject.quote(it) + ":" + canonical(field)
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun common(id: String, kind: String, title: String, created: OffsetDateTime, modified: OffsetDateTime) = JSONObject()
        .put("id", id).put("kind", kind).put("title", title).put("body", "")
        .put("created_at", created.toInstant().toString()).put("modified_at", modified.toInstant().toString())
        .put("subtype", "standard").put("journal_date", JSONObject.NULL).put("double_x_date", JSONObject.NULL)
        .put("template_version", 0).put("description", "").put("completed_at", JSONObject.NULL)
        .put("flagged", false).put("flags", JSONArray()).put("order", 0)
    fun note(n: EditableNote, id: String): JSONObject = common(id, if (n.category == NoteCategory.JOURNAL) "journal" else "note",
        n.title, n.createdAt.withNano(0), n.modifiedAt.withNano(0)).put("body", n.body)
        .put("subtype", if (n.noteType == NoteType.DOUBLE_X_DAY) "double_x_day" else "standard")
        .put("journal_date", n.journalDate?.toString() ?: JSONObject.NULL)
        .put("double_x_date", n.doubleXDate?.toString() ?: JSONObject.NULL).put("template_version", n.doubleXTemplateVersion)
    fun task(t: TodoItem): JSONObject = common(t.id, "task", t.title, t.addedAt, t.addedAt)
        .put("description", t.description).put("completed_at", t.completedAt?.toInstant()?.toString() ?: JSONObject.NULL)
        .put("flagged", t.flagged).put("flags", JSONArray(TodoFlag.entries.filter { it in t.flags }.map { it.key }))
        .put("order", t.addedAt.toInstant().toEpochMilli())
    fun validate(p: JSONObject, id: String): JSONObject {
        require(p.getString("id") == id && p.getString("kind") in listOf("note", "journal", "task"))
        for (key in listOf("title", "body", "description")) require(p.get(key) is String)
        if(p.getString("kind")!="task")require(!p.getString("title").contains(Regex("[\\r\\n]")))
        OffsetDateTime.parse(p.getString("created_at")); OffsetDateTime.parse(p.getString("modified_at"))
        date(p, "journal_date"); date(p, "double_x_date")
        if (!p.isNull("completed_at")) OffsetDateTime.parse(p.getString("completed_at"))
        p.getBoolean("flagged"); p.getLong("order")
        require(p.getString("subtype") in listOf("standard", "double_x_day"))
        require(p.getInt("template_version") >= 0)
        val flags = p.getJSONArray("flags")
        for (i in 0 until flags.length()) require(TodoFlag.entries.any { it.key == flags.getString(i) })
        return p
    }
    private fun date(p: JSONObject, key: String): LocalDate? = if (p.isNull(key)) null else LocalDate.parse(p.getString(key))
    fun editable(p: JSONObject, uri: Uri, filename: String, trashed: Boolean): EditableNote = EditableNote(
        uri, filename, p.getString("title"), p.getString("body"), OffsetDateTime.parse(p.getString("created_at")).atZoneSameInstant(java.time.ZoneId.systemDefault()).toOffsetDateTime(),
        OffsetDateTime.parse(p.getString("modified_at")).atZoneSameInstant(java.time.ZoneId.systemDefault()).toOffsetDateTime(), trashed,
        if (p.getString("subtype") == "double_x_day") NoteType.DOUBLE_X_DAY else NoteType.NORMAL,
        date(p, "double_x_date"), if (p.getString("kind") == "journal") NoteCategory.JOURNAL else NoteCategory.NOTES,
        date(p, "journal_date"), p.getInt("template_version"))
    fun todo(p: JSONObject) = TodoItem(p.getString("id"), p.getString("title"), p.getString("description"),
        OffsetDateTime.parse(p.getString("created_at")), if (p.isNull("completed_at")) null else OffsetDateTime.parse(p.getString("completed_at")),
        p.getBoolean("flagged"), TodoFlag.entries.filter { flag -> (0 until p.getJSONArray("flags").length()).any { p.getJSONArray("flags").getString(it) == flag.key } }.toSet())
    fun mergeTask(base: JSONObject, local: JSONObject, remote: JSONObject): JSONObject? {
        if (listOf(base, local, remote).any { it.getString("kind") != "task" || it.getString("id") != base.getString("id") }) return null
        val result = JSONObject(remote.toString())
        for (key in local.keys()) {
            if (key == "modified_at") continue
            if (canonical(local.get(key)) == canonical(base.get(key))) continue
            if (canonical(remote.get(key)) != canonical(base.get(key)) && canonical(remote.get(key)) != canonical(local.get(key))) return null
            result.put(key, local.get(key))
        }
        return result
    }
}
