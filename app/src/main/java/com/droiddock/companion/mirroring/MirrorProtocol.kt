package com.droiddock.companion.mirroring

import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MirrorOffer(val sessionId: String, val key: String, val port: Int,
    val maxSize: Int = 1280, val fps: Int = 60, val bitrate: Int = 4_000_000,
    val audio: Boolean = false, val packageName: String? = null)

data class MirrorStatus(val sessionId: String, val state: String, val message: String)
data class MirrorMetadata(val sessionId: String, val width: Int, val height: Int,
    val control: Boolean, val audio: Boolean, val message: String? = null)

class MirrorCipher(key: ByteArray, sessionId: String, private val isMac: Boolean = false) {
    companion object { const val MAX_RECORD = VideoPacket.MAX_PAYLOAD + 128 }
    private val keySpec = SecretKeySpec(key, "AES")
    private val session = sessionId.toByteArray(Charsets.UTF_8)
    private var sent = 0L
    private var received = 0L
    init { require(key.size == 32); UUID.fromString(sessionId) }
    private fun nonce(direction: Int, sequence: Long) = ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array()
    @Synchronized fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size + 24 <= MAX_RECORD && sent < Long.MAX_VALUE)
        val sequence = sent + 1
        val iv = nonce(if (isMac) 2 else 1, sequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(session + iv)
        val record = ByteBuffer.allocate(8).putLong(sequence).array() + cipher.doFinal(plaintext)
        sent = sequence
        return record
    }
    @Synchronized fun open(record: ByteArray): ByteArray {
        require(record.size in 25..MAX_RECORD && received < Long.MAX_VALUE)
        val sequence = ByteBuffer.wrap(record).long
        require(sequence == received + 1) { "Out-of-order media record" }
        val iv = nonce(if (isMac) 1 else 2, sequence)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(session + iv)
        val plaintext = cipher.doFinal(record, 8, record.size - 8)
        received = sequence
        return plaintext
    }
}
