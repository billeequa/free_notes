package com.plainnotes.android.sync

import com.plainnotes.android.BuildConfig
import com.plainnotes.android.data.TodoItem
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncConnectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = SyncRuntime.prefs(context)
    private fun directory() = JSONObject(context.assets.open("jnotes-service.json").bufferedReader().use { it.readText() })
    private fun bootstrap(deployment: String = directory().getString("deployment_id")) = JSONObject()
        .put("deployment_id",deployment).put("protocol_version",1).put("kdf_salt","AAAAAAAAAAAAAAAAAAAAAA==")
        .put("memory_kib",65536).put("iterations",3).put("parallelism",1)
    private fun session() = JSONObject().put("session",JSONObject().put("refresh_token","never-forward-me").put("access_token","old-access"))
        .put("api_origin",directory().getString("supabase_url"))
    @Before fun reset() {
        prefs.edit().clear().commit()
        val db = SyncRuntime.store(context).writableDatabase
        for(table in listOf("outbox","conflicts","records","meta")) db.execSQL("DELETE FROM $table")
    }
    @Test fun freshSetupDiscoversJNotesWithoutAnyUserAddressAndProbesWithoutSession() {
        val result = SyncConnection.prepare(context,"",fetch={ url ->
            assertEquals(BuildConfig.SYNC_DIRECTORY_URL,url); directory()
        },probe={ api -> assertNull(api.session); bootstrap() })
        assertEquals(directory().getString("supabase_url"),result.api.url)
        assertEquals(directory().getString("web_url"),SyncConnection.website(context))
        assertFalse(SyncRuntime.enabled(context))
    }
    @Test fun backendMoveRequiresFreshLoginWithoutChangingOutboxOrForwardingOldSession() {
        val store = SyncRuntime.store(context)
        val task = TodoItem(title="Keep this pending edit")
        store.capture("task:${task.id}",SyncPayload.task(task),false)
        store.setMeta("deployment",directory().getString("deployment_id")); store.setMeta("epoch","original"); store.setMeta("floor","17")
        val pending = store.pending(); val secret = session(); val before = secret.toString(); var probes = 0
        val moved = directory().put("revision",2).put("supabase_url","https://new-backend.example.test").put("web_url","https://new-notes.example.test")
        assertThrows(SyncConnection.ReconnectRequired::class.java) {
            SyncConnection.prepareSession(context,secret,store.meta("deployment"),fetch={moved},probe={ api ->
                probes++; assertNull(api.session); assertEquals("https://new-backend.example.test",api.url); bootstrap()
            })
        }
        assertEquals(1,probes); assertEquals(before,secret.toString()); assertEquals(pending,store.pending())
        assertEquals("original",store.meta("epoch")); assertEquals("17",store.meta("floor"))
        assertEquals("https://new-notes.example.test",SyncConnection.website(context))
        assertNull(prefs.getString("url",null))
    }
    @Test fun sameOriginKeyRotationAndWebsiteMoveReuseSessionAfterAnonymousIdentityCheck() {
        val changed = directory().put("revision",2).put("publishable_key","sb_publishable_rotated").put("web_url","https://notes.example.test")
        val result = SyncConnection.prepareSession(context,session(),directory().getString("deployment_id"),fetch={changed},probe={api ->
            assertNull(api.session); assertEquals("sb_publishable_rotated",api.publicKey); bootstrap()
        })
        assertEquals("never-forward-me",result.api.session!!.getString("refresh_token"))
        SyncConnection.saveActive(context,result.service)
        assertEquals("sb_publishable_rotated",prefs.getString("public_key",null))
        assertEquals("https://notes.example.test",SyncConnection.website(context))
    }
    @Test fun legacyConfiguredPhoneKeepsItsSessionOnUnchangedBackend() {
        val secret = session().also { it.remove("api_origin") }
        prefs.edit().putString("web",directory().getString("web_url")+"/").putString("url",directory().getString("supabase_url")+"/").commit()
        val result = SyncConnection.prepareSession(context,secret,directory().getString("deployment_id"),fetch={directory()},probe={bootstrap()})
        assertEquals("old-access",result.api.session!!.getString("access_token"))
        assertFalse(result.service.custom)
    }
    @Test fun directoryOutageFallsBackToShippedOrPreviouslyValidatedConfiguration() {
        val initial = SyncConnection.prepare(context,"",fetch={throw IOException("offline")},probe={bootstrap()})
        assertEquals(directory().getString("supabase_url"),initial.api.url)
        val moved = directory().put("revision",3).put("supabase_url","https://moved.example.test")
        SyncConnection.prepare(context,"",fetch={moved},probe={bootstrap()})
        val result = SyncConnection.prepare(context,"",fetch={throw IOException("directory down")},probe={bootstrap()})
        assertEquals("https://moved.example.test",result.api.url)
        val stale = SyncConnection.prepare(context,"",fetch={directory()},probe={bootstrap()})
        assertEquals("https://moved.example.test",stale.api.url)
    }
    @Test fun invalidDirectoryAndUnsupportedProtocolDoNotSilentlyUseCachedService() {
        SyncConnection.prepare(context,"",fetch={directory()},probe={bootstrap()})
        for(change in listOf(directory().put("schema_version",2),directory().put("protocol_version",2),directory().put("publishable_key","sb_secret_do-not-send"))) {
            assertThrows(IllegalArgumentException::class.java) {
                SyncConnection.prepare(context,"",fetch={change},probe={fail("must not contact invalid destination");bootstrap()})
            }
        }
        assertThrows(SyncConnection.ConfigurationChanged::class.java) {
            SyncConnection.prepare(context,"",fetch={directory().put("supabase_url","https://changed-without-revision.test")},probe={bootstrap()})
        }
    }
    @Test fun slowOlderDiscoveryCannotOverwriteANewerValidatedDirectory() {
        val older = directory().put("revision",2).put("web_url","https://older.example.test")
        val newer = directory().put("revision",3).put("web_url","https://newer.example.test")
        SyncConnection.prepare(context,"",fetch={older},probe={
            SyncConnection.prepare(context,"",fetch={newer},probe={bootstrap()})
            bootstrap()
        })
        assertEquals("https://newer.example.test",SyncConnection.website(context))
    }
    @Test fun wrongDeploymentAndUnrelatedBootstrapNeverReceiveSessionOrAdvanceDirectory() {
        assertThrows(SyncConnection.ConfigurationChanged::class.java) {
            SyncConnection.prepareSession(context,session(),directory().getString("deployment_id"),fetch={directory().put("revision",2)},probe={api ->
                assertNull(api.session); bootstrap("11111111-1111-1111-1111-111111111111")
            })
        }
        assertNull(prefs.getString("service_directory",null))
        assertThrows(SyncConnection.ConfigurationChanged::class.java) {
            SyncConnection.prepare(context,"11111111-1111-1111-1111-111111111111",fetch={directory()},probe={fail("wrong deployment must stop before contact");bootstrap()})
        }
    }
    @Test fun selfHostedLegacyConnectionIsPreservedAndRefreshesItsOwnPublicConfig() {
        prefs.edit().putString("web","https://private-host.example.test/").commit()
        val deployment = "11111111-1111-1111-1111-111111111111"
        val result = SyncConnection.prepare(context,deployment,fetch={url ->
            assertEquals("https://private-host.example.test/config.json",url)
            JSONObject().put("supabase_url","https://private-api.example.test").put("publishable_key","sb_publishable_custom")
        },probe={api -> assertNull(api.session); bootstrap(deployment)})
        assertTrue(result.service.custom); assertEquals("https://private-api.example.test",result.api.url)
        assertNull(prefs.getString("service_directory",null))
    }
    @Test fun missingMigratedVaultCannotCreateANewVaultOrChangePassword() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(exchange.requestMethod+" "+path)
            val response = when(path) {
                "/rest/v1/public_bootstrap" -> org.json.JSONArray().put(bootstrap()).toString()
                "/auth/v1/verify" -> JSONObject().put("user",JSONObject().put("id","owner")).put("access_token","synthetic-session").toString()
                "/rest/v1/rpc/get_vault" -> JSONObject().put("epoch","new").put("high_water",0).toString()
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(200,response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val service = SyncConnection.parse(directory()).copy(backend="http://127.0.0.1:${server.address.port}",custom=true)
        val store = SyncRuntime.store(context)
        store.setMeta("deployment",service.deployment); store.setMeta("owner","owner"); store.setMeta("key_id","original-key")
        val api = SyncApi(service.backend,service.publicKey,connection=service)
        try {
            assertThrows(SyncApi.CloudChanged::class.java) { SyncRuntime.verify(context,api,"test@example.test","test-code","synthetic password") }
            assertEquals(listOf("GET /rest/v1/public_bootstrap","POST /auth/v1/verify","POST /rest/v1/rpc/get_vault"),requests.toList())
            assertEquals("original-key",store.meta("key_id"))
        } finally { server.stop(0) }
    }
    @Test fun serviceOriginsRejectCredentialsPathsAndInsecureNonlocalEndpoints() {
        for(value in listOf("http://example.test","ftp://localhost","https://me:password@example.test","https://example.test/path","https://example.test?token=secret","https://example.test/#fragment"))
            assertThrows(IllegalArgumentException::class.java) { SyncConnection.origin(value) }
        assertEquals("https://example.test",SyncConnection.origin("https://EXAMPLE.test:443/"))
    }
    @Test fun discoveryFetchIsBoundedAnonymousAndDoesNotFollowRedirects() {
        val targetRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/large") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Authorization")); assertNull(exchange.requestHeaders.getFirst("apikey"))
            val bytes = "x".repeat(9000).toByteArray(); exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange -> exchange.responseHeaders.add("Location","/target"); exchange.sendResponseHeaders(302,-1); exchange.close() }
        server.createContext("/target") { exchange -> targetRequests.incrementAndGet(); exchange.sendResponseHeaders(200,2); exchange.responseBody.use { it.write("{}".toByteArray()) } }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertThrows(IllegalArgumentException::class.java) { SyncConnection.fetchJSON("$base/large") }
            assertThrows(IOException::class.java) { SyncConnection.fetchJSON("$base/redirect") }
            assertEquals(0,targetRequests.get())
        } finally { server.stop(0) }
    }
}
