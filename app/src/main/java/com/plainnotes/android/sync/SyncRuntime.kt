package com.plainnotes.android.sync

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.data.TodoItem
import com.plainnotes.android.model.EditableNote
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SyncRuntime {
    private const val PERIODIC="jnotes-cloud-periodic"
    private const val IMMEDIATE="jnotes-cloud-now"
    private val stores=ConcurrentHashMap<String,SyncStore>()
    val workerMutex=Mutex()
    val editing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val localChanges=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    fun prefs(c: Context)=c.applicationContext.getSharedPreferences("web-sync",Context.MODE_PRIVATE)
    fun enabled(c: Context)=prefs(c).getBoolean("enabled",false)
    fun store(c: Context): SyncStore = stores.getOrPut(c.applicationContext.filesDir.path) {SyncStore(c.applicationContext)}
    fun status(c: Context,text: String) {prefs(c).edit().putString("status",text).apply()}
    fun captureNote(c: Context,note: EditableNote,previous: Uri?=null) {
        if(!enabled(c))return
        try {
            val s=store(c);val local="note:${note.documentUri}"
            if(previous!=null)s.move("note:$previous",local)
            val id=s.idFor(local)
            if(s.capture(local,SyncPayload.note(note,id),note.isTrashed))schedule(c)
        }catch(e:Exception){status(c,"Saved on phone — sync queue needs repair")}
    }
    fun prepareLocalMove(c: Context,source: EditableNote,updated: EditableNote,targetName: String,rawText: String? = null) {
        if(!enabled(c))return
        try {
            val s=store(c);val id=s.idFor("note:${source.documentUri}")
            val intent=JSONObject().put("id",id).put("payload",SyncPayload.note(updated,id)).put("deleted",updated.isTrashed)
                .put("source",source.documentUri.toString()).put("expected",SyncPayload.canonical(SyncPayload.note(source,id)))
                .put("record_content",s.record(id)!!.content).put("target_name",targetName)
            if(rawText!=null)intent.put("raw_text",rawText)
            s.setApply(id,intent)
        }catch(e:Exception){status(c,"Saved on phone — move identity needs sync repair")}
    }
    fun completeLocalMove(c: Context,uri: Uri) {
        if(!enabled(c))return
        try {val s=store(c);s.byLocal("note:$uri")?.let {s.clearApply(it.id)}}catch(e:Exception){status(c,"Saved on phone — sync repair needed")}
    }
    fun captureTodos(c: Context,items: List<TodoItem>,schedule: Boolean=true,previous: List<TodoItem>? = null) {
        if(!enabled(c))return
        try {
            val s=store(c)
            val changed=if(previous==null)captureTodosStrict(s,items) else {
                val old=previous.associateBy {it.id};val current=items.associateBy {it.id};var changed=false
                for(t in items)if(t!=old[t.id]) {s.idFor("task:${t.id}");changed=s.capture("task:${t.id}",SyncPayload.task(t),false)||changed}
                for(t in previous)if(t.id !in current) {s.idFor("task:${t.id}");changed=s.capture("task:${t.id}",SyncPayload.task(t),true)||changed}
                changed
            }
            if(changed&&schedule)SyncRuntime.schedule(c)
        }catch(e:Exception){status(c,"Saved on phone — sync queue needs repair")}
    }
    fun captureTodosStrict(s: SyncStore,items: List<TodoItem>): Boolean {
        var changed=false
        val existing=s.taskRecords().associateBy {it.id}
        for(t in items){
            val payload=SyncPayload.task(t);val old=existing[t.id]
            if(old==null || old.deleted || old.content!=SyncPayload.canonical(payload)) {s.idFor("task:${t.id}");changed=s.capture("task:${t.id}",payload,false)||changed}
        }
        val present=items.mapTo(hashSetOf()){it.id}
        for(r in existing.values.filter {!it.deleted&&it.id !in present})
            if(r.content!="{}")changed=s.capture(r.local,JSONObject(r.content),true)||changed
        return changed
    }
    fun schedule(c: Context,periodic: Boolean=false,continuation: Boolean=false) {
        if(!enabled(c))return
        val work=WorkManager.getInstance(c.applicationContext)
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        if(periodic)work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        work.enqueueUniqueWork(IMMEDIATE,if(continuation)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    fun disable(c: Context) {
        prefs(c).edit().putBoolean("enabled",false).putString("status","Saved on phone — sync disabled").commit()
        // Initialize WorkManager only if sync has actually been configured.
        if(prefs(c).contains("url")) {
            WorkManager.getInstance(c).cancelUniqueWork(PERIODIC);WorkManager.getInstance(c).cancelUniqueWork(IMMEDIATE)
        }
        // Keep a wrapped credential for safe re-enable; no unwrapped key survives a worker.
    }
    fun configFromWeb(web: String): JSONObject {
        val u=java.net.URL(web.trimEnd('/')+"/config.json")
        require(u.protocol=="https" || u.host in listOf("localhost","127.0.0.1","10.0.2.2")) {"Use an HTTPS web address"}
        val c=u.openConnection() as java.net.HttpURLConnection
        c.connectTimeout=15000;c.readTimeout=15000;c.instanceFollowRedirects=false
        try {require(c.responseCode==200){"Web configuration unavailable"};return JSONObject(c.inputStream.bufferedReader().use {it.readText()}.also {require(it.length<8192)})}finally{c.disconnect()}
    }
    data class Setup(val api: SyncApi,val bootstrap: JSONObject,val owner: String,val keyId: String,val key: ByteArray,val vault: JSONObject,var recoveryCode: String?)
    fun begin(web: String,email: String): SyncApi {
        val config=configFromWeb(web)
        return SyncApi(config.getString("supabase_url"),config.getString("publishable_key")).also {it.sendCode(email)}
    }
    fun verify(c: Context,api: SyncApi,email: String,otp: String,password: String,recoveryInput: String = ""): Setup {
        require(password.length>=12){"Use at least 12 characters"}
        val b=api.bootstrap();val credentials=SyncCrypto.derive(password,b)
        try {
            val session=api.verifyCode(email,otp);val owner=session.getJSONObject("user").getString("id")
            val state=api.vault();val prior=state.optJSONObject("vault")
            val keyId=prior?.getString("key_id") ?: UUID.randomUUID().toString()
            val key: ByteArray
            var recovery: String?=null
            if(prior==null) {
                key=SyncCrypto.random();recovery=SyncCrypto.recoveryCode()
                val wrap=SyncCrypto.seal(credentials.wrap,keyId,key,SyncCrypto.vaultAAD(b.getString("deployment_id"),owner,keyId,"password"))
                val rk=SyncCrypto.recoveryKey(recovery)
                val recoveryEnvelope=try {SyncCrypto.seal(rk,keyId,key,SyncCrypto.vaultAAD(b.getString("deployment_id"),owner,keyId,"recovery"))}finally{rk.fill(0)}
                // Set derived auth credential before publishing the envelope. No user files uploaded.
                api.request("/auth/v1/user",JSONObject().put("password",credentials.auth),method="PUT")
                api.rpc("create_vault",JSONObject().put("p_key_id",keyId).put("p_password_envelope",wrap).put("p_recovery_envelope",recoveryEnvelope))
            } else {
                val aad=SyncCrypto.vaultAAD(b.getString("deployment_id"),owner,keyId,"password")
                key=try {SyncCrypto.open(credentials.wrap,prior.getJSONObject("password_envelope"),aad)}catch(e:Exception){
                    val staged=prior.optJSONObject("staged_envelope")
                    val stagedKey=staged?.let {runCatching {SyncCrypto.open(credentials.wrap,it,aad)}.getOrNull()}
                    if(stagedKey!=null) {
                        api.rpc("finish_password",JSONObject().put("p_transition_id",prior.getString("transition_id")));stagedKey
                    }else {
                        val recovered=if(recoveryInput.isNotBlank()) {
                            val rk=SyncCrypto.recoveryKey(recoveryInput)
                            try {SyncCrypto.open(rk,prior.getJSONObject("recovery_envelope"),SyncCrypto.vaultAAD(b.getString("deployment_id"),owner,keyId,"recovery"))}finally{rk.fill(0)}
                        }else {
                            val cached=runCatching {SyncSecrets(c).read()}.getOrNull() ?: throw SyncApi.AuthRequired()
                            require(cached.getString("owner")==owner&&cached.getString("key_id")==keyId)
                            SyncCrypto.unb64(cached.getString("key"))
                        }
                        val transition=prior.optString("transition_id").takeIf {it.isNotBlank()&&it!="null"} ?: UUID.randomUUID().toString()
                        val wrapped=SyncCrypto.seal(credentials.wrap,keyId,recovered,aad)
                        api.rpc("stage_password",JSONObject().put("p_transition_id",transition).put("p_envelope",wrapped))
                        api.request("/auth/v1/user",JSONObject().put("password",credentials.auth),method="PUT")
                        api.rpc("finish_password",JSONObject().put("p_transition_id",transition));recovered
                    }
                }
            }
            if(recovery==null)recovery=SyncCrypto.recoveryCode()
            return Setup(api,b,owner,keyId,key,api.vault(),recovery)
        }finally{credentials.wrap.fill(0)}
    }
    suspend fun enable(c: Context,setup: Setup,web: String,email: String) {
        val s=store(c)
        val rootUri=NotesRepository(c).rootFolderUri().first()?.toString() ?: error("Choose a notes folder first")
        require(s.meta("root_uri").isEmpty() || s.meta("root_uri")==rootUri){"The previously linked notes folder differs. Relink that folder or use a separately reviewed new-vault reset. Local files are unchanged."}
        val oldOwner=s.meta("owner")
        require(oldOwner.isEmpty() || oldOwner==setup.owner && s.meta("deployment")==setup.bootstrap.getString("deployment_id") && s.meta("key_id")==setup.keyId){"Previously linked cloud differs. A reviewed backup/reset is required. Phone files are unchanged."}
        val backup=NotesRepository(c).backupForSync()
        setup.recoveryCode?.let { code ->
            val rk=SyncCrypto.recoveryKey(code)
            val e=try {SyncCrypto.seal(rk,setup.keyId,setup.key,SyncCrypto.vaultAAD(setup.bootstrap.getString("deployment_id"),setup.owner,setup.keyId,"recovery"))}finally{rk.fill(0)}
            setup.api.rpc("rotate_recovery",JSONObject().put("p_envelope",e))
        }
        val state=setup.vault
        val secrets=JSONObject().put("session",setup.api.session).put("key",SyncCrypto.b64(setup.key)).put("owner",setup.owner).put("key_id",setup.keyId)
        SyncSecrets(c).save(secrets)
        s.setMeta("root_uri",rootUri)
        s.setMeta("owner",setup.owner);s.setMeta("deployment",setup.bootstrap.getString("deployment_id"));s.setMeta("key_id",setup.keyId)
        if(s.meta("epoch").isEmpty())s.setMeta("epoch",state.getString("epoch"))
        if(s.meta("device").isEmpty())s.setMeta("device",UUID.randomUUID().toString())
        prefs(c).edit().putString("url",setup.api.url).putString("public_key",setup.api.publicKey).putString("web",web).putString("email",email)
            .putString("backup",backup).putBoolean("enabled",true).putString("status","Saved on phone — pending initial upload").commit()
        setup.key.fill(0);setup.recoveryCode=null
        // Snapshot and outbox construction occur off the UI thread, after verified backup and consent.
        schedule(c,periodic=true)
    }
    /** Explicit repair only: verified phone backup, then preserve both sides as conflicts.
     * This never replaces or deletes phone files and never accepts an empty server implicitly. */
    suspend fun repair(c: Context) = workerMutex.withLock {
        val s=store(c);val prefs=prefs(c);val repo=NotesRepository(c)
        require(repo.rootFolderUri().first()?.toString()==s.meta("root_uri")){"Relink the original notes folder before repairing sync"}
        val backup=repo.backupForSync()
        val secret=SyncSecrets(c).read();val key=SyncCrypto.unb64(secret.getString("key"))
        try {
            val api=SyncApi(prefs.getString("url","")!!,prefs.getString("public_key","")!!,secret.getJSONObject("session"))
            api.refresh();secret.put("session",api.session);SyncSecrets(c).save(secret)
            val state=api.vault();val vault=state.optJSONObject("vault") ?: error("Cloud vault missing. Re-provisioning requires a new reviewed link.")
            val b=api.bootstrap()
            require(b.getString("deployment_id")==s.meta("deployment") && vault.getString("key_id")==s.meta("key_id")){"Cloud identity/key differs; no phone files or sync metadata changed."}
            val epoch=state.getString("epoch");val high=state.getLong("high_water")
            val remote=linkedMapOf<String,JSONObject>();var cursor=0L
            while(cursor<high) {
                val page=JSONObject(api.rpc("get_changes",JSONObject().put("p_epoch",epoch).put("p_after",cursor).put("p_limit",20)))
                val changes=page.getJSONArray("changes");require(changes.length()>0){"Incomplete cloud snapshot"}
                for(i in 0 until changes.length()) {
                    val r=changes.getJSONObject(i);require(r.getLong("seq")==cursor+1);cursor=r.getLong("seq")
                    if(cursor<=high)remote[r.getString("id")]=r
                }
            }
            while(!repo.reconcileForSync(s))kotlinx.coroutines.yield()
            // Preserve prior unresolved variants inside the verified recovery ZIP before resetting their queue bases.
            s.clearConflictsForRepair()
            for(record in s.records()) {
                val r=remote[record.id]
                if(r==null)s.resetBase(record.id) else {
                    val plain=SyncCrypto.open(key,r.getJSONObject("envelope"),SyncCrypto.aad(s.meta("deployment"),s.meta("owner"),s.meta("key_id"),record.id,r.getLong("revision"),r.getBoolean("deleted")))
                    val payload=SyncPayload.validate(JSONObject(String(plain,Charsets.UTF_8)),record.id)
                    val phone=JSONObject(record.content)
                    s.accept(record.id,record.local,r,payload,phone,record.deleted)
                    if(SyncPayload.canonical(payload)!=record.content||r.getBoolean("deleted")!=record.deleted)s.conflict(record.id,phone,record.deleted,r,payload)
                }
            }
            s.setMeta("epoch",epoch);s.setMeta("cursor","0");s.setMeta("floor","0")
            prefs.edit().putString("backup",backup).putString("status","Phone backup verified — cloud re-baseline approved; review conflicts").commit()
            schedule(c,periodic=true)
        }finally{key.fill(0)}
    }

    suspend fun resolve(c: Context,id: String,choice: String) {
        val s=store(c);val conflict=s.conflicts().first {it.getString("id")==id};val r=s.record(id)?:return
        val remote=conflict.getJSONObject("remote");val rp=conflict.getJSONObject("remote_content")
        val phone=JSONObject(r.content);val repository=NotesRepository(c)
        if(choice=="remote") {
            val archived=JSONObject(phone.toString()).put("id",UUID.randomUUID().toString()).put("title",phone.getString("title")+" (discarded conflict)")
            val archivedLocal=repository.applySyncRecord(archived,true,null,null,s) ?: error("Cannot preserve discarded conflict")
            s.idFor(archivedLocal);s.capture(archivedLocal,archived,true)
            val local=repository.applySyncRecord(rp,remote.getBoolean("deleted"),r.content,r.local,s) ?: error("Finish editing this record before resolving")
            s.accept(id,local,remote,rp)
        }else {
            s.accept(id,r.local,remote,rp,phone,r.deleted)
            if(choice=="both") {
                val copy=JSONObject(rp.toString()).put("id",UUID.randomUUID().toString()).put("title",rp.getString("title")+" (conflict copy)")
                val local=repository.applySyncRecord(copy,remote.getBoolean("deleted"),null,null,s) ?: error("Cannot save conflict copy")
                s.capture(local,copy,remote.getBoolean("deleted"))
            }
        }
        s.clearConflict(id);localChanges.tryEmit(Unit);schedule(c)
    }
}
