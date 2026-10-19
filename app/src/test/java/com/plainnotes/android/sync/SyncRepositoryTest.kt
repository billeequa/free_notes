package com.plainnotes.android.sync

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.plainnotes.android.data.AppSettingsRepository
import com.plainnotes.android.data.NoteFileParser
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.model.NoteTextContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncRepositoryTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val tree=DocumentsContract.buildTreeDocumentUri("sync-test","root")
    private val date=OffsetDateTime.parse("2026-10-18T12:00:00Z")
    private lateinit var provider:SyncDocumentsProvider
    private lateinit var store:SyncStore
    @Before fun setup()=runBlocking {
        SyncRuntime.prefs(context).edit().clear().commit()
        provider=SyncDocumentsProvider(File(context.cacheDir,"sync-provider").apply {deleteRecursively();mkdirs()})
        provider.attachInfo(context,ProviderInfo().apply {authority="sync-test";exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS"})
        ShadowContentResolver.registerProviderInternal("sync-test",provider)
        AppSettingsRepository(context).setRootFolderUri(tree)
        store=SyncRuntime.store(context)
    }
    private suspend fun existing():Pair<NotesRepository,com.plainnotes.android.model.EditableNote> {
        val filename="20261018-120000-original.txt"
        File(provider.root,filename).writeText(NoteFileParser.serialize(NoteTextContent("original",date,date,"Phone λ\nOriginal body")))
        val repo=NotesRepository(context);val summary=repo.listActiveNotes().single();val note=repo.loadEditableNote(summary.id)!!
        val local="note:${note.documentUri}";val id=store.idFor(local);store.capture(local,SyncPayload.note(note,id),false);store.acknowledge(store.pending().single(),1,1)
        SyncRuntime.prefs(context).edit().putBoolean("enabled",true).commit()
        return repo to note
    }
    @Test fun reconciliationResumesInBoundedBatchesWithoutDuplicateIdentities()=runBlocking {
        for(i in 0..4)File(provider.root,"20261018-12000$i-note.txt").writeText(
            NoteFileParser.serialize(NoteTextContent("note $i",date,date,"Body $i")))
        val repo=NotesRepository(context)
        assertFalse(repo.reconcileForSync(store,2))
        assertEquals(2,store.records().size)
        assertTrue(store.meta("reconcile_after").isNotEmpty())
        assertFalse(repo.reconcileForSync(store,2))
        assertEquals(4,store.records().size)
        assertTrue(repo.reconcileForSync(store,2))
        assertEquals("",store.meta("reconcile_after"))
        assertEquals(5,store.records().map{it.id}.toSet().size)
        val ids=store.records().map{it.id}.toSet()
        while(!repo.reconcileForSync(store,2)){}
        assertEquals(ids,store.records().map{it.id}.toSet())
        assertEquals(5,store.pending().size)
    }
    @Test fun compareAndSetNeverOverwritesNewPhoneWriting()=runBlocking {
        val (repo,note)=existing();val id=store.byLocal("note:${note.documentUri}")!!.id
        val expected=SyncPayload.canonical(SyncPayload.note(note,id))
        val updated=repo.saveNote(note.copy(body="New phone writing"))
        val remote=SyncPayload.note(note.copy(body="Cloud writing"),id)
        assertNull(repo.applySyncRecord(remote,false,expected,"note:${updated.documentUri}",store))
        assertEquals("New phone writing",repo.loadEditableNote(updated.documentUri.toString())!!.body)
    }
    @Test fun interruptedRenameRecoversSameIdentityAndExactlyOneFile()=runBlocking {
        val (repo,note)=existing();val id=store.byLocal("note:${note.documentUri}")!!.id
        provider.failDelete=true
        try {repo.saveNote(note.copy(title="renamed",body="Latest local text"));fail("Injected delete failure") }catch(_:java.io.IOException){}
        assertEquals(1,store.applies().size)
        provider.failDelete=false
        val restarted=NotesRepository(context);restarted.recoverSyncApplies(store);restarted.reconcileForSync(store)
        assertTrue(store.applies().isEmpty())
        val current=restarted.listActiveNotes();assertEquals(1,current.size)
        assertEquals(id,store.byLocal("note:${current.single().documentUri}")!!.id)
        assertEquals("Latest local text",restarted.loadEditableNote(current.single().id)!!.body)
        assertEquals(1L,store.pending().single().expected)
    }
    @Test fun interruptedNewPullRecoversWithoutDuplicateOrLosingUnicode()=runBlocking {
        val repo=NotesRepository(context);repo.listActiveNotes()
        val note=com.plainnotes.android.model.EditableNote(android.net.Uri.EMPTY,"","Cloud title","λ 🚨\n".repeat(10000),date,date,false)
        val id=UUID.randomUUID().toString();val p=SyncPayload.note(note,id)
        SyncRuntime.prefs(context).edit().putBoolean("enabled",true).commit();provider.failWrite=true
        try{repo.applySyncRecord(p,false,null,null,store);fail("Injected write failure")}catch(_:java.io.IOException){}
        assertEquals(1,store.applies().size)
        provider.failWrite=false
        val restarted=NotesRepository(context);restarted.recoverSyncApplies(store);restarted.reconcileForSync(store)
        assertEquals(1,restarted.listActiveNotes().size)
        assertEquals(id,store.records().single().id)
        assertEquals(note.body,restarted.loadEditableNote(restarted.listActiveNotes().single().id)!!.body)
    }
    @Test fun cloudRestoreCanRecoverANotePermanentlyRemovedFromPhoneTrash()=runBlocking {
        val (repo,note)=existing();val id=store.byLocal("note:${note.documentUri}")!!.id
        repo.moveToTrash(note.documentUri.toString())
        val trashed=repo.listTrashedNotes().single()
        store.acknowledge(store.pending().single(),2,2)
        repo.deletePermanently(trashed.documentUri.toString())
        val record=store.record(id)!!
        val payload=JSONObject(record.content)
        val local=repo.applySyncRecord(payload,false,record.content,record.local,store)
        assertNotNull(local)
        store.accept(id,local!!,JSONObject().put("revision",3).put("deleted",false),payload)
        assertEquals(1,repo.listActiveNotes().size)
        assertEquals(note.body,repo.loadEditableNote(repo.listActiveNotes().single().id)!!.body)
    }
    @Test fun openEditorDefersPullAndVerifiedBackupKeepsExistingReadableFiles()=runBlocking {
        val (repo,note)=existing();val id=store.byLocal("note:${note.documentUri}")!!.id
        val p=SyncPayload.note(note.copy(body="Web"),id)
        SyncRuntime.editing.add(note.documentUri.toString())
        try{assertNull(repo.applySyncRecord(p,false,store.record(id)!!.content,"note:${note.documentUri}",store))}finally{SyncRuntime.editing.remove(note.documentUri.toString())}
        repo.saveTodos(listOf(TodoItem(title="task 🚨",addedAt=date)))
        val backup=repo.backupForSync();var count=0
        java.util.zip.ZipInputStream(context.contentResolver.openInputStream(android.net.Uri.parse(backup))).use{zip->while(true){val entry=zip.nextEntry?:break;val text=zip.readBytes().toString(Charsets.UTF_8);if(entry.name.endsWith("original.txt"))assertTrue(text.contains("Original body"));count++;zip.closeEntry()}}
        assertEquals(2,count)
        SyncRuntime.disable(context)
        assertEquals(note.body,repo.loadEditableNote(note.documentUri.toString())!!.body)
    }
}

private class SyncDocumentsProvider(val root:File):android.content.ContentProvider() {
    var failDelete=false;var failWrite=false
    override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val document=DocumentsContract.getDocumentId(uri)
        return if(uri.lastPathSegment=="children")queryChildDocuments(document,projection,sortOrder) else queryDocument(document,projection)
    }
    override fun openFile(uri:android.net.Uri,mode:String)=openDocument(DocumentsContract.getDocumentId(uri),mode,null)
    override fun getType(uri:android.net.Uri):String=if(file(DocumentsContract.getDocumentId(uri)).isDirectory)DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
    override fun call(method:String,arg:String?,extras:android.os.Bundle?):android.os.Bundle {
        val uri=extras!!.getParcelable<android.net.Uri>("uri")!!
        return when(method) {
            "android:createDocument" -> {
                val document=createDocument(DocumentsContract.getDocumentId(uri),extras.getString("mime_type")!!,extras.getString("_display_name")!!)
                android.os.Bundle().apply{putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,document))}
            }
            "android:deleteDocument" -> {deleteDocument(DocumentsContract.getDocumentId(uri));android.os.Bundle()}
            else -> error("Unexpected provider method $method")
        }
    }
    override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri?=null
    override fun delete(uri:android.net.Uri,selection:String?,selectionArgs:Array<out String>?)=0
    override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0

    override fun onCreate()=true
    private fun file(id:String)=if(id=="root")root else File(root,id.removePrefix("root/"))
    private fun id(f:File)=if(f==root)"root" else "root/"+f.relativeTo(root).invariantSeparatorsPath
    private fun cursor(files:List<File>,projection:Array<out String>?):Cursor {
        val columns=projection?:arrayOf("document_id","_display_name","mime_type","last_modified","_size","flags")
        return MatrixCursor(columns).apply{files.forEach{f->addRow(columns.map{column->when(column){
            "document_id"->id(f);"_display_name"->f.name;"mime_type"->if(f.isDirectory)DocumentsContract.Document.MIME_TYPE_DIR else if(f.extension=="zip")"application/zip" else "text/plain"
            "last_modified"->f.lastModified();"_size"->f.length();"flags"->0;else->null
        }}.toTypedArray<Any?>())}}
    }
    fun queryRoots(projection:Array<out String>?)=MatrixCursor(projection?:arrayOf("root_id","document_id"))
    fun queryDocument(documentId:String,projection:Array<out String>?)=cursor(listOf(file(documentId)).filter{it.exists()},projection)
    fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?)=cursor(file(parentDocumentId).listFiles().orEmpty().toList(),projection)
    fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
        if(failWrite&&mode.contains('w'))throw java.io.IOException("Injected write failure")
        return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    }
    fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
        val f=File(file(parentDocumentId),displayName)
        if(mimeType==DocumentsContract.Document.MIME_TYPE_DIR)f.mkdirs() else f.createNewFile()
        return id(f)
    }
    fun deleteDocument(documentId:String) {if(failDelete)throw java.io.IOException("Injected delete failure");check(file(documentId).delete())}
    fun isChildDocument(parentDocumentId:String,documentId:String)=file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath+File.separator)
}
