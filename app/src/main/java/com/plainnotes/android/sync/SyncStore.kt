package com.plainnotes.android.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Private sync sidecar. Existing document files and NoteIndex remain authoritative. */
class SyncStore(context: Context) : SQLiteOpenHelper(context, "web-sync.db", null, 1) {
    data class Record(val local: String, val id: String, val revision: Long, val base: String?, val baseDeleted: Boolean, val content: String, val deleted: Boolean)
    data class Operation(val op: String, val id: String, val expected: Long, val content: String, val deleted: Boolean, val envelope: String?)
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(local_key TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL DEFAULT 0,base TEXT,base_deleted INTEGER NOT NULL DEFAULT 0,content TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE outbox(op TEXT PRIMARY KEY,id TEXT NOT NULL UNIQUE,expected INTEGER NOT NULL,content TEXT NOT NULL,deleted INTEGER NOT NULL,envelope TEXT)")
        db.execSQL("CREATE TABLE conflicts(id TEXT PRIMARY KEY,local_content TEXT NOT NULL,local_deleted INTEGER NOT NULL,remote TEXT NOT NULL,remote_content TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported sync sidecar version; local files remain usable") }
    private fun Cursor.record() = Record(getString(0),getString(1),getLong(2),if(isNull(3)) null else getString(3),getInt(4)!=0,getString(5),getInt(6)!=0)
    @Synchronized fun taskRecords(): List<Record> = readableDatabase.rawQuery("SELECT local_key,id,revision,base,base_deleted,content,deleted FROM records WHERE local_key LIKE 'task:%'",null).use {c->buildList {while(c.moveToNext())add(c.record())}}
    @Synchronized fun linkedNotes(): List<String> = readableDatabase.rawQuery("SELECT local_key FROM records WHERE local_key LIKE 'note:%' AND deleted=0 AND base IS NOT NULL",null).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    @Synchronized fun operation(id: String): Operation? = readableDatabase.rawQuery("SELECT op,id,expected,content,deleted,envelope FROM outbox WHERE id=?",arrayOf(id)).use {c->if(c.moveToFirst())Operation(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getInt(4)!=0,if(c.isNull(5))null else c.getString(5)) else null}
    @Synchronized fun hasPending(): Boolean = readableDatabase.rawQuery("SELECT EXISTS(SELECT 1 FROM outbox WHERE id NOT IN(SELECT id FROM conflicts))",null).use {it.moveToFirst();it.getInt(0)!=0}
    @Synchronized fun records(): List<Record> = readableDatabase.rawQuery("SELECT local_key,id,revision,base,base_deleted,content,deleted FROM records",null).use { c -> buildList { while(c.moveToNext()) add(c.record()) } }
    @Synchronized fun record(id: String): Record? = readableDatabase.rawQuery("SELECT local_key,id,revision,base,base_deleted,content,deleted FROM records WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.record() else null }
    @Synchronized fun byLocal(local: String): Record? = readableDatabase.rawQuery("SELECT local_key,id,revision,base,base_deleted,content,deleted FROM records WHERE local_key=?",arrayOf(local)).use { if(it.moveToFirst()) it.record() else null }
    @Synchronized fun idFor(local: String): String {
        byLocal(local)?.let { return it.id }
        val id = if(local.startsWith("task:")) local.removePrefix("task:") else UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("records",null,ContentValues().apply { put("local_key",local);put("id",id);put("content","{}") })
        return id
    }
    @Synchronized fun bind(id: String, local: String) {
        val existing=record(id)
        if(existing==null) writableDatabase.insertOrThrow("records",null,ContentValues().apply { put("local_key",local);put("id",id);put("content","{}") })
        else writableDatabase.update("records",ContentValues().apply {put("local_key",local)},"id=?",arrayOf(id))
    }
    @Synchronized fun move(old: String, current: String) {
        if(old!=current) writableDatabase.update("records",ContentValues().apply {put("local_key",current)},"local_key=?",arrayOf(old))
    }
    private fun queue(id: String) {
        val r=record(id)?:return
        if(r.content=="{}" || (r.content==r.base&&r.deleted==r.baseDeleted)) return
        writableDatabase.insertWithOnConflict("outbox",null,ContentValues().apply {
            put("op",UUID.randomUUID().toString());put("id",id);put("expected",r.revision);put("content",r.content);put("deleted",if(r.deleted)1 else 0)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun capture(local: String, payload: JSONObject, deleted: Boolean): Boolean {
        val id=idFor(local);require(id==payload.getString("id"));val json=SyncPayload.canonical(payload);val old=record(id)!!
        if(old.content==json&&old.deleted==deleted) return false
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("records",ContentValues().apply {put("content",json);put("deleted",if(deleted)1 else 0)},"id=?",arrayOf(id))
            queue(id);writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
        return true
    }
    @Synchronized fun pending(limit: Int = 20): List<Operation> = readableDatabase.rawQuery("SELECT op,id,expected,content,deleted,envelope FROM outbox WHERE id NOT IN(SELECT id FROM conflicts) ORDER BY rowid LIMIT ?",arrayOf(limit.toString())).use {c->
        buildList {
            var bytes=0
            while(c.moveToNext()) {
                val content=c.getString(3);val size=content.toByteArray(Charsets.UTF_8).size
                if(isNotEmpty()&&bytes+size>2*1024*1024)break
                add(Operation(c.getString(0),c.getString(1),c.getLong(2),content,c.getInt(4)!=0,if(c.isNull(5))null else c.getString(5)))
                bytes+=size
            }
        }
    }
    @Synchronized fun prepare(op: String, envelope: JSONObject) {writableDatabase.update("outbox",ContentValues().apply {put("envelope",envelope.toString())},"op=? AND envelope IS NULL",arrayOf(op))}
    @Synchronized fun acknowledge(op: Operation, revision: Long, seq: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("records",ContentValues().apply {put("revision",revision);put("base",op.content);put("base_deleted",if(op.deleted)1 else 0)},"id=?",arrayOf(op.id))
            writableDatabase.delete("outbox","op=?",arrayOf(op.op));setMeta("floor",maxOf(meta("floor","0").toLong(),seq).toString());queue(op.id)
            writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
    }
    @Synchronized fun accept(id: String, local: String, remote: JSONObject, payload: JSONObject, current: JSONObject = payload, currentDeleted: Boolean = remote.getBoolean("deleted")) {
        val json=SyncPayload.canonical(payload)
        writableDatabase.beginTransaction()
        try {
            bind(id,local)
            writableDatabase.update("records",ContentValues().apply {put("revision",remote.getLong("revision"));put("base",json);put("base_deleted",if(remote.getBoolean("deleted"))1 else 0);put("content",SyncPayload.canonical(current));put("deleted",if(currentDeleted)1 else 0)},"id=?",arrayOf(id))
            writableDatabase.delete("outbox","id=?",arrayOf(id));queue(id);writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
    }
    @Synchronized fun conflict(id: String, local: JSONObject, deleted: Boolean, remote: JSONObject, payload: JSONObject) {
        writableDatabase.insertWithOnConflict("conflicts",null,ContentValues().apply {put("id",id);put("local_content",local.toString());put("local_deleted",if(deleted)1 else 0);put("remote",remote.toString());put("remote_content",payload.toString())},SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun conflicts(): List<JSONObject> = readableDatabase.rawQuery("SELECT id,local_content,local_deleted,remote,remote_content FROM conflicts",null).use {c->buildList {while(c.moveToNext())add(JSONObject().put("id",c.getString(0)).put("local",JSONObject(c.getString(1))).put("local_deleted",c.getInt(2)!=0).put("remote",JSONObject(c.getString(3))).put("remote_content",JSONObject(c.getString(4))))}}
    @Synchronized fun clearConflict(id: String) {writableDatabase.delete("conflicts","id=?",arrayOf(id))}
    @Synchronized fun setApply(id: String,intent: JSONObject) {setMeta("apply:$id",intent.toString())}
    @Synchronized fun applies(): List<JSONObject> = readableDatabase.rawQuery("SELECT value FROM meta WHERE key LIKE 'apply:%'",null).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
    @Synchronized fun clearApply(id: String) {writableDatabase.delete("meta","key=?",arrayOf("apply:$id"))}
    @Synchronized fun resetBase(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("records",ContentValues().apply {put("revision",0);putNull("base");put("base_deleted",0)},"id=?",arrayOf(id))
            writableDatabase.delete("outbox","id=?",arrayOf(id));queue(id);writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
    }
    @Synchronized fun clearConflictsForRepair() {writableDatabase.delete("conflicts",null,null)}
    @Synchronized fun exportSidecar(): JSONObject = JSONObject().put("records",org.json.JSONArray(records().map {r->JSONObject().put("local",r.local).put("id",r.id).put("revision",r.revision).put("base",r.base ?: JSONObject.NULL).put("content",r.content).put("deleted",r.deleted)})).put("conflicts",org.json.JSONArray(conflicts()))
    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT count(*) FROM outbox",null).use {it.moveToFirst();it.getInt(0)}
    @Synchronized fun meta(key: String, default: String = ""): String = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0) else default}
    @Synchronized fun setMeta(key: String,value: String) {writableDatabase.insertWithOnConflict("meta",null,ContentValues().apply {put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
}
