package com.plainnotes.android.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Only public API key and derived authentication credential leave the client. */
class SyncApi(url: String, val publicKey: String, var session: JSONObject? = null, val connection: SyncConnection.Service? = null) {
    val url = SyncConnection.origin(url)
    init { SyncConnection.checkPublicKey(publicKey) }
    fun request(path: String, body: JSONObject? = null, method: String = if(body==null)"GET" else "POST", authorized: Boolean = true): String {
        val c=URL(url.trimEnd('/')+path).openConnection() as HttpURLConnection
        c.connectTimeout=15000;c.readTimeout=20000;c.instanceFollowRedirects=false;c.requestMethod=method
        c.setRequestProperty("apikey",publicKey);c.setRequestProperty("Cache-Control","no-store");c.setRequestProperty("Content-Type","application/json")
        if(authorized) session?.optString("access_token")?.takeIf {it.isNotBlank()}?.let {c.setRequestProperty("Authorization","Bearer $it")}
        try {
            if(body!=null){c.doOutput=true;c.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val status=c.responseCode
            val stream=if(status in 200..299)c.inputStream else c.errorStream
            val response=stream?.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4*1024*1024){"Cloud response too large"};out.write(buffer,0,n)}
                String(out.toByteArray(),Charsets.UTF_8)
            }?:""
            if(status !in 200..299){
                if(response.contains("CLOUD_STATE_CHANGED")) throw CloudChanged()
                if(path.startsWith("/auth/v1/token?grant_type=password") && (status==400||status==401))throw InvalidCredentials()
                if(status==401||status==403||response.contains("NOT_AUTHORIZED"))throw AuthRequired()
                throw IOException("Cloud request failed (HTTP $status). Phone files are safe.")
            }
            return response
        }finally{c.disconnect()}
    }
    fun bootstrap(): JSONObject = JSONArray(request("/rest/v1/public_bootstrap?select=*",authorized=false)).getJSONObject(0)
    fun signIn(email: String, auth: String): JSONObject = JSONObject(request("/auth/v1/token?grant_type=password",JSONObject().put("email",email).put("password",auth),authorized=false)).also {session=it}
    fun refresh(): JSONObject = JSONObject(request("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",session!!.getString("refresh_token")),authorized=false)).also {session=it}
    fun rpc(name: String, args: JSONObject = JSONObject()): String = request("/rest/v1/rpc/$name",args)
    fun vault(): JSONObject = JSONObject(rpc("get_vault"))
    class InvalidCredentials : IOException("Email or password is incorrect. First time? Tap Set up my password on the website below.")
    class SetupRequired : IOException("Finish setting up your password on the website first, then sign in here.")
    class CloudChanged : IOException("Cloud state changed — repair required. Phone files are safe.")
    class AuthRequired : IOException("Account recovery needed. Phone files are still readable and editable.")
}
