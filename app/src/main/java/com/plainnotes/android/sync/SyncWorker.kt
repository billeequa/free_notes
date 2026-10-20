package com.plainnotes.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.data.AppSettingsRepository
import com.plainnotes.android.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        // A repair or transient error in Personal must not prevent a linked Work feed from syncing.
        // Disabled installations still never construct a Work repository or database.
        val workEnabled = SyncRuntime.enabled(applicationContext) &&
            (AppSettingsRepository(applicationContext).workspaceSettings.first().enabled ||
                applicationContext.getDatabasePath("web-sync-work.db").exists())
        val workspaces = if (workEnabled) listOf(Workspace.PERSONAL, Workspace.WORK)
            else listOf(Workspace.PERSONAL)
        return syncWorkspaces(workspaces, ::syncWorkspace)
    }

    private suspend fun syncWorkspace(workspace: Workspace): Result = withContext(Dispatchers.IO) {
        if(!SyncRuntime.enabled(applicationContext)||SyncHealth.paused(applicationContext))return@withContext Result.success()
        SyncRuntime.workerMutex.withLock {
            var key:ByteArray?=null
            try {
                val c=applicationContext;val prefs=SyncRuntime.prefs(c);val store=SyncRuntime.store(c,workspace)
                val secret=runCatching {SyncSecrets(c).read()}.getOrElse {throw SyncApi.AuthRequired()}
                val master=SyncCrypto.unb64(secret.getString("key"))
                key=if(workspace==Workspace.WORK)SyncCrypto.expand(master,"workspace/work").also {master.fill(0)} else master
                val connection=SyncConnection.prepareSession(c,secret,store.meta("deployment"))
                val api=connection.api.also {it.workspace=workspace}
                api.refresh();require(api.session!!.getJSONObject("user").getString("id")==SyncRuntime.store(c).meta("owner")){"Cloud account differs"}
                secret.put("session",api.session).put("api_origin",api.url);SyncSecrets(c).save(secret)
                val b=connection.bootstrap;val state=api.vault();val vault=state.optJSONObject("vault") ?: throw SyncApi.CloudChanged()
                val repo=NotesRepository(c,workspace)
                if(workspace==Workspace.WORK && store.meta("epoch").isEmpty()) {
                    val personal=SyncRuntime.store(c)
                    require(NotesRepository(c).rootFolderUri().first()?.toString()==personal.meta("root_uri")){"Notes folder differs; relink before syncing"}
                    require(b.getString("deployment_id")==personal.meta("deployment") && vault.getString("key_id")==personal.meta("key_id")){"Cloud account differs"}
                    val root=repo.rootFolderUri().first()?.toString() ?: error("Choose a notes folder first")
                    repo.backupForSync()
                    store.setMeta("root_uri",root);store.setMeta("owner",personal.meta("owner"))
                    store.setMeta("deployment",personal.meta("deployment"));store.setMeta("key_id",personal.meta("key_id"))
                    store.setMeta("device",java.util.UUID.randomUUID().toString());store.setMeta("epoch",state.getString("epoch"))
                }
                if(b.getString("deployment_id")!=store.meta("deployment") || state.getString("epoch")!=store.meta("epoch") ||
                    vault.getString("key_id")!=store.meta("key_id") || state.getLong("high_water")<store.meta("floor","0").toLong())throw SyncApi.CloudChanged()
                SyncConnection.saveActive(c,connection.service)
                require(repo.rootFolderUri().first()?.toString()==store.meta("root_uri")){"Notes folder differs; relink before syncing"}
                repo.recoverSyncApplies(store);val reconciled=repo.reconcileForSync(store)
                val deadline=System.currentTimeMillis()+30000;var applied=false;var count=0;var bytes=0
                fun decrypt(r:JSONObject):JSONObject {
                    val id=r.getString("id")
                    val aad=SyncCrypto.aad(workspace.recordDeployment(store.meta("deployment")),store.meta("owner"),store.meta("key_id"),id,r.getLong("revision"),r.getBoolean("deleted"))
                    return SyncPayload.validate(JSONObject(String(SyncCrypto.open(key!!,r.getJSONObject("envelope"),aad),Charsets.UTF_8)),id)
                }
                suspend fun acceptRemote(remote:JSONObject) {
                    val id=remote.getString("id");val payload=decrypt(remote);val record=store.record(id)
                    if(record!=null && record.revision>=remote.getLong("revision"))return
                    val pending=store.operation(id)?.takeIf {it.op==remote.optString("op_id")}
                    if(pending!=null){store.acknowledge(pending,remote.getLong("revision"),remote.getLong("seq"));return}
                    if(record!=null && record.content==SyncPayload.canonical(payload) && record.deleted==remote.getBoolean("deleted")) {
                        store.accept(id,record.local,remote,payload);return
                    }
                    val dirty=record!=null && record.content!="{}" && (record.content!=record.base||record.deleted!=record.baseDeleted)
                    if(dirty) {
                        val local=JSONObject(record!!.content)
                        val merged=if(!record.deleted&&!remote.getBoolean("deleted")&&record.base!=null) SyncPayload.mergeTask(JSONObject(record.base),local,payload) else null
                        if(merged!=null) {
                            val target=repo.applySyncRecord(merged,false,record.content,record.local,store)
                            if(target!=null){store.accept(id,target,remote,payload,merged,false);applied=true;return}
                        }
                        store.conflict(id,local,record.deleted,remote,payload);return
                    }
                    if(record?.local?.removePrefix("note:")?.let {it in SyncRuntime.editing} == true) throw java.io.IOException("Finish editing before applying cloud changes")
                    val target=repo.applySyncRecord(payload,remote.getBoolean("deleted"),record?.content?.takeIf {it!="{}" && !(record.deleted && payload.getString("kind")=="task")},record?.local,store)
                    if(target==null) throw java.io.IOException("Local edit changed while syncing; retrying safely")
                    store.accept(id,target,remote,payload);applied=true
                }
                var cursor=store.meta("cursor","0").toLong()
                val page=JSONObject(api.rpc("get_changes",JSONObject().put("p_epoch",store.meta("epoch")).put("p_after",cursor).put("p_limit",20)))
                val changes=page.getJSONArray("changes")
                for(i in 0 until changes.length()) {
                    if(!SyncRuntime.enabled(c)||isStopped) return@withLock Result.success()
                    val remote=changes.getJSONObject(i)
                    require(remote.getLong("seq")==cursor+1){"Missing cloud history; repair required"}
                    acceptRemote(remote)
                    cursor=remote.getLong("seq");store.setMeta("cursor",cursor.toString());store.setMeta("floor",maxOf(cursor,store.meta("floor","0").toLong()).toString())
                }
                if(cursor<page.getLong("high_water")){if(applied)SyncRuntime.localChanges.tryEmit(Unit);SyncRuntime.schedule(c,continuation=true);return@withLock Result.success()}
                for(op in store.pending()) {
                    if(!SyncRuntime.enabled(c)||isStopped)break
                    if(count>=20||bytes>2*1024*1024||System.currentTimeMillis()>deadline)break
                    val envelope=op.envelope?.let(::JSONObject) ?: SyncCrypto.seal(key!!,store.meta("key_id"),op.content.toByteArray(Charsets.UTF_8),
                        SyncCrypto.aad(workspace.recordDeployment(store.meta("deployment")),store.meta("owner"),store.meta("key_id"),op.id,op.expected+1,op.deleted)).also {store.prepare(op.op,it)}
                    val result=JSONObject(api.rpc("commit_operation",JSONObject().put("p_epoch",store.meta("epoch")).put("p_floor",store.meta("floor","0").toLong())
                        .put("p_op_id",op.op).put("p_device_id",store.meta("device")).put("p_id",op.id).put("p_expected_revision",op.expected).put("p_envelope",envelope).put("p_deleted",op.deleted)))
                    if(result.getString("status")=="OK")store.acknowledge(op,result.getLong("revision"),result.getLong("seq"))
                    else {
                        val remote=result.optJSONObject("current") ?: throw SyncApi.CloudChanged()
                        val r=store.record(op.id)!!;store.conflict(op.id,JSONObject(r.content),r.deleted,remote,decrypt(remote))
                    }
                    count++;bytes+=op.content.toByteArray(Charsets.UTF_8).size
                }
                api.rpc("ack_cursor",JSONObject().put("p_device_id",store.meta("device")).put("p_epoch",store.meta("epoch")).put("p_cursor",cursor))
                if(applied)SyncRuntime.localChanges.tryEmit(Unit)
                prefs.edit().putLong(if(workspace==Workspace.WORK)"work_last_sync" else "last_sync",System.currentTimeMillis()).apply()
                SyncRuntime.status(c,if(store.conflicts().isNotEmpty())"Conflict — action needed" else if(store.count()>0||!reconciled)"Saved on phone — pending upload" else "Synced",workspace)
                if(store.hasPending()||!reconciled)SyncRuntime.schedule(c,continuation=true)
                Result.success()
            }catch(e:CancellationException){throw e}
            catch(e:SyncConnection.ReconnectRequired){SyncRuntime.status(applicationContext,e.message!!,workspace);Result.failure()}
            catch(e:SyncConnection.ConfigurationChanged){SyncRuntime.status(applicationContext,e.message!!,workspace);Result.failure()}
            catch(e:SyncApi.CloudChanged){SyncRuntime.status(applicationContext,e.message!!,workspace);Result.failure()}
            catch(e:SyncApi.AuthRequired){SyncRuntime.status(applicationContext,e.message!!,workspace);Result.failure()}
            catch(e:javax.crypto.AEADBadTagException){SyncRuntime.status(applicationContext,"Damaged cloud ciphertext — repair required. Phone files preserved.",workspace);Result.failure()}
            catch(e:IllegalArgumentException){SyncRuntime.status(applicationContext,"Unsupported cloud/local record — repair required. Phone files preserved.",workspace);Result.failure()}
            catch(e:org.json.JSONException){SyncRuntime.status(applicationContext,"Damaged cloud record — repair required. Phone files preserved.",workspace);Result.failure()}
            catch(e:Exception){SyncRuntime.status(applicationContext,"Saved on phone — sync paused; retry or recover in Settings",workspace);Result.retry()}
            finally{key?.fill(0)}
        }
    }
}

/** Try each independent feed and preserve retries without starving the other workspace. */
internal suspend fun syncWorkspaces(workspaces: List<Workspace>, sync: suspend (Workspace) -> androidx.work.ListenableWorker.Result): androidx.work.ListenableWorker.Result {
    val results = workspaces.map { sync(it) }
    return when {
        results.any { it == androidx.work.ListenableWorker.Result.retry() } -> androidx.work.ListenableWorker.Result.retry()
        results.any { it != androidx.work.ListenableWorker.Result.success() } -> androidx.work.ListenableWorker.Result.failure()
        else -> androidx.work.ListenableWorker.Result.success()
    }
}
