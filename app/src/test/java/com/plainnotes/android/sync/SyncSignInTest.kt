package com.plainnotes.android.sync

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncSignInTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val directory get()=JSONObject(context.assets.open("jnotes-service.json").bufferedReader().use{it.readText()})
    private val bootstrap get()=JSONObject().put("deployment_id",directory.getString("deployment_id")).put("protocol_version",1)
        .put("kdf_salt","AAAAAAAAAAAAAAAAAAAAAA==").put("memory_kib",65536).put("iterations",3).put("parallelism",1)
    private val password="synthetic notebook password"
    @Before fun reset() {
        SyncRuntime.prefs(context).edit().clear().commit()
        val db=SyncRuntime.store(context).writableDatabase
        for(table in listOf("outbox","conflicts","records","meta"))db.execSQL("DELETE FROM $table")
    }
    private fun fixture(vault: JSONObject?, invalid: Boolean=false, block:(SyncApi,ConcurrentLinkedQueue<String>)->Unit) {
        val requests=ConcurrentLinkedQueue<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){exchange->
            val path=exchange.requestURI.path;requests.add(exchange.requestMethod+" "+path)
            val body=exchange.requestBody.bufferedReader().use{it.readText()}
            val response=when(path){
                "/rest/v1/public_bootstrap"->JSONArray().put(bootstrap).toString()
                "/auth/v1/token"->{
                    val input=JSONObject(body)
                    assertEquals("owner@example.test",input.getString("email"));assertNotEquals(password,input.getString("password"))
                    assertEquals(SyncCrypto.derive(password,bootstrap).let{it.wrap.fill(0);it.auth},input.getString("password"))
                    if(invalid)"{\"error_code\":\"invalid_credentials\"}" else "{\"user\":{\"id\":\"owner\"},\"access_token\":\"synthetic-session\"}"
                }
                "/rest/v1/rpc/get_vault"->JSONObject().put("epoch","epoch").put("high_water",0).put("vault",vault ?: JSONObject.NULL).toString()
                else->"{}"
            }.toByteArray()
            exchange.sendResponseHeaders(if(invalid&&path=="/auth/v1/token")400 else 200,response.size.toLong())
            exchange.responseBody.use{it.write(response)}
        }
        server.start()
        val service=SyncConnection.parse(directory).copy(backend="http://127.0.0.1:${server.address.port}",custom=true)
        try{block(SyncApi(service.backend,service.publicKey,connection=service),requests)}finally{server.stop(0)}
    }
    @Test fun passwordSignInOpensExistingWebsiteNotebookWithoutSendingMailOrChangingCredentials() {
        val key=SyncCrypto.random();val keyId="key"
        val credentials=SyncCrypto.derive(password,bootstrap)
        val envelope=try{SyncCrypto.seal(credentials.wrap,keyId,key,SyncCrypto.vaultAAD(bootstrap.getString("deployment_id"),"owner",keyId,"password"))}finally{credentials.wrap.fill(0)}
        val vault=JSONObject().put("key_id",keyId).put("password_envelope",envelope)
        fixture(vault){api,requests->
            val setup=SyncRuntime.signIn(context,api," owner@example.test ",password)
            assertArrayEquals(key,setup.key);assertNull(setup.recoveryCode);assertFalse(SyncRuntime.enabled(context))
            assertEquals(listOf("GET /rest/v1/public_bootstrap","POST /auth/v1/token","POST /rest/v1/rpc/get_vault","POST /rest/v1/rpc/get_vault"),requests.toList())
            setup.key.fill(0)
        }
    }
    @Test fun missingNotebookDirectsSetupToWebsiteWithoutCreatingOrResettingAnything() {
        fixture(null){api,requests->
            assertThrows(SyncApi.SetupRequired::class.java){SyncRuntime.signIn(context,api,"owner@example.test",password)}
            assertNull(api.session);assertEquals(3,requests.size)
        }
    }
    @Test fun wrongPasswordHasActionableMessageAndDoesNotSendEmailOrChangeVault() {
        fixture(null,invalid=true){api,requests->
            val error=assertThrows(SyncApi.InvalidCredentials::class.java){SyncRuntime.signIn(context,api,"owner@example.test",password)}
            assertTrue(error.message!!.contains("Set up my password"));assertEquals(2,requests.size);assertNull(api.session)
        }
    }
}
