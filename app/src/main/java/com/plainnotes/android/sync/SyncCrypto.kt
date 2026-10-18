package com.plainnotes.android.sync

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire v1: see docs/web-sync/protocol.md and shared test vectors. Never logs secrets. */
object SyncCrypto {
    data class Credentials(val auth: String, val wrap: ByteArray)
    fun random(n: Int = 32) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    fun b64(a: ByteArray): String = Base64.getEncoder().encodeToString(a)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s).also { require(b64(it) == s) }
    fun expand(material: ByteArray, label: String): ByteArray {
        fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(message)
        }
        val prk = hmac(ByteArray(32), material)
        return try { hmac(prk, "jnotes.v1/$label".toByteArray(Charsets.UTF_8) + byteArrayOf(1)) } finally { prk.fill(0) }
    }
    fun derive(password: String, bootstrap: JSONObject): Credentials {
        require(bootstrap.getInt("protocol_version") == 1 && bootstrap.getInt("memory_kib") == 65536 &&
            bootstrap.getInt("iterations") == 3 && bootstrap.getInt("parallelism") == 1) { "Unsupported cloud KDF configuration" }
        val salt = unb64(bootstrap.getString("kdf_salt")).also { require(it.size == 16) }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(1).withSalt(salt).build()
        val intermediate = ByteArray(32)
        val bytes = password.toByteArray(Charsets.UTF_8)
        try {
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(bytes, intermediate)
            return Credentials(b64(expand(intermediate, "auth")), expand(intermediate, "wrap"))
        } finally { intermediate.fill(0); bytes.fill(0); parameters.clear() }
    }
    fun aad(deployment: String, owner: String, key: String, id: String, revision: Long, deleted: Boolean) =
        JSONArray(listOf("jnotes.record", 1, deployment, owner, key, id, revision, deleted)).toString()
    fun vaultAAD(deployment: String, owner: String, key: String, purpose: String) =
        JSONArray(listOf("jnotes.vault", 1, deployment, owner, key, purpose, 0, false)).toString()
    fun seal(key: ByteArray, keyId: String, plain: ByteArray, aad: String, nonce: ByteArray = random(12)): JSONObject {
        require(key.size == 32 && nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return JSONObject().put("protocol_version", 1).put("key_id", keyId).put("algorithm", "A256GCM")
            .put("nonce", b64(nonce)).put("ciphertext_with_tag", b64(cipher.doFinal(plain)))
    }
    fun open(key: ByteArray, envelope: JSONObject, aad: String): ByteArray {
        require(envelope.getInt("protocol_version") == 1 && envelope.getString("algorithm") == "A256GCM")
        require(envelope.getString("key_id") == JSONArray(aad).getString(4))
        val nonce = unb64(envelope.getString("nonce")).also { require(it.size == 12) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(unb64(envelope.getString("ciphertext_with_tag")))
    }
    fun recoveryCode(secret: ByteArray = random()): String {
        require(secret.size == 32)
        fun hex(a: ByteArray) = a.joinToString("") { "%02X".format(it.toInt() and 255) }
        val checked = hex(secret) + hex(MessageDigest.getInstance("SHA-256").digest(secret).copyOf(4))
        return "JN1-" + checked.chunked(8).joinToString("-")
    }
    fun recoveryKey(code: String): ByteArray {
        val text = code.trim().uppercase(java.util.Locale.ROOT).removePrefix("JN1-").replace("-", "")
        require(Regex("[0-9A-F]{72}").matches(text)) { "Invalid recovery code" }
        val secret = text.take(64).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(recoveryCode(secret).removePrefix("JN1-").replace("-", "") == text) { "Recovery code checksum failed" }
        return try { expand(secret, "recovery") } finally { secret.fill(0) }
    }
}
