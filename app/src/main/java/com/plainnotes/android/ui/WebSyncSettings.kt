package com.plainnotes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.plainnotes.android.data.NotesRepository
import com.plainnotes.android.data.Workspace
import com.plainnotes.android.model.NoteCategory
import com.plainnotes.android.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun WebsiteSettings(workspace: Workspace = Workspace.PERSONAL) {
    val context = LocalContext.current
    val prefs = remember { SyncRuntime.prefs(context) }
    val uriHandler = LocalUriHandler.current
    var website by remember { mutableStateOf(SyncConnection.website(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> website = SyncConnection.website(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val address = website.trimEnd('/') + if (workspace == Workspace.WORK) "/work" else ""
    Text("Website", style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = {
        error = null
        try { uriHandler.openUri(address) }
        catch(_: IllegalArgumentException) { error = "No browser is available. Open the address above on another device." }
    }) { Text(address) }
    error?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
}

/** Dormant unless opened. KDF/network/preview never run during normal app startup. */
@Composable
fun WebSyncSettings(workspace: Workspace = Workspace.PERSONAL) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val statusKey=if(workspace==Workspace.WORK)"work_status" else "status"
    val lastSyncKey=if(workspace==Workspace.WORK)"work_last_sync" else "last_sync"
    val uriHandler=LocalUriHandler.current
    val prefs=remember {SyncRuntime.prefs(context)}
    var expanded by remember {mutableStateOf(false)}
    var enabled by remember {mutableStateOf(SyncRuntime.enabled(context))}
    var status by remember {mutableStateOf(prefs.getString(statusKey,"Saved on phone — sync disabled")!!)}
    var useCustom by remember {mutableStateOf(SyncConnection.customWebsite(context)!=null)}
    var customWeb by remember {mutableStateOf(SyncConnection.customWebsite(context).orEmpty())}
    var email by remember {mutableStateOf(prefs.getString("email","")!!)}
    var password by remember {mutableStateOf("")}
    var setup by remember {mutableStateOf<SyncRuntime.Setup?>(null)}
    var preview by remember {mutableStateOf("")}
    var counts by remember {mutableStateOf(0)}
    var confirmation by remember {mutableStateOf("")}
    var approved by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var conflicts by remember {mutableStateOf<List<JSONObject>>(emptyList())}
    var repairText by remember {mutableStateOf("")}
    var comparison by remember {mutableStateOf<JSONObject?>(null)}
    DisposableEffect(Unit) { onDispose {setup?.key?.fill(0);setup?.recoveryCode=null} }
    LaunchedEffect(expanded,enabled) {
        if(expanded)while(true) {
            status=prefs.getString(statusKey,"Saved on phone")!!
            if(enabled){counts=withContext(Dispatchers.IO){SyncRuntime.store(context,workspace).count()};conflicts=withContext(Dispatchers.IO){SyncRuntime.store(context,workspace).conflicts()}}
            delay(1500)
        }
    }
    fun run(block:suspend()->Unit) {scope.launch {busy=true;error=null;try{block()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message ?: "Sync setup paused. Phone files are safe."}finally{busy=false}}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(workspace==Workspace.WORK)"Work Web Sync" else "Web Sync",style=MaterialTheme.typography.titleMedium)
        Text(status,style=MaterialTheme.typography.bodyMedium)
        Text("Optional. Sync covers Personal and enabled Work mode in separate encrypted feeds. Your phone files remain available without an account or server.",style=MaterialTheme.typography.bodySmall)
        if(!expanded)TextButton(onClick={expanded=true}){Text(if(enabled)"Manage Web Sync" else "Set up Web Sync")}
        if(expanded) {
            if(enabled) {
                Text(email)
                Text("Pending: $counts · Last sync: "+prefs.getLong(lastSyncKey,0).let {if(it==0L)"Never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))})
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={run{withContext(Dispatchers.IO){SyncRuntime.retry(context)}}},enabled=!busy){Text("Sync now")}
                    OutlinedButton(onClick={SyncRuntime.disable(context);enabled=false},enabled=!busy){Text("Disable all sync")}
                }
                Text("Disabling sync keeps every phone file and the cloud copy. Recovery or reconnecting also never deletes local files.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!busy,onClick={run{val path=withContext(Dispatchers.IO){NotesRepository(context,workspace).backupForSync()};status="Verified local backup: $path"}}){Text("Export verified phone backup")}
                TextButton(enabled=!busy,onClick={SyncRuntime.disable(context);enabled=false;setup=null}){Text("Sign in again")}
                if(status.contains("repair",ignoreCase=true)||status.contains("Cloud state changed",ignoreCase=true)) {
                    Text("Repair keeps phone files, backs up both local content and sync evidence, and retains differing cloud versions as conflicts. Type REPAIR to explicitly approve a new cloud baseline.",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(repairText,{repairText=it},label={Text("Repair confirmation")},modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy&&repairText=="REPAIR",onClick={run{withContext(Dispatchers.IO){SyncRuntime.repair(context,workspace)};repairText=""}}){Text("Back up and repair cloud link")}
                }
                conflicts.forEach { conflict ->
                    OutlinedButton(onClick={comparison=conflict}){Text("Resolve: "+conflict.getJSONObject("local").optString("title","Untitled"))}
                }
            } else if(setup==null) {
                Text("Already set your JNotes password? Sign in below with the same email and password you use on the website.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(email,{email=it},enabled=!busy,label={Text("Email")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                OutlinedTextField(password,{password=it},enabled=!busy,label={Text("Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
                TextButton(enabled=!busy,onClick={useCustom=!useCustom}) {Text(if(useCustom) "Use JNotes connection" else "Advanced: self-hosted connection")}
                if(useCustom) OutlinedTextField(customWeb,{customWeb=it},label={Text("Self-hosted website (HTTPS)")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                Button(enabled=!busy&&email.isNotBlank()&&password.isNotEmpty()&&(!useCustom||customWeb.isNotBlank()),onClick={run{
                    val prepared=withContext(Dispatchers.IO){SyncRuntime.signIn(context,email,password,if(useCustom)customWeb else null)}
                    setup=prepared;password=""
                    preview=withContext(Dispatchers.IO){
                        val repo=NotesRepository(context);val docs=repo.listActiveNotes();val trash=repo.listTrashedNotes();val tasks=repo.loadTodos()
                        val bytes=docs.sumOf {n->repo.loadEditableNote(n.documentUri.toString())?.let{it.title.toByteArray().size+it.body.toByteArray().size}?.toLong() ?: 0L}
                        val work = if(com.plainnotes.android.data.AppSettingsRepository(context).workspaceSettings.first().enabled) {
                            val workRepo=NotesRepository(context,Workspace.WORK)
                            "\nWork: ${workRepo.loadTodos().size} tasks · ${workRepo.listActiveNotes().size} notes (separate encrypted feed)"
                        } else ""
                        "${tasks.size} tasks · ${docs.count{it.category==NoteCategory.NOTES}} notes · ${docs.count{it.category==NoteCategory.JOURNAL}} journal entries · ${trash.size} trashed documents\nApproximately ${bytes/1024} KiB of document text, plus tasks and metadata.\nCloud destination: $email\nExisting cloud revisions: ${prepared.vault.getLong("high_water")}$work"
                    }
                }}){Text("Sign in and preview sync")}
                Text("First time? Create your password on the website once. An email link opens the setup page; no numeric code is needed. Then return here to sign in.",style=MaterialTheme.typography.bodySmall)
                for((label,fragment) in listOf("Set up my password on the website" to "setup","Forgot password?" to "recover")) {
                    TextButton(enabled=!busy&&(!useCustom||customWeb.isNotBlank()),onClick={run{
                        val website=withContext(Dispatchers.IO){SyncRuntime.accountWebsite(context,if(useCustom)customWeb else null)}
                        uriHandler.openUri(website.trimEnd('/')+"/#"+fragment)
                    }}){Text(label)}
                }
            } else {
                val prepared=setup!!
                Text("Initial upload preview",style=MaterialTheme.typography.titleSmall)
                Text(preview)
                Text("All tasks, notes and journals will be encrypted before upload. A verified local backup is created first. Existing cloud records are preserved; simultaneous edits need a reviewed merge.",style=MaterialTheme.typography.bodySmall)
                prepared.recoveryCode?.let {code ->
                    Text("Save this recovery code now. It is displayed only during setup. If reconnecting, this code replaces the old code only after you confirm.")
                    SelectionContainer {Text(code,style=MaterialTheme.typography.bodyMedium)}
                    OutlinedTextField(confirmation,{confirmation=it.uppercase()},label={Text("Type the final 8 characters to confirm saved")},singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                Row {
                    Checkbox(approved,{approved=it})
                    Text("I approve the complete encrypted upload/merge after the local backup.",modifier=Modifier.padding(top=12.dp))
                }
                Button(enabled=!busy&&approved&&(prepared.recoveryCode==null||confirmation==prepared.recoveryCode!!.takeLast(8)),onClick={run{
                    withContext(Dispatchers.IO){SyncRuntime.enable(context,prepared,email)}
                    setup=null;enabled=true;confirmation="";approved=false
                }}){Text("Back up phone and enable sync")}
                TextButton(onClick={prepared.key.fill(0);prepared.recoveryCode=null;setup=null;approved=false;confirmation=""},enabled=!busy){Text("Cancel setup")}
            }
            if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    comparison?.let {conflict ->
        AlertDialog(onDismissRequest={comparison=null},title={Text("Resolve sync conflict")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Both versions are preserved until you choose.")
                Text("Phone: "+conflict.getJSONObject("local").let {it.optString("title")+"\n"+it.optString("body").ifEmpty {it.optString("description")}}.take(1500))
                Text("Cloud: "+conflict.getJSONObject("remote_content").let {it.optString("title")+"\n"+it.optString("body").ifEmpty {it.optString("description")}}.take(1500))
                for((choice,label)in listOf("local" to "Keep Phone","remote" to "Keep Web","both" to "Save Both"))Button(onClick={run{withContext(Dispatchers.IO){SyncRuntime.resolve(context,conflict.getString("id"),choice,workspace)};comparison=null}},enabled=!busy){Text(label)}
            }
        },confirmButton={TextButton(onClick={comparison=null}){Text("Later")}})
    }
}
