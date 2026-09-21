package com.plainnotes.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.plainnotes.android.BuildConfig
import com.plainnotes.android.update.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<AppUpdates.Release?>(null) }
    var apkPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Update failed. Please try again." }
            finally { busy = false }
        }
    }
    fun install(file: File) {
        message = if (AppUpdates.install(context, file)) "Confirm the update in Android's installer."
            else "Allow updates from Jnotes, then return here and tap Install update."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { perform {
            val file = AppUpdates.importApk(context, it)
            apkPath = file.path
            install(file)
        } }
    }
    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Text("Installed: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    TextButton(enabled = !busy, onClick = { perform {
        release = null
        val latest = AppUpdates.latest()
        if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            release = latest
            message = "Available: ${latest.label}"
        } else message = "You're up to date."
    } }) { Text(if (busy) "Working…" else "Check for updates") }
    release?.let { available ->
        Button(enabled = !busy, onClick = { perform {
            val file = AppUpdates.download(context, available)
            apkPath = file.path
            install(file)
        } }) { Text("Download and install update") }
    }
    apkPath?.let { path ->
        if (File(path).exists()) TextButton(enabled = !busy, onClick = { perform { install(File(path)) } }) { Text("Install update") }
    }
    TextButton(enabled = !busy, onClick = { perform {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.releasesUrl)))
    } }) { Text("Open GitHub Releases") }
    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("Install downloaded APK") }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
