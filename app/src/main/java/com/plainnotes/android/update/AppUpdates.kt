package com.plainnotes.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.plainnotes.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** No credentials or note content are sent. Private releases use the signed-in browser. */
object AppUpdates {
    val releasesUrl = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"
    data class Release(val versionCode: Long, val label: String, val apkUrl: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val connection = connect("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
        try {
            when (connection.responseCode) {
                404 -> error("No public release is available. For this private repository, open Releases and sign in to GitHub.")
                403, 429 -> error("GitHub's update check is temporarily unavailable. Try later or open Releases.")
            }
            require(connection.responseCode == 200) { "Cannot check updates (${connection.responseCode}). Try again later." }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = release.getJSONArray("assets")
            (0 until assets.length()).mapNotNull { index ->
                val asset = assets.getJSONObject(index)
                val code = Regex("jnotes-(\\d+)\\.apk").matchEntire(asset.getString("name"))?.groupValues?.get(1)?.toLongOrNull()
                code?.let { Release(it, release.optString("name", release.getString("tag_name")), asset.getString("browser_download_url")) }
            }.maxByOrNull { it.versionCode }
                ?: error("This release has no update APK. Open Releases for details.")
        } finally { connection.disconnect() }
    }

    private fun connect(address: String): HttpURLConnection {
        val url = URL(address)
        require(url.protocol == "https") { "Updates require HTTPS." }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Jnotes/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val uri = Uri.parse(release.apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.path.orEmpty().startsWith("/${BuildConfig.UPDATE_REPOSITORY}/releases/download/")) { "Unrecognized release download location." }
        val connection = connect(release.apkUrl)
        try {
            require(connection.responseCode == 200) { "Download failed (${connection.responseCode})." }
            copyAndValidate(context) { target -> connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 100L * 1024 * 1024) { "Update file is too large." }
                    output.write(buffer, 0, n)
                }
            } } }.also { file ->
                require(packageCode(context, file) == release.versionCode) { "Downloaded version does not match the release." }
            }
        } finally { connection.disconnect() }
    }

    suspend fun importApk(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        copyAndValidate(context) { target ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 100L * 1024 * 1024) { "Update file is too large." }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Cannot read the selected APK.")
        }
    }

    private fun copyAndValidate(context: Context, copy: (File) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        val partial = File(directory, "update.part")
        try {
            copy(partial)
            validate(context, partial)
            target.delete()
            check(partial.renameTo(target)) { "Could not prepare update." }
            return target
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    private fun packageCode(context: Context, file: File): Long {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("Invalid APK.")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun validate(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.path, flags) ?: error("This file is not a valid APK.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(candidate.packageName == context.packageName) { "This APK is for a different app." }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
        val signers = signatures(installed)
        require(signers.isNotEmpty() && signatures(candidate) == signers) { "This APK uses a different signing key and cannot update this installation. Your notes have not been changed." }
        require(packageCode(context, file) > BuildConfig.VERSION_CODE) { "Choose a newer version of Jnotes." }
    }

    fun install(context: Context, file: File): Boolean {
        validate(context, file)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
