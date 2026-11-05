package com.droiddock.companion.telephony

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.droiddock.companion.network.*
import com.google.gson.Gson
import java.util.UUID

class DroidDockInCallService : InCallService() {
    private val handler = Handler(Looper.getMainLooper())
    private val activeCalls = linkedMapOf<String, Call>()
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val ids = mutableMapOf<Call, String>()
    private val reportedEnded = mutableSetOf<String>()
    private var muted = false
    @Volatile private var audioRoute = CallAudioState.ROUTE_EARPIECE
    private val dtmfStops = mutableMapOf<Call, Runnable>()

    companion object {
        private const val TAG = "DroidDockInCall"
        @Volatile var activeInstance: DroidDockInCallService? = null
            private set

        fun executeCallAction(payload: CallActionPayload, onResult: (String?) -> Unit) {
            Handler(Looper.getMainLooper()).post {
                val service = activeInstance
                if (service == null) {
                    onResult("Call controls are unavailable. Open the Android companion and enable call controls for your Mac.")
                } else {
                    try { onResult(service.performAction(payload)) }
                    catch (e: Exception) { onResult(e.message ?: "The phone rejected the call command.") }
                }
            }
        }

        fun syncCurrentCalls() {
            Handler(Looper.getMainLooper()).post {
                activeInstance?.let { service -> service.activeCalls.forEach { (id, call) -> service.publishCall(id, call) } }
            }
        }

        fun isBluetoothAudioConnected(): Boolean =
            activeInstance?.audioRoute == CallAudioState.ROUTE_BLUETOOTH
    }

    private fun performAction(payload: CallActionPayload): String? {
        // A stale ID must never act on an unrelated call (including call waiting).
        val call = if (payload.callId != null) activeCalls[payload.callId] else activeCalls.values.singleOrNull()
        if (call == null) return "This call is no longer available on the phone."
        when (payload.action) {
            "answer" -> {
                if (call.state != Call.STATE_RINGING) return "The call is no longer ringing."
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
            "reject" -> {
                if (call.state != Call.STATE_RINGING) return "The call is no longer ringing."
                call.reject(false, null)
            }
            "hangup" -> call.disconnect()
            "mute" -> setMuted(true)
            "unmute" -> setMuted(false)
            "dtmf" -> {
                val digit = payload.dtmfDigit?.singleOrNull()
                if (digit == null || digit !in "0123456789*#ABCD" || call.state != Call.STATE_ACTIVE) {
                    return "Touch tones require an active call and a valid keypad digit."
                }
                dtmfStops.remove(call)?.let(handler::removeCallbacks)
                call.playDtmfTone(digit)
                val stop = Runnable { call.stopDtmfTone(); dtmfStops.remove(call) }
                dtmfStops[call] = stop
                handler.postDelayed(stop, 180)
            }
            "audio_phone" -> setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
            "audio_speaker" -> setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            "audio_bluetooth" -> return routeAudioToMac()
            else -> return "Unsupported call command: ${payload.action}"
        }
        return null
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeInstance = this
        val id = UUID.randomUUID().toString()
        ids[call] = id
        activeCalls[id] = call
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = publishCall(id, call)
            override fun onDetailsChanged(call: Call, details: Call.Details) = publishCall(id, call)
        }
        callbacks[call] = callback
        call.registerCallback(callback, handler)
        publishCall(id, call)
    }

    override fun onCallRemoved(call: Call) {
        val id = ids.remove(call)
        if (id != null) {
            if (id !in reportedEnded) publishCall(id, call, forceEnded = true)
            activeCalls.remove(id)
            reportedEnded.remove(id)
        }
        callbacks.remove(call)?.let(call::unregisterCallback)
        dtmfStops.remove(call)?.let(handler::removeCallbacks)
        super.onCallRemoved(call)
        if (activeCalls.isEmpty()) activeInstance = null
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        handler.removeCallbacksAndMessages(null)
        callbacks.clear()
        activeCalls.clear()
        ids.clear()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        muted = state.isMuted
        audioRoute = state.route
        activeCalls.forEach { (id, call) -> if (id !in reportedEnded) publishCall(id, call) }
    }

    private fun publishCall(id: String, call: Call, forceEnded: Boolean = false) {
        val details = call.details
        val ended = forceEnded || call.state == Call.STATE_DISCONNECTED
        if (ended) reportedEnded.add(id)
        val state = if (ended) {
            when (details?.disconnectCause?.code) {
                DisconnectCause.MISSED -> "missed"
                DisconnectCause.REJECTED -> "rejected"
                else -> "ended"
            }
        } else when (call.state) {
            Call.STATE_RINGING -> "incoming"
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_NEW -> "dialing"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "held"
            else -> return
        }
        val number = details?.handle?.schemeSpecificPart ?: "Unknown"
        val connectedAt = details?.connectTimeMillis?.takeIf { it > 0 }
        val payload = PhoneCall(
            id = id, phoneNumber = number,
            contactName = details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: number,
            state = state,
            direction = if (details?.callDirection == Call.Details.DIRECTION_OUTGOING) "outgoing" else "incoming",
            startTime = connectedAt ?: if (ended) details?.creationTimeMillis else null,
            duration = connectedAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0)) / 1000.0 } ?: 0.0,
            isMuted = muted, isSpeaker = audioRoute == CallAudioState.ROUTE_SPEAKER
        )
        ConnectionManager.getInstance(applicationContext).sendEnvelope(MessageEnvelope(
            type = if (state == "incoming") EnvelopeTypes.CALL_INCOMING else EnvelopeTypes.CALL_STATE_UPDATE,
            payload = Gson().toJson(payload)
        ))
    }

    @Suppress("DEPRECATION")
    private fun routeAudioToMac(): String? {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Grant Nearby Devices permission on the phone to select Bluetooth call audio."
        }
        val address = getSharedPreferences("call_setup", MODE_PRIVATE).getString("macAddress", null)
            ?: return "Start Bluetooth pairing from the Mac, then pair the Mac in Android Bluetooth settings."
        return try {
            val device = callAudioState?.supportedBluetoothDevices?.firstOrNull { it.address.equals(address, true) }
                ?: return "The Mac is not connected as a Bluetooth call-audio device. Check the phone's Bluetooth settings."
            requestBluetoothAudio(device)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth audio permission was revoked", e)
            "Bluetooth permission is unavailable."
        }
    }
}
