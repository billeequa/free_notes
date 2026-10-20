package com.plainnotes.android.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncCryptoTest {
    private fun vector() = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())
    @Test fun kotlinMatchesTypescriptArgon2HkdfGcmAndRecoveryVectors() {
        val v=vector();val keys=SyncCrypto.derive(v.getString("password"),v.getJSONObject("bootstrap"))
        assertEquals(v.getString("auth"),keys.auth);assertEquals(v.getString("wrap"),SyncCrypto.b64(keys.wrap))
        val key=SyncCrypto.unb64(v.getString("key"));val e=v.getJSONObject("envelope");val aad=v.getJSONArray("aad").toString()
        val plain=SyncCrypto.open(key,e,aad)
        assertEquals(SyncPayload.canonical(v.getJSONObject("plaintext")),SyncPayload.canonical(JSONObject(String(plain,Charsets.UTF_8))))
        assertEquals(e.getString("ciphertext_with_tag"),SyncCrypto.seal(key,e.getString("key_id"),plain,aad,SyncCrypto.unb64(v.getString("nonce"))).getString("ciphertext_with_tag"))
        assertEquals(v.getString("recovery_code"),SyncCrypto.recoveryCode(key))
        assertEquals(v.getString("recovery_key"),SyncCrypto.b64(SyncCrypto.recoveryKey(v.getString("recovery_code"))))
    }
    @Test fun changedAadAndCiphertextCannotDecrypt() {
        val v=vector();val key=SyncCrypto.unb64(v.getString("key"));val aad=v.getJSONArray("aad")
        aad.put(6,8)
        assertThrows(Exception::class.java){SyncCrypto.open(key,v.getJSONObject("envelope"),aad.toString())}
        val e=JSONObject(v.getJSONObject("envelope").toString());val cipher=SyncCrypto.unb64(e.getString("ciphertext_with_tag"));cipher[0]=(cipher[0].toInt() xor 1).toByte();e.put("ciphertext_with_tag",SyncCrypto.b64(cipher))
        assertThrows(Exception::class.java){SyncCrypto.open(key,e,v.getJSONArray("aad").put(6,7).toString())}
    }

    @org.junit.Test fun workKeyMatchesBrowserVectorAndUsesDistinctAAD() {
        val master=ByteArray(32){it.toByte()}
        val work=SyncCrypto.expand(master,"workspace/work")
        assertEquals("8e1f33ba2bffb39b363e743982506013299cb00153e9b99f7dbf5e686949a915",work.joinToString(""){"%02x".format(it.toInt() and 255)})
        assertFalse(master.contentEquals(work))
        val aad=SyncCrypto.aad("deployment/work","owner","key","id",1,false)
        val envelope=SyncCrypto.seal(work,"key","Work text".toByteArray(),aad)
        assertEquals("Work text",String(SyncCrypto.open(work,envelope,aad)))
        try {SyncCrypto.open(master,envelope,SyncCrypto.aad("deployment","owner","key","id",1,false));fail("Work must not decrypt with Personal key/AAD") }catch(_:javax.crypto.AEADBadTagException){}
    }
}
