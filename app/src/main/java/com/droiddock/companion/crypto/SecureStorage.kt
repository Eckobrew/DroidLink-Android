package com.droiddock.companion.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "droiddock_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePrivateKey(bytes: ByteArray) {
        prefs.edit().putString("ecdh_private_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    fun getPrivateKey(): ByteArray? {
        val s = prefs.getString("ecdh_private_key", null) ?: return null
        return Base64.decode(s, Base64.NO_WRAP)
    }

    fun savePublicKey(bytes: ByteArray) {
        prefs.edit().putString("ecdh_public_key", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    fun getPublicKey(): ByteArray? {
        val s = prefs.getString("ecdh_public_key", null) ?: return null
        return Base64.decode(s, Base64.NO_WRAP)
    }

    fun savePeerPublicKey(base64Key: String) {
        prefs.edit().putString("peer_public_key", base64Key).apply()
    }

    fun getStoredPeerPublicKey(): String? {
        return prefs.getString("peer_public_key", null)
    }

    fun saveMacEndpoint(ip: String, port: Int) {
        prefs.edit().putString("mac_endpoint_ip", ip).putInt("mac_endpoint_port", port).apply()
    }

    fun getMacEndpointIp(): String? = prefs.getString("mac_endpoint_ip", null)
    fun getMacEndpointPort(): Int = prefs.getInt("mac_endpoint_port", 8765)

    fun saveCandidateEndpoints(endpoints: List<String>) {
        prefs.edit().putString("mac_candidate_endpoints", endpoints.joinToString(",")).apply()
    }

    fun getCandidateEndpoints(): List<String> {
        val s = prefs.getString("mac_candidate_endpoints", null) ?: return emptyList()
        return s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveMacDeviceName(name: String) {
        prefs.edit().putString("mac_device_name", name).apply()
    }

    fun getMacDeviceName(): String? = prefs.getString("mac_device_name", null)

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
