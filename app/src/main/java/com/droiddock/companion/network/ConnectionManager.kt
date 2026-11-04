package com.droiddock.companion.network

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import com.droiddock.companion.crypto.CryptoManager
import com.droiddock.companion.crypto.SecureStorage
import com.droiddock.companion.messages.ContactsHelper
import com.droiddock.companion.messages.RcsNotificationListenerService
import com.droiddock.companion.messages.SmsHelper
import com.droiddock.companion.telephony.CallManager
import com.droiddock.companion.telephony.DroidDockInCallService
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DroidDockConnMgr"

        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    val cryptoManager = CryptoManager(context)
    private val secureStorage = SecureStorage(context)
    private val smsHelper = SmsHelper(context)
    private val callManager = CallManager(context)
    private val contactsHelper = ContactsHelper(context)
    private val appsHelper = com.droiddock.companion.apps.AppsHelper(context)
    private val gson = Gson()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()
    private var recentSyncJob: Job? = null
    private var fullSyncJob: Job? = null
    private var tcpSocket: Socket? = null
    private var outStream: DataOutputStream? = null
    private var inStream: DataInputStream? = null
    private val isConnected = AtomicBoolean(false)
    private var activeTransportType = "none"

    var onConnectionStateChanged: ((String) -> Unit)? = null

    init {
        startAutoReconnectLoop()
        registerCallLogObserver()
        registerSmsObserver()
    }

    private fun registerCallLogObserver() {
        try {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    if (isConnected.get()) {
                        syncAllCalls()
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register CallLog observer: ${e.message}")
        }
    }

    private fun registerSmsObserver() {
        try {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    if (isConnected.get()) {
                        recentSyncJob?.cancel()
                        recentSyncJob = scope.launch {
                            delay(1200) // Debounce rapid DB writes
                            syncRecentMessages()
                        }
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms-sms/conversations"),
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms"),
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register SMS/MMS observer: ${e.message}")
        }
    }

    fun startAutoReconnectLoop() {
        scope.launch {
            while (true) {
                if (!isConnected.get()) {
                    val peerKey = secureStorage.getStoredPeerPublicKey()
                    val savedHost = secureStorage.getMacEndpointIp()
                    val port = secureStorage.getMacEndpointPort()
                    val candidates = secureStorage.getCandidateEndpoints()

                    if (peerKey != null) {
                        try {
                            cryptoManager.establishSharedKey(peerKey)

                            val targets = mutableListOf<String>()
                            if (!savedHost.isNullOrBlank()) targets.add(savedHost)
                            for (c in candidates) {
                                if (!targets.contains(c)) targets.add(c)
                            }

                            var connected = false
                            var successHost = ""

                            for (target in targets) {
                                try {
                                    val addrs = try {
                                        java.net.InetAddress.getAllByName(target)
                                    } catch (e: Exception) {
                                        emptyArray()
                                    }

                                    // Sort IPv4 first to avoid IPv6 link-local ECONNREFUSED
                                    val sortedAddrs = addrs.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }

                                    for (addr in sortedAddrs) {
                                        try {
                                            val socket = Socket()
                                            socket.connect(InetSocketAddress(addr, port), 2500)
                                            tcpSocket = socket
                                            outStream = DataOutputStream(socket.getOutputStream())
                                            inStream = DataInputStream(socket.getInputStream())
                                            isConnected.set(true)
                                            activeTransportType = "wifi"
                                            connected = true
                                            successHost = addr.hostAddress ?: target
                                            break
                                        } catch (e: Exception) {
                                            // Try next IP
                                        }
                                    }
                                    if (connected) break
                                } catch (e: Exception) {
                                    Log.d(TAG, "Target $target failed: ${e.message}")
                                }
                            }

                            if (connected) {
                                secureStorage.saveMacEndpoint(successHost, port)
                                cryptoManager.resetSequenceCounters()
                                withContext(Dispatchers.Main) {
                                    onConnectionStateChanged?.invoke("wifi")
                                }

                                startInboundListener()

                                // Send pairing handshake to establish session and symmetric key on Mac
                                val pairingReq = PairingPayload(
                                    deviceId = android.os.Build.MODEL,
                                    deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                                    publicKey = cryptoManager.localPublicKeyBase64,
                                    wifiPort = port,
                                    wifiIps = emptyList(),
                                    hostName = null,
                                    bleServiceUuid = "",
                                    pairingNonce = java.util.UUID.randomUUID().toString(),
                                    timestamp = System.currentTimeMillis()
                                )
                                sendRawEnvelope(MessageEnvelope(type = EnvelopeTypes.PAIRING_REQUEST, payload = gson.toJson(pairingReq)))
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Auto-reconnect error: ${e.message}")
                        }
                    }
                }
                kotlinx.coroutines.delay(4000)
            }
        }
    }

    fun startPairing(payload: PairingPayload, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                cryptoManager.establishSharedKey(payload.publicKey)

                val candidateHosts = mutableListOf<String>()
                // Put IP addresses first so direct IP connection is tried immediately
                for (h in payload.wifiIps) {
                    if (!candidateHosts.contains(h)) {
                        candidateHosts.add(h)
                    }
                }
                payload.hostName?.let { if (it.isNotEmpty() && !candidateHosts.contains(it)) candidateHosts.add(it) }

                // Try Wi-Fi connection to discovered hostname/IPs
                var connected = false
                var connectedHost = ""
                for (host in candidateHosts) {
                    try {
                        Log.d(TAG, "Trying Wi-Fi connection to $host:${payload.wifiPort}")
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, payload.wifiPort), 4000)
                        tcpSocket = socket
                        outStream = DataOutputStream(socket.getOutputStream())
                        inStream = DataInputStream(socket.getInputStream())
                        isConnected.set(true)
                        activeTransportType = "wifi"
                        connected = true
                        connectedHost = host
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect to $host: ${e.message}")
                    }
                }

                if (connected) {
                    cryptoManager.resetSequenceCounters()
                    // Persist pairing keys and endpoints
                    secureStorage.savePeerPublicKey(payload.publicKey)
                    secureStorage.saveCandidateEndpoints(candidateHosts)
                    secureStorage.saveMacEndpoint(connectedHost, payload.wifiPort)
                    secureStorage.saveMacDeviceName(payload.deviceName)

                    startInboundListener()

                    // Send Pairing Request
                    val pairingReq = PairingPayload(
                        deviceId = android.os.Build.MODEL,
                        deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                        publicKey = cryptoManager.localPublicKeyBase64,
                        wifiPort = payload.wifiPort,
                        wifiIps = emptyList(),
                        hostName = null,
                        bleServiceUuid = payload.bleServiceUuid,
                        pairingNonce = payload.pairingNonce,
                        timestamp = System.currentTimeMillis()
                    )
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.PAIRING_REQUEST,
                        payload = gson.toJson(pairingReq)
                    )
                    sendRawEnvelope(envelope)

                    withContext(Dispatchers.Main) {
                        onConnectionStateChanged?.invoke("wifi")
                        onResult(true, "Paired and connected to ${payload.deviceName}!")
                    }
                    sendDeviceStatus()
                    syncRecentMessages()
                    syncAllContacts()
                    syncAllApps()
                    RcsNotificationListenerService.syncAllActiveNotifications(context)
                    syncAllCalls()
                    syncAllMessages()
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Could not reach Mac at ${candidateHosts.joinToString(", ")}. Verify Mac app is running on the same network.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing handshake failed", e)
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Pairing failed")
                }
            }
        }
    }

    fun syncAllContacts() {
        scope.launch {
            try {
                val contacts = contactsHelper.getAllContactsList()
                val chunks = contacts.chunked(100)
                for (chunk in chunks) {
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.CONTACT_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope)
                    kotlinx.coroutines.delay(10)
                }
                Log.d(TAG, "Synced all ${contacts.size} contacts in ${chunks.size} chunks with Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all contacts", e)
            }
        }
    }

    fun syncAllApps() {
        scope.launch {
            try {
                val apps = appsHelper.getAllInstalledApps()
                val chunks = apps.chunked(25)
                for (chunk in chunks) {
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.APP_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope)
                    delay(20)
                }
                Log.d(TAG, "Synced ${apps.size} installed apps in ${chunks.size} chunks with Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing installed apps", e)
            }
        }
    }

    fun syncRecentMessages() {
        scope.launch {
            try {
                val recent = smsHelper.getRecentMessages(50)
                if (recent.isNotEmpty()) {
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.CHAT_SYNC,
                        payload = gson.toJson(recent)
                    )
                    sendEnvelope(envelope)
                    Log.d(TAG, "Synced ${recent.size} recent messages with Mac")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing recent messages", e)
            }
        }
    }

    fun syncAllMessages() {
        fullSyncJob?.cancel()
        fullSyncJob = scope.launch {
            try {
                val messages = smsHelper.getAllMessages()
                val chunks = messages.chunked(250)
                for (chunk in chunks) {
                    if (!isActive) break
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.CHAT_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope)
                    kotlinx.coroutines.delay(40)
                }
                Log.d(TAG, "Synced all ${messages.size} messages in ${chunks.size} chunks with Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all messages", e)
            }
        }
    }

    fun syncAllCalls() {
        scope.launch {
            try {
                val calls = callManager.getAllCalls()
                val chunks = calls.chunked(50)
                for (chunk in chunks) {
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.CALL_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope)
                }
                Log.d(TAG, "Synced all ${calls.size} call records in ${chunks.size} chunks with Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all call records", e)
            }
        }
    }

    private fun startInboundListener() {
        scope.launch {
            val input = inStream ?: return@launch
            while (isConnected.get()) {
                try {
                    val length = input.readInt()
                    if (length <= 0 || length > 10_000_000) break

                    val packet = ByteArray(length)
                    input.readFully(packet)

                    // Check if packet is unencrypted JSON envelope (e.g. pairing_response)
                    val rawEnvelope = if (packet.isNotEmpty() && packet[0] == '{'.code.toByte()) {
                        try {
                            gson.fromJson(String(packet, Charsets.UTF_8), MessageEnvelope::class.java)
                        } catch (e: Exception) {
                            null
                        }
                    } else null

                    val finalEnvelope = if (rawEnvelope != null) {
                        rawEnvelope
                    } else {
                        val decrypted = cryptoManager.decrypt(packet)
                        gson.fromJson(String(decrypted, Charsets.UTF_8), MessageEnvelope::class.java)
                    }

                    handleInboundEnvelope(finalEnvelope)
                } catch (e: Exception) {
                    Log.e(TAG, "Inbound read/decrypt error", e)
                    break
                }
            }
            isConnected.set(false)
            withContext(Dispatchers.Main) {
                onConnectionStateChanged?.invoke("disconnected")
            }
        }
    }

    private fun handleInboundEnvelope(envelope: MessageEnvelope) {
        when (envelope.type) {
            EnvelopeTypes.PAIRING_RESPONSE -> {
                Log.d(TAG, "Pairing handshake accepted by Mac! Streaming all data...")
                sendDeviceStatus()
                syncRecentMessages()
                syncAllContacts()
                syncAllApps()
                RcsNotificationListenerService.syncAllActiveNotifications(context)
                syncAllCalls()
                syncAllMessages()
            }
            EnvelopeTypes.CHAT_SEND -> {
                val req = gson.fromJson(envelope.payload, OutgoingMessageRequest::class.java)
                Log.d(TAG, "Outgoing message request for: ${req.recipient}, preferRcs: ${req.preferRcs}")

                var sentViaRcs = false
                if (req.preferRcs) {
                    sentViaRcs = RcsNotificationListenerService.replyToRcsConversation(context, req.threadId, req.text) ||
                            RcsNotificationListenerService.replyToRcsConversation(context, req.recipient, req.text)
                }

                var sent = sentViaRcs
                if (!sent) {
                    Log.d(TAG, "RCS reply not available, falling back to SMS for: ${req.recipient}")
                    sent = smsHelper.sendSms(req.recipient, req.text)
                }

                // Send status update back to Mac with protocol confirmation
                val statusUpdate = mapOf(
                    "id" to req.id,
                    "status" to if (sent) "delivered" else "failed",
                    "protocol" to if (sentViaRcs) "RCS" else "SMS"
                )
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.CHAT_STATUS_UPDATE, payload = gson.toJson(statusUpdate)))
            }

            EnvelopeTypes.CALL_ACTION -> {
                val action = gson.fromJson(envelope.payload, CallActionPayload::class.java)
                Log.d(TAG, "Call action received from Mac: ${action.action}")

                if (action.action == "dial") {
                    action.number?.let { callManager.placeCall(it) }
                } else {
                    DroidDockInCallService.executeCallAction(action)
                }
            }

            EnvelopeTypes.HEARTBEAT -> {
                sendDeviceStatus()
            }

            EnvelopeTypes.APP_SYNC -> {
                syncAllApps()
            }

            EnvelopeTypes.NOTIFICATION_SYNC -> {
                RcsNotificationListenerService.syncAllActiveNotifications(context)
            }

            EnvelopeTypes.NOTIFICATION_DISMISS -> {
                try {
                    val json = org.json.JSONObject(envelope.payload)
                    val id = json.optString("id")
                    if (id.isNotEmpty()) {
                        RcsNotificationListenerService.cancelNotificationByKey(id)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dismiss notification: ${e.message}")
                }
            }

            EnvelopeTypes.APP_LAUNCH -> {
                try {
                    val json = org.json.JSONObject(envelope.payload)
                    val pkg = json.optString("packageName")
                    if (pkg.isNotEmpty()) {
                        Log.d(TAG, "Launching app on phone: $pkg")
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } else {
                            Log.w(TAG, "No launch intent found for package: $pkg")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch app from Mac request", e)
                }
            }

            EnvelopeTypes.BLUETOOTH_PAIR_REQUEST -> {
                Log.d(TAG, "Bluetooth pair request received from Mac: ${envelope.payload}")
                try {
                    val json = org.json.JSONObject(envelope.payload)
                    val macAddr = json.optString("address", "").replace("-", ":")
                    if (macAddr.isNotEmpty()) {
                        val btAdapter = BluetoothAdapter.getDefaultAdapter()
                        if (btAdapter != null && btAdapter.isEnabled) {
                            try {
                                val macDevice = btAdapter.getRemoteDevice(macAddr)
                                Log.d(TAG, "Initiating Bluetooth bonding with Mac: $macAddr (${macDevice.name})")
                                macDevice.createBond()
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not createBond with $macAddr: ${e.message}")
                            }
                        }
                    }

                    val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(discoverableIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch Bluetooth discoverable intent", e)
                }
            }
        }
    }

    fun sendEnvelope(envelope: MessageEnvelope) {
        scope.launch {
            sendMutex.withLock {
                try {
                    val jsonBytes = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
                    val encryptedPacket = cryptoManager.encrypt(jsonBytes)

                    val out = outStream
                    if (out != null && isConnected.get()) {
                        out.writeInt(encryptedPacket.size)
                        out.write(encryptedPacket)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt and send envelope", e)
                }
            }
        }
    }

    private fun sendRawEnvelope(envelope: MessageEnvelope) {
        scope.launch {
            try {
                val jsonBytes = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
                val out = outStream ?: return@launch
                synchronized(out) {
                    out.writeInt(jsonBytes.size)
                    out.write(jsonBytes)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send raw envelope", e)
            }
        }
    }

    fun sendDeviceStatus() {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val carrier = telephonyManager?.networkOperatorName ?: "Carrier"

        val payload = DeviceStatusPayload(
            batteryLevel = batteryPct,
            isCharging = isCharging,
            cellularCarrier = carrier,
            cellularSignalStrength = 4,
            isBluetoothAudioConnected = true
        )

        sendEnvelope(MessageEnvelope(type = EnvelopeTypes.DEVICE_STATUS, payload = gson.toJson(payload)))
    }
}
