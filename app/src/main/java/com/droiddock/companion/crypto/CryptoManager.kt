package com.droiddock.companion.crypto

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager(private val context: Context) {

    private val secureStorage = SecureStorage(context)
    private var keyPair: KeyPair
    private var sharedKey: SecretKey? = null
    private var outboundSequence: Long = 0
    private var lastInboundSequence: Long = 0

    init {
        keyPair = loadOrGenerateKeyPair()
        secureStorage.getStoredPeerPublicKey()?.let { remotePubBase64 ->
            try {
                establishSharedKey(remotePubBase64)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        val storedPrivate = secureStorage.getPrivateKey()
        val storedPublic = secureStorage.getPublicKey()

        if (storedPrivate != null && storedPublic != null) {
            try {
                val kf = KeyFactory.getInstance("EC")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(storedPrivate))
                val pubKey = kf.generatePublic(X509EncodedKeySpec(storedPublic))
                return KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val newKp = kpg.generateKeyPair()

        secureStorage.savePrivateKey(newKp.private.encoded)
        secureStorage.savePublicKey(newKp.public.encoded)
        return newKp
    }

    /**
     * Exports public key in ANSI X9.63 uncompressed format (65 bytes: 0x04 + X + Y), Base64 encoded.
     */
    val localPublicKeyBase64: String
        get() {
            val ecPub = keyPair.public as java.security.interfaces.ECPublicKey
            val w = ecPub.w
            val x = toFixed32(w.affineX)
            val y = toFixed32(w.affineY)
            val raw = ByteArray(65)
            raw[0] = 0x04
            System.arraycopy(x, 0, raw, 1, 32)
            System.arraycopy(y, 0, raw, 33, 32)
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        }

    /**
     * 6-digit cryptographic verification code for pairing confirmation.
     */
    val verificationFingerprint: String
        get() {
            val pubBytes = Base64.decode(localPublicKeyBase64, Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
            val v0 = digest[0].toInt() and 0xFF
            val v1 = digest[1].toInt() and 0xFF
            val v2 = digest[2].toInt() and 0xFF
            val code = ((v0 shl 16) or (v1 shl 8) or v2) % 1_000_000
            return String.format(java.util.Locale.ROOT, "%06d", code)
        }

    /**
     * Performs ECDH Key Agreement with the remote Mac's ANSI X9.63 public key and derives AES-256-GCM key.
     */
    @Synchronized
    fun establishSharedKey(remotePublicKeyBase64: String, salt: String = "DroidDock_E2EE_v1", persistPeer: Boolean = true) {
        val raw = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid ANSI X9.63 public key" }

        val xBytes = raw.copyOfRange(1, 33)
        val yBytes = raw.copyOfRange(33, 65)
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val params = (keyPair.public as java.security.interfaces.ECPublicKey).params
        val spec = ECPublicKeySpec(point, params)
        val kf = KeyFactory.getInstance("EC")
        val remotePublicKey = kf.generatePublic(spec)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(remotePublicKey, true)
        val sharedSecret = ka.generateSecret()

        // HKDF-SHA256
        val symmetricKeyBytes = hkdf(
            ikm = sharedSecret,
            salt = salt.toByteArray(Charsets.UTF_8),
            info = "DroidDock_AES256GCM_Channel".toByteArray(Charsets.UTF_8),
            length = 32
        )

        this.sharedKey = SecretKeySpec(symmetricKeyBytes, "AES")
        if (persistPeer) secureStorage.savePeerPublicKey(remotePublicKeyBase64)
        outboundSequence = 0
        lastInboundSequence = 0
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Wire format: [8 bytes seq][12 bytes IV][Ciphertext + 16 bytes GCM Tag]
     */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("Shared key not established")
        outboundSequence++
        val seq = outboundSequence

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        cipher.updateAAD(ByteBuffer.allocate(8).putLong(seq).array())
        val ciphertextAndTag = cipher.doFinal(plaintext)

        val buffer = ByteBuffer.allocate(8 + 12 + ciphertextAndTag.size)
        buffer.putLong(seq)
        buffer.put(iv)
        buffer.put(ciphertextAndTag)
        return buffer.array()
    }

    /**
     * Decrypts ciphertext bytes using AES-256-GCM and verifies anti-replay sequence ordering.
     */
    @Synchronized
    fun decrypt(packet: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("Shared key not established")
        require(packet.size >= 36) { "Malformed encrypted packet" }

        val buffer = ByteBuffer.wrap(packet)
        val seq = buffer.long
        val iv = ByteArray(12)
        buffer.get(iv)

        val ciphertextAndTag = ByteArray(buffer.remaining())
        buffer.get(ciphertextAndTag)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        cipher.updateAAD(packet.copyOfRange(0, 8))
        val decrypted = cipher.doFinal(ciphertextAndTag)

        if (seq <= lastInboundSequence) {
            throw SecurityException("Replay attack detected: duplicate or stale packet")
        }
        lastInboundSequence = seq

        return decrypted
    }

    @Synchronized
    fun resetSequenceCounters() {
        outboundSequence = 0
        lastInboundSequence = 0
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        return okm.copyOf(length)
    }

    private fun toFixed32(b: BigInteger): ByteArray {
        val raw = b.toByteArray()
        if (raw.size == 32) return raw
        val res = ByteArray(32)
        if (raw.size > 32) {
            System.arraycopy(raw, raw.size - 32, res, 0, 32)
        } else {
            System.arraycopy(raw, 0, res, 32 - raw.size, raw.size)
        }
        return res
    }
}
