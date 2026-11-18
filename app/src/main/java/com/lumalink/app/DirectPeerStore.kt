package com.lumalink.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SavedDirectPeer(val identity: DirectIdentity, val secret: ByteArray) {
    val tag: String get() = Codec.hash(secret).take(24)
}

/** Profiles/credentials are encrypted with a non-exportable Android Keystore key, excluded from backup. */
internal class DirectPeerStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: DirectPeerStore? = null
        @Synchronized fun get(context: Context): DirectPeerStore = instance ?: DirectPeerStore(context.applicationContext).also { instance = it }
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "direct-peers.bin"))
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("lumalink-direct-profiles", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("lumalink-direct-profiles", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    private var document: JSONObject = if (file.baseFile.exists()) {
        val bytes = file.readFully()
        require(bytes.size > 28) { "Saved device profiles are damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    } else JSONObject().put("identity", DirectIdentity(UUID.randomUUID().toString(), Build.MODEL.take(100).ifBlank { "Android phone" }).json())
        .put("peers", JSONArray()).also { write(it) }

    val identity: DirectIdentity get() = DirectIdentity.parse(document.getJSONObject("identity"))
    @Synchronized fun list(): List<SavedDirectPeer> {
        val peers = document.getJSONArray("peers")
        return (0 until peers.length()).map { index ->
            val peer = peers.getJSONObject(index)
            SavedDirectPeer(DirectIdentity.parse(peer.getJSONObject("identity")), Codec.un64(peer.getString("secret")).also { require(it.size == 32) })
        }
    }
    @Synchronized fun remember(identity: DirectIdentity, secret: ByteArray) {
        require(secret.size == 32)
        val remaining = list().filter { it.identity.id != identity.id }.takeLast(49) + SavedDirectPeer(identity, secret)
        replace(remaining)
    }
    @Synchronized fun forget(id: String) { replace(list().filter { it.identity.id != id }) }
    private fun replace(peers: List<SavedDirectPeer>) {
        val next = JSONObject(document.toString()).put("peers", JSONArray().apply {
            peers.forEach { put(JSONObject().put("identity", it.identity.json()).put("secret", Codec.b64(it.secret))) }
        })
        write(next); document = next
    }
    private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
