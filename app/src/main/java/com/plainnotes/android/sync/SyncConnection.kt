package com.plainnotes.android.sync

import android.content.Context
import com.plainnotes.android.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Public service discovery only. Never send an email, session, password or vault key here. */
object SyncConnection {
    private const val CACHE = "service_directory"
    private const val LIMIT = 8192
    private val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    class ConfigurationChanged : IOException("The connection configuration changed unexpectedly. Sync is paused; phone files are safe.")
    class ReconnectRequired : IOException("The service has moved. Reconnect Web Sync with your email and password. Phone files and pending edits are safe.")

    data class Service(val revision: Int, val deployment: String, val web: String, val backend: String, val publicKey: String, val custom: Boolean = false) {
        fun json() = JSONObject().put("schema_version",1).put("protocol_version",1).put("revision",revision)
            .put("deployment_id",deployment).put("web_url",web).put("supabase_url",backend).put("publishable_key",publicKey)
    }
    data class Prepared(val service: Service, val api: SyncApi, val bootstrap: JSONObject)

    fun origin(value: String): String {
        val u = URI(value)
        require(u.isAbsolute && u.host != null && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            (u.rawPath.isNullOrEmpty() || u.rawPath == "/") && (u.port == -1 || u.port in 1..65535)) { "Invalid service address" }
        require(u.scheme == "https" || BuildConfig.DEBUG && u.scheme == "http" && u.host in listOf("localhost","127.0.0.1","10.0.2.2")) { "HTTPS service required" }
        return URI(u.scheme, null, u.host.lowercase(), if(u.port == 443 && u.scheme == "https") -1 else u.port, null, null, null).toString()
    }
    fun checkPublicKey(value: String) {
        require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid public API key" }
        if(value.startsWith("sb_publishable_")) return
        val claims = runCatching { JSONObject(String(java.util.Base64.getUrlDecoder().decode(value.split('.')[1]),Charsets.UTF_8)) }.getOrNull()
        require(claims?.optString("role") == "anon") { "Use a public API key, never an admin key" }
    }
    fun parse(json: JSONObject): Service {
        require(json.getInt("schema_version") == 1 && json.getInt("protocol_version") == 1) { "Update JNotes to use this service version" }
        val revision = json.getInt("revision"); require(revision > 0)
        val deployment = json.getString("deployment_id"); require(uuid.matches(deployment))
        val key = json.getString("publishable_key"); checkPublicKey(key)
        return Service(revision,deployment,origin(json.getString("web_url")),origin(json.getString("supabase_url")),key)
    }
    private fun bundled(c: Context) = parse(JSONObject(c.assets.open("jnotes-service.json").bufferedReader().use { it.readText() }))
    private fun cached(c: Context): Service? = SyncRuntime.prefs(c).getString(CACHE,null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    fun website(c: Context): String = customWebsite(c) ?: cached(c)?.web ?: bundled(c).web
    fun customWebsite(c: Context): String? {
        val prefs = SyncRuntime.prefs(c)
        if(prefs.getString("connection_mode",null) == "managed") return null
        val web = prefs.getString("web",null)?.takeIf { it.isNotBlank() } ?: return null
        // Preserve explicitly configured self-hosted installations from 1.5.0/1.5.1.
        return if(prefs.getString("connection_mode",null) == "custom" || origin(web) != bundled(c).web) origin(web) else null
    }
    fun fetchJSON(address: String): JSONObject {
        val url = URL(address)
        require(url.protocol == "https" || BuildConfig.DEBUG && url.protocol == "http" && url.host in listOf("localhost","127.0.0.1","10.0.2.2"))
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("Cache-Control","no-cache, no-store")
        connection.setRequestProperty("Accept","application/json")
        connection.setRequestProperty("User-Agent","JNotes/${BuildConfig.VERSION_NAME}")
        try {
            if(connection.responseCode != 200) throw IOException("Service configuration unavailable")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(1024)
                while(true) {
                    val count = input.read(buffer); if(count < 0) break
                    require(output.size() + count <= LIMIT) { "Service configuration too large" }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    fun prepare(c: Context, expectedDeployment: String, customWeb: String? = customWebsite(c),
                fetch: (String) -> JSONObject = ::fetchJSON, probe: (SyncApi) -> JSONObject = { it.bootstrap() }): Prepared {
        val service: Service
        var bootstrap: JSONObject? = null
        if(customWeb != null) {
            val web = origin(customWeb)
            val config = fetch("$web/config.json")
            val backend = origin(config.getString("supabase_url")); val key = config.getString("publishable_key"); checkPublicKey(key)
            bootstrap = probe(SyncApi(backend,key))
            service = Service(1,bootstrap.getString("deployment_id"),web,backend,key,true)
        } else {
            val bundled = bundled(c); val last = cached(c) ?: bundled
            // An outage may use the last validated directory (or the shipped copy on first use).
            // Invalid, incompatible and differently identified responses fail closed instead.
            val fresh = try { fetch(BuildConfig.SYNC_DIRECTORY_URL) } catch(_: IOException) { null }
            service = if(fresh == null) last else parse(fresh).also {
                if(it.deployment != bundled.deployment || it.deployment != last.deployment) throw ConfigurationChanged()
                if(it.revision == last.revision && it != last) throw ConfigurationChanged()
            }.let { if(it.revision < last.revision) last else it }
        }
        if(expectedDeployment.isNotEmpty() && expectedDeployment != service.deployment) throw ConfigurationChanged()
        val api = SyncApi(service.backend,service.publicKey,connection=service)
        val b = bootstrap ?: probe(api)
        if(b.getInt("protocol_version") != 1 || b.getString("deployment_id") != service.deployment) throw ConfigurationChanged()
        if(!service.custom) {
            // Only a successful anonymous identity check can advance the cached directory.
            synchronized(this) {
                val latest = cached(c)
                if(latest != null && latest.revision == service.revision && latest != service) throw ConfigurationChanged()
                if(latest == null || latest.revision < service.revision)
                    SyncRuntime.prefs(c).edit().putString(CACHE,service.json().toString()).apply()
            }
        }
        return Prepared(service,api,b)
    }
    fun prepareSession(c: Context, secret: JSONObject, expectedDeployment: String,
                       fetch: (String) -> JSONObject = ::fetchJSON, probe: (SyncApi) -> JSONObject = { it.bootstrap() }): Prepared {
        val prepared = prepare(c,expectedDeployment,fetch=fetch,probe=probe)
        val priorOrigin = secret.optString("api_origin").ifBlank { SyncRuntime.prefs(c).getString("url","")!! }
        // A public deployment UUID is not proof that another host may receive old credentials.
        // Verify the new connection anonymously; require fresh sign-in on a cross-origin move.
        if(runCatching { origin(priorOrigin) }.getOrNull() != prepared.service.backend) throw ReconnectRequired()
        prepared.api.session = secret.getJSONObject("session")
        return prepared
    }
    fun saveActive(c: Context, service: Service) {
        SyncRuntime.prefs(c).edit().putString("url",service.backend).putString("public_key",service.publicKey).putString("web",service.web)
            .putString("connection_mode",if(service.custom) "custom" else "managed").commit()
    }
}
