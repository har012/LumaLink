package com.lumalink.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** QR-shared random key, challenge authentication and independently authenticated records. */
internal object DirectProtocol {
    const val CHUNK = 32 * 1024
    fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    fun proof(key: ByteArray, nonce: ByteArray, id: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal("LumaLink/Direct/1/$id".toByteArray() + nonce)
    }
    fun checkProof(expected: ByteArray, actual: ByteArray) {
        require(MessageDigest.isEqual(expected, actual)) { "Wi-Fi session authentication failed" }
    }
    fun write(output: DataOutputStream, key: ByteArray, id: String, index: Int, bytes: ByteArray) {
        require(bytes.size <= CHUNK)
        val nonce = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$id/$index".toByteArray())
        val encrypted = cipher.doFinal(bytes)
        output.writeInt(encrypted.size); output.write(nonce); output.write(encrypted); output.flush()
    }
    fun read(input: DataInputStream, key: ByteArray, id: String, index: Int): ByteArray {
        val length = input.readInt()
        require(length in 16..CHUNK + 16) { "Invalid encrypted record size" }
        val nonce = ByteArray(12).also { input.readFully(it) }
        val encrypted = ByteArray(length).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$id/$index".toByteArray())
        return cipher.doFinal(encrypted)
    }
}
