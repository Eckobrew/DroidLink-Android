package com.droiddock.companion.network

import com.google.gson.annotations.SerializedName
import java.util.UUID

// MARK: - Envelope Protocol
data class MessageEnvelope(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("type") val type: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("payload") val payload: String
)

object EnvelopeTypes {
    const val PAIRING_REQUEST = "pairing_request"
    const val PAIRING_RESPONSE = "pairing_response"
    const val HEARTBEAT = "heartbeat"
    const val CHAT_MESSAGE = "chat_message"
    const val CHAT_SYNC = "chat_sync"
    const val CHAT_SEND = "chat_send"
    const val CHAT_STATUS_UPDATE = "chat_status_update"
    const val CALL_SYNC = "call_sync"
    const val CALL_INCOMING = "call_incoming"
    const val CALL_STATE_UPDATE = "call_state_update"
    const val CALL_ACTION = "call_action"
    const val BLUETOOTH_PAIR_REQUEST = "bluetooth_pair_request"
    const val BLUETOOTH_PAIR_RESPONSE = "bluetooth_pair_response"
    const val DEVICE_STATUS = "device_status"
    const val CONTACT_SYNC = "contact_sync"
    const val APP_SYNC = "app_sync"
    const val APP_LAUNCH = "app_launch"
    const val NOTIFICATION_SYNC = "notification_sync"
    const val NOTIFICATION_POSTED = "notification_posted"
    const val NOTIFICATION_REMOVED = "notification_removed"
    const val NOTIFICATION_DISMISS = "notification_dismiss"
}

// MARK: - Pairing Models
data class PairingPayload(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("publicKey") val publicKey: String,
    @SerializedName("wifiPort") val wifiPort: Int = 8765,
    @SerializedName("wifiIps") val wifiIps: List<String>,
    @SerializedName("hostName") val hostName: String? = null,
    @SerializedName("bleServiceUuid") val bleServiceUuid: String,
    @SerializedName("pairingNonce") val pairingNonce: String,
    @SerializedName("timestamp") val timestamp: Long
)

data class PairingConfirmation(
    @SerializedName("status") val status: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("publicKey") val publicKey: String,
    @SerializedName("nonceResponse") val nonceResponse: String
)

// MARK: - Messages & RCS
data class ChatMessage(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("threadId") val threadId: String,
    @SerializedName("senderName") val senderName: String,
    @SerializedName("senderAddress") val senderAddress: String,
    @SerializedName("isFromMe") val isFromMe: Boolean,
    @SerializedName("text") val text: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("status") val status: String = "sent",
    @SerializedName("messageType") val messageType: String = "RCS",
    @SerializedName("attachmentUrl") val attachmentUrl: String? = null,
    @SerializedName("attachmentMimeType") val attachmentMimeType: String? = null
)

data class OutgoingMessageRequest(
    @SerializedName("id") val id: String,
    @SerializedName("threadId") val threadId: String,
    @SerializedName("recipient") val recipient: String,
    @SerializedName("text") val text: String,
    @SerializedName("preferRcs") val preferRcs: Boolean = true
)

// MARK: - Phone Calls
data class PhoneCall(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("phoneNumber") val phoneNumber: String,
    @SerializedName("contactName") val contactName: String = "",
    @SerializedName("state") val state: String = "incoming",
    @SerializedName("direction") val direction: String = "incoming",
    @SerializedName("startTime") val startTime: Long? = null,
    @SerializedName("duration") val duration: Double = 0.0,
    @SerializedName("isMuted") val isMuted: Boolean = false,
    @SerializedName("isSpeaker") val isSpeaker: Boolean = false
)

data class CallActionPayload(
    @SerializedName("callId") val callId: String? = null,
    @SerializedName("action") val action: String,
    @SerializedName("number") val number: String? = null,
    @SerializedName("dtmfDigit") val dtmfDigit: String? = null
)

// MARK: - Device Status
data class DeviceStatusPayload(
    @SerializedName("batteryLevel") val batteryLevel: Int,
    @SerializedName("isCharging") val isCharging: Boolean,
    @SerializedName("wifiSsid") val wifiSsid: String? = null,
    @SerializedName("cellularCarrier") val cellularCarrier: String? = null,
    @SerializedName("cellularSignalStrength") val cellularSignalStrength: Int = 4,
    @SerializedName("isBluetoothAudioConnected") val isBluetoothAudioConnected: Boolean = false
)

// MARK: - Contacts
data class ContactItem(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("phoneNumbers") val phoneNumbers: List<String>,
    @SerializedName("email") val email: String? = null
)

// MARK: - Apps
data class InstalledApp(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("versionName") val versionName: String? = null,
    @SerializedName("iconBase64") val iconBase64: String? = null,
    @SerializedName("isSystemApp") val isSystemApp: Boolean = false
)

// MARK: - Notifications
data class AppNotification(
    @SerializedName("id") val id: String,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("title") val title: String,
    @SerializedName("text") val text: String,
    @SerializedName("subText") val subText: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("iconBase64") val iconBase64: String? = null,
    @SerializedName("canReply") val canReply: Boolean = false,
    @SerializedName("isClearable") val isClearable: Boolean = true
)
