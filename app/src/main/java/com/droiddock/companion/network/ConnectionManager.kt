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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "DroidDockConnMgr"
        @Volatile private var instance: ConnectionManager? = null
        fun getInstance(context: Context): ConnectionManager = instance ?: synchronized(this) {
            instance ?: ConnectionManager(context.applicationContext).also { instance = it }
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
    private val connectionMutex = Mutex()
    private val sendMutex = Mutex()
    private var recentSyncJob: Job? = null
    private var fullSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private val isConnected = AtomicBoolean(false)
    @Volatile private var activeTransportType = "none"
    @Volatile private var unpairing = false
    private val transportState = MutableStateFlow("disconnected")
    val transport = transportState.asStateFlow()
    private val bleState = MutableStateFlow("Waiting for pairing")
    val bluetoothStatus = bleState.asStateFlow()
    var onConnectionStateChanged: ((String) -> Unit)? = null
    val bluetoothFallbackEnabled: Boolean get() = context.getSharedPreferences("transport", Context.MODE_PRIVATE).getBoolean("ble", true)
    private class Session(val source: String, val crypto: CryptoManager, val peer: String,
        val nonce: String = UUID.randomUUID().toString(), val socket: Socket? = null,
        val pairingResult: ((Boolean, String) -> Unit)? = null) {
        @Volatile var confirmed = false
        @Volatile var ready = false
        @Volatile var lastReceived = android.os.SystemClock.elapsedRealtime()
        val created = android.os.SystemClock.elapsedRealtime()
        val output: DataOutputStream? = socket?.getOutputStream()?.let(::DataOutputStream)
    }
    @Volatile private var wifiSession: Session? = null
    @Volatile private var bleSession: Session? = null
    private data class Incoming(val session: Session, val packet: ByteArray)
    private val incoming = Channel<Incoming>(64)
    private class Pending(val envelope: MessageEnvelope, val data: ByteArray) {
        val ack = CompletableDeferred<Unit>()
        var inFlight = false
        var attempt = UUID.randomUUID()
        var sentAt = 0L
        var source = "none"
    }
    private val pendingLock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private var pendingBytes = 0
    private val seen = EnvelopeDeduplicator()
    private val ble = BluetoothTransport(context,
        onPacket = { packet -> bleSession?.let { session ->
            if (!incoming.trySend(Incoming(session, packet)).isSuccess) bleDisconnectForOverflow()
        } },
        onConnection = { connected ->
            if (connected) scope.launch { beginBleSession() }
            else { bleSession = null; updateTransport() }
        },
        onStatus = { bleState.value = it })

    init {
        scope.launch {
            for (item in incoming) {
                try { receive(item.session, item.packet) }
                catch (e: Exception) { Log.w(TAG, "Rejected ${item.session.source} packet", e); closeSession(item.session) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(3000)
                val now = android.os.SystemClock.elapsedRealtime()
                for (session in listOfNotNull(wifiSession, bleSession)) {
                    val activity = if (session.source == "bluetooth") maxOf(session.lastReceived, ble.lastActivity) else session.lastReceived
                    if ((!session.ready && now - session.created > 15_000) || now - activity > 20_000) closeSession(session)
                }
                updateTransport()
                val retry = synchronized(pendingLock) {
                    pending.values.filter { !it.inFlight && (it.source != activeTransportType || now - it.sentAt >
                        if (it.source == "bluetooth") maxOf(20_000L, it.data.size.toLong() * 2) else 10_000L) }
                }
                retry.forEach { transmit(it) }
            }
        }
        startAutoReconnectLoop()
        registerCallLogObserver()
        registerSmsObserver()
    }
    private fun bleDisconnectForOverflow() { ble.disconnect() }

    fun retryBluetoothConnection() {
        if (bluetoothFallbackEnabled && secureStorage.getStoredPeerPublicKey() != null) ble.retry()
    }
    fun setBluetoothFallbackEnabled(enabled: Boolean) {
        context.getSharedPreferences("transport", Context.MODE_PRIVATE).edit().putBoolean("ble", enabled).apply()
        if (enabled && secureStorage.getStoredPeerPublicKey() != null) ble.retry()
        else { bleSession = null; ble.stop(); updateTransport() }
    }
    private fun currentSession(): Session? = when (activeTransportType) {
        "wifi" -> wifiSession?.takeIf { it.ready }
        "bluetooth" -> bleSession?.takeIf { it.ready && ble.isConnected }
        else -> null
    }
    private fun updateTransport() {
        val selected = synchronized(this) {
            val next = if (unpairing) "none" else TransportPolicy.choose(wifiSession?.ready == true,
                bleSession?.ready == true && ble.isConnected, bluetoothFallbackEnabled)
            if (next == activeTransportType) return
            activeTransportType = next
            isConnected.set(next != "none")
            transportState.value = if (next == "none") "disconnected" else next
            next
        }
        Log.i(TAG, "Active data transport: $selected")
        synchronized(pendingLock) {
            pending.values.filter { it.source != selected }.forEach {
                it.attempt = UUID.randomUUID(); it.inFlight = false
            }
        }
        scope.launch(Dispatchers.Main) { onConnectionStateChanged?.invoke(transportState.value) }
        if (selected != "none") {
            scope.launch {
                synchronized(pendingLock) { pending.values.toList() }.forEach { transmit(it) }
                syncForTransport()
            }
        }
    }
    private fun syncForTransport() {
        sendDeviceStatus()
        syncRecentMessages()
        syncAllContacts()
        syncAllApps()
        RcsNotificationListenerService.syncAllActiveNotifications(context)
        RcsNotificationListenerService.syncStoredImages(context)
        DroidDockInCallService.syncCurrentCalls()
        syncAllCalls()
        syncAllMessages()
    }

    private fun registerCallLogObserver() {
        try {
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true,
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { if (isConnected.get()) syncAllCalls() }
                })
        } catch (e: Exception) { Log.w(TAG, "Could not observe call history", e) }
    }
    private fun registerSmsObserver() {
        try {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    if (isConnected.get()) { recentSyncJob?.cancel(); recentSyncJob = scope.launch { delay(350); syncRecentMessages() } }
                }
            }
            for (uri in listOf(Telephony.Sms.CONTENT_URI, android.net.Uri.parse("content://mms-sms/conversations"), android.net.Uri.parse("content://mms"))) {
                context.contentResolver.registerContentObserver(uri, true, observer)
            }
        } catch (e: Exception) { Log.w(TAG, "Could not observe messages", e) }
    }

    fun startAutoReconnectLoop() {
        if (reconnectJob != null) return
        reconnectJob = scope.launch {
            while (isActive) {
                val peer = secureStorage.getStoredPeerPublicKey()
                if (peer != null && !unpairing) {
                    if (bluetoothFallbackEnabled) ble.start()
                    if (wifiSession == null) connectionMutex.withLock {
                        if (wifiSession == null && !unpairing) {
                            val targets = (listOfNotNull(secureStorage.getMacEndpointIp()) + secureStorage.getCandidateEndpoints()).distinct()
                            connectWiFi(targets, secureStorage.getMacEndpointPort(), peer)
                        }
                    }
                }
                delay(4000)
            }
        }
    }

    fun startPairing(payload: PairingPayload, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            connectionMutex.withLock {
                unpairing = false
                wifiSession?.let { closeSession(it) }
                val targets = (payload.wifiIps + listOfNotNull(payload.hostName)).filter { it.isNotBlank() }.distinct()
                secureStorage.saveCandidateEndpoints(targets)
                secureStorage.saveMacDeviceName(payload.deviceName)
                val started = connectWiFi(targets, payload.wifiPort, payload.publicKey, onResult)
                if (!started) withContext(Dispatchers.Main) { onResult(false, "Could not reach DroidDock on the Mac. Open the Mac app and scan its current QR code. If both devices are on the same network, check the Mac firewall and VPN settings.") }
            }
        }
    }

    private fun connectWiFi(targets: List<String>, port: Int, peer: String, result: ((Boolean, String) -> Unit)? = null): Boolean {
        for (target in targets.take(6)) {
            val addresses = try { java.net.InetAddress.getAllByName(target).sortedBy { it !is java.net.Inet4Address } } catch (_: Exception) { continue }
            for (address in addresses.take(2)) {
                if (unpairing || wifiSession != null) return false
                val socket = Socket()
                try {
                    socket.keepAlive = true; socket.tcpNoDelay = true; socket.soTimeout = 12_000
                    socket.connect(InetSocketAddress(address, port), 2000)
                    val session = Session("wifi", CryptoManager(context), peer, socket = socket, pairingResult = result)
                    session.crypto.establishSharedKey(peer, TransportPolicy.sessionSalt(session.source, session.nonce), persistPeer = false)
                    wifiSession = session
                    secureStorage.saveMacEndpoint(address.hostAddress ?: target, port)
                    beginHandshake(session)
                    scope.launch {
                        try {
                            val input = DataInputStream(socket.getInputStream())
                            while (wifiSession === session) {
                                val length = input.readInt()
                                require(length in 1..BleFrameDecoder.MAX_FRAME) { "Invalid TCP frame length" }
                                val packet = ByteArray(length); input.readFully(packet)
                                incoming.send(Incoming(session, packet))
                            }
                        } catch (e: Exception) { Log.d(TAG, "Wi-Fi link closed: ${e.message}") }
                        finally { closeSession(session) }
                    }
                    return true
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    wifiSession?.takeIf { it.socket === socket }?.let { closeSession(it) }
                }
            }
        }
        return false
    }

    private suspend fun beginBleSession() {
        val peer = secureStorage.getStoredPeerPublicKey() ?: run { ble.stop(); return }
        if (unpairing || !bluetoothFallbackEnabled) { ble.stop(); return }
        val session = Session("bluetooth", CryptoManager(context), peer)
        session.crypto.establishSharedKey(peer, TransportPolicy.sessionSalt(session.source, session.nonce), persistPeer = false)
        bleSession = session
        sendMutex.withLock { beginHandshake(session) }
    }
    private fun beginHandshake(session: Session) {
        val request = PairingPayload(version = 2, deviceId = android.os.Build.MODEL,
            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", publicKey = session.crypto.localPublicKeyBase64,
            wifiPort = secureStorage.getMacEndpointPort(), wifiIps = emptyList(), hostName = null,
            bleServiceUuid = BluetoothTransport.SERVICE.toString(), pairingNonce = session.nonce, timestamp = System.currentTimeMillis())
        writeRaw(session, gson.toJson(MessageEnvelope(type = EnvelopeTypes.PAIRING_REQUEST, payload = gson.toJson(request))).toByteArray()) { error ->
            if (error != null) closeSession(session)
        }
    }
    private fun current(session: Session) = !unpairing && if (session.source == "wifi") wifiSession === session else bleSession === session
    private fun closeSession(session: Session) {
        val wasCurrent = if (session.source == "wifi") {
            synchronized(this) { if (wifiSession === session) { wifiSession = null; true } else false }
        } else {
            synchronized(this) { if (bleSession === session) { bleSession = null; true } else false }
        }
        try { session.socket?.close() } catch (_: Exception) {}
        if (wasCurrent && session.source == "bluetooth") ble.disconnect()
        if (wasCurrent && !session.ready) session.pairingResult?.let { callback ->
            scope.launch(Dispatchers.Main) { callback(false, "Mac verification failed or timed out. Update both applications and try again.") }
        }
        updateTransport()
    }

    private suspend fun receive(session: Session, packet: ByteArray) {
        if (!current(session)) return
        if (packet.firstOrNull() == '{'.code.toByte()) {
            val raw = gson.fromJson(String(packet, Charsets.UTF_8), MessageEnvelope::class.java)
            require(raw.type == EnvelopeTypes.PAIRING_RESPONSE && !session.confirmed) { "Unexpected plaintext envelope" }
            val response = gson.fromJson(raw.payload, PairingConfirmation::class.java)
            require(response.status == "accepted" && response.publicKey == session.peer && response.nonceResponse == session.nonce) { "Mac identity did not match pairing" }
            session.confirmed = true
            sendDirect(MessageEnvelope(type = EnvelopeTypes.HEARTBEAT, payload = "hello"), session)
            return
        }
        require(session.confirmed) { "Handshake not confirmed" }
        val envelope = gson.fromJson(String(session.crypto.decrypt(packet), Charsets.UTF_8), MessageEnvelope::class.java)
        session.lastReceived = android.os.SystemClock.elapsedRealtime()
        if (!session.ready) {
            session.ready = true
            Log.i(TAG, "Verified ${session.source} session")
            secureStorage.savePeerPublicKey(session.peer)
            if (session.source == "bluetooth") bleState.value = "Verified Bluetooth sync connection"
            updateTransport()
            session.pairingResult?.let { callback -> scope.launch(Dispatchers.Main) { callback(true, "Mac paired and verified") } }
        }
        if (envelope.type == EnvelopeTypes.HEARTBEAT) {
            if (envelope.payload != "pong") sendDirect(MessageEnvelope(type = EnvelopeTypes.HEARTBEAT, payload = "pong"), session)
            return
        }
        if (envelope.type == EnvelopeTypes.ENVELOPE_ACK) {
            synchronized(pendingLock) { pending.remove(envelope.payload)?.let { pendingBytes -= it.data.size; it.ack.complete(Unit) } }
            return
        }
        sendDirect(MessageEnvelope(type = EnvelopeTypes.ENVELOPE_ACK, payload = envelope.id), session)
        if (!seen.insert(envelope.id)) return
        handleInboundEnvelope(envelope)
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
                    sendEnvelope(envelope).join()
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
                val chunks = apps.chunked(if (activeTransportType == "bluetooth") 3 else 25)
                for (chunk in chunks) {
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.APP_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope).join()
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
                    sendEnvelope(envelope).join()
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
                val chunks = messages.chunked(if (activeTransportType == "bluetooth") 25 else 250)
                for (chunk in chunks) {
                    if (!isActive) break
                    val envelope = MessageEnvelope(
                        type = EnvelopeTypes.CHAT_SYNC,
                        payload = gson.toJson(chunk)
                    )
                    sendEnvelope(envelope).join()
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
                    sendEnvelope(envelope).join()
                }
                Log.d(TAG, "Synced all ${calls.size} call records in ${chunks.size} chunks with Mac")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all call records", e)
            }
        }
    }


    private fun handleInboundEnvelope(envelope: MessageEnvelope) {
        when (envelope.type) {
            EnvelopeTypes.TRANSPORT_PROBE -> {
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.TRANSPORT_PROBE, payload = envelope.payload))
            }
            EnvelopeTypes.TRANSPORT_ENDPOINTS -> {
                val endpoints = org.json.JSONObject(envelope.payload)
                val ips = endpoints.optJSONArray("wifiIps")
                val candidates = (0 until (ips?.length() ?: 0)).map { ips!!.getString(it) }
                val host = endpoints.optString("hostName")
                secureStorage.saveCandidateEndpoints((candidates + listOf(host)).filter { it.isNotBlank() }.distinct())
            }
            EnvelopeTypes.CHAT_CAPABILITIES -> {
                val request = org.json.JSONObject(envelope.payload)
                val recipient = request.optString("recipient")
                val reply = mapOf("threadId" to request.optString("threadId"),
                    "recipient" to recipient,
                    "canReply" to RcsNotificationListenerService.canReplyToConversation(context, recipient).toString())
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.CHAT_CAPABILITIES, payload = gson.toJson(reply)))
            }
            EnvelopeTypes.ATTACHMENT_REQUEST -> {
                val url = org.json.JSONObject(envelope.payload).optString("url")
                val response = try {
                    mapOf("url" to url, "data" to com.droiddock.companion.messages.ImageMessages.readPreview(context, url))
                } catch (e: Exception) { mapOf("url" to url, "error" to (e.message ?: "Image unavailable")) }
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.ATTACHMENT_DATA, payload = gson.toJson(response)))
            }
            EnvelopeTypes.CHAT_SEND -> {
                val req = gson.fromJson(envelope.payload, OutgoingMessageRequest::class.java)
                if (req.imageBase64 != null) {
                    val response = try {
                        com.droiddock.companion.messages.ImageMessages.prepareComposer(context, req)
                        mapOf("id" to req.id, "status" to "on_phone", "protocol" to "Unconfirmed")
                    } catch (e: Exception) {
                        mapOf("id" to req.id, "status" to "failed", "protocol" to "Unconfirmed",
                            "error" to (e.message ?: "Could not prepare image on phone"))
                    }
                    sendEnvelope(MessageEnvelope(type = EnvelopeTypes.CHAT_STATUS_UPDATE, payload = gson.toJson(response)))
                    return
                }
                Log.d(TAG, "Outgoing message request for: ${req.recipient}, preferRcs: ${req.preferRcs}")

                var sentViaRcs = false
                if (req.preferRcs) {
                    sentViaRcs = RcsNotificationListenerService.replyToRcsConversation(context, req.threadId, req.text) ||
                            RcsNotificationListenerService.replyToRcsConversation(context, req.recipient, req.text)
                }

                var sent = sentViaRcs
                if (!sent && !req.threadId.startsWith("notification_group_")) {
                    Log.d(TAG, "RCS reply not available, falling back to SMS for: ${req.recipient}")
                    sent = smsHelper.sendSms(req.recipient, req.text, req.id)
                }

                // Send status update back to Mac with protocol confirmation
                val statusUpdate = mapOf(
                    "id" to req.id,
                    "status" to if (!sent) "failed" else if (sentViaRcs) "sent" else "sending",
                    "protocol" to if (sentViaRcs || req.threadId.startsWith("notification_group_")) "Unconfirmed" else "SMS"
                )
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.CHAT_STATUS_UPDATE, payload = gson.toJson(statusUpdate)))
            }

            EnvelopeTypes.CALL_ACTION -> {
                val action = gson.fromJson(envelope.payload, CallActionPayload::class.java)
                Log.d(TAG, "Call action received from Mac: ${action.action}")

                fun reply(error: String?) {
                    val result = mapOf("action" to action.action, "callId" to (action.callId ?: ""),
                        "success" to (error == null).toString(), "error" to (error ?: ""))
                    sendEnvelope(MessageEnvelope(type = EnvelopeTypes.CALL_ACTION_RESULT, payload = gson.toJson(result)))
                }
                if (action.action == "dial") {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val number = action.number
                        reply(if (number != null && callManager.placeCall(number)) null else
                            "Could not place the call. Enable call controls in the Android companion and grant Phone permission.")
                    }
                } else {
                    DroidDockInCallService.executeCallAction(action, ::reply)
                }
            }

            EnvelopeTypes.HEARTBEAT -> {
                sendDeviceStatus()
            }

            EnvelopeTypes.MEDIA_CONTROL -> {
                val command = org.json.JSONObject(envelope.payload).optString("action")
                RcsNotificationListenerService.controlMedia(context, command)
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

            EnvelopeTypes.UNPAIR -> {
                Log.d(TAG, "Unpair request received from Mac")
                scope.launch { delay(500); unpairInternal() }
            }

            EnvelopeTypes.BLUETOOTH_PAIR_REQUEST -> {
                val json = org.json.JSONObject(envelope.payload)
                val address = json.optString("address").replace("-", ":").uppercase(java.util.Locale.ROOT)
                val valid = BluetoothAdapter.checkBluetoothAddress(address)
                if (valid) {
                    context.getSharedPreferences("call_setup", Context.MODE_PRIVATE).edit()
                        .putString("macAddress", address).putString("macName", json.optString("name")).apply()
                }
                val response = mapOf("message" to if (valid)
                    "Open the Android companion, tap Enable call controls, and select this Mac. Pair it in Bluetooth settings and enable Phone calls."
                    else "The Mac did not provide a valid Bluetooth address. Pair manually in Bluetooth settings, then enable call controls in the Android companion.")
                sendEnvelope(MessageEnvelope(type = EnvelopeTypes.BLUETOOTH_PAIR_RESPONSE, payload = gson.toJson(response)))
            }

        }
    }


    fun sendEnvelope(envelope: MessageEnvelope): Job = scope.launch {
        val bytes = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
        if (bytes.size + 36 > BleFrameDecoder.MAX_FRAME) { Log.w(TAG, "Envelope exceeds frame limit"); return@launch }
        val item = Pending(envelope, bytes)
        val accepted = synchronized(pendingLock) {
            if (currentSession() == null || pending.containsKey(envelope.id) || pending.size >= 512 || pendingBytes + bytes.size > 24 * 1024 * 1024) false
            else { pending[envelope.id] = item; pendingBytes += bytes.size; true }
        }
        if (!accepted) { Log.d(TAG, "Envelope not queued: no verified link or outbox full"); return@launch }
        transmit(item)
        item.ack.await() // Sync producers join this job: BLE naturally applies backpressure.
    }

    private suspend fun transmit(item: Pending) {
        sendMutex.withLock {
            val session = currentSession() ?: return
            val attempt = UUID.randomUUID()
            synchronized(pendingLock) {
                if (pending[item.envelope.id] !== item || item.inFlight) return
                item.inFlight = true; item.attempt = attempt; item.source = session.source
            }
            try {
                val encrypted = session.crypto.encrypt(item.data)
                writeRaw(session, encrypted) { error ->
                    synchronized(pendingLock) {
                        if (pending[item.envelope.id] === item && item.attempt == attempt) {
                            item.inFlight = false
                            item.sentAt = if (error == null) android.os.SystemClock.elapsedRealtime() else 0L
                        }
                    }
                    if (error != null) closeSession(session)
                }
            } catch (e: Exception) {
                synchronized(pendingLock) { if (item.attempt == attempt) { item.inFlight = false; item.sentAt = 0 } }
                closeSession(session)
            }
        }
    }
    private suspend fun sendDirect(envelope: MessageEnvelope, session: Session) {
        sendMutex.withLock {
            if (!current(session)) return
            try {
                val packet = session.crypto.encrypt(gson.toJson(envelope).toByteArray(Charsets.UTF_8))
                writeRaw(session, packet) { error -> if (error != null) closeSession(session) }
            } catch (e: Exception) { closeSession(session) }
        }
    }
    private fun writeRaw(session: Session, packet: ByteArray, completion: (Exception?) -> Unit) {
        if (!current(session)) { completion(IllegalStateException("Session closed")); return }
        if (session.source == "bluetooth") { ble.send(packet, completion); return }
        try {
            val output = session.output ?: error("Socket closed")
            synchronized(output) { output.writeInt(packet.size); output.write(packet); output.flush() }
            completion(null)
        } catch (e: Exception) { completion(e) }
    }
    fun unpair() {
        scope.launch {
            withTimeoutOrNull(2000) { sendEnvelope(MessageEnvelope(type = EnvelopeTypes.UNPAIR, payload = "{}")).join() }
            unpairInternal()
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
            isBluetoothAudioConnected = DroidDockInCallService.isBluetoothAudioConnected()
        )

        sendEnvelope(MessageEnvelope(type = EnvelopeTypes.DEVICE_STATUS, payload = gson.toJson(payload)))
    }


    private fun unpairInternal() {
        unpairing = true
        context.stopService(Intent(context, ConnectionService::class.java))
        seen.reset()
        wifiSession?.let { closeSession(it) }
        bleSession = null; ble.stop()
        recentSyncJob?.cancel(); fullSyncJob?.cancel()
        synchronized(pendingLock) {
            pending.values.forEach { it.ack.cancel() }
            pending.clear(); pendingBytes = 0
        }
        val setup = context.getSharedPreferences("call_setup", Context.MODE_PRIVATE)
        val address = setup.getString("macAddress", null)
        try {
            val manager = context.getSystemService(android.companion.CompanionDeviceManager::class.java)
            @Suppress("DEPRECATION")
            manager?.associations?.filter { it.equals(address, true) }?.forEach { manager.disassociate(it) }
        } catch (e: Exception) { Log.w(TAG, "Could not remove companion association", e) }
        setup.edit().clear().apply()
        context.getSharedPreferences("image_composer", Context.MODE_PRIVATE).edit().clear().apply()
        java.io.File(context.filesDir, "outgoing_images").deleteRecursively()
        java.io.File(context.filesDir, "received_images").deleteRecursively()
        context.getSharedPreferences("received_image_messages", Context.MODE_PRIVATE).edit().clear().apply()
        secureStorage.clearPairing()
        updateTransport()
    }
}
