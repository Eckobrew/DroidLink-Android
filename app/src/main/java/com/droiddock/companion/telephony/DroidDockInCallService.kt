package com.droiddock.companion.telephony

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.droiddock.companion.network.CallActionPayload
import com.droiddock.companion.network.ConnectionManager
import com.droiddock.companion.network.EnvelopeTypes
import com.droiddock.companion.network.MessageEnvelope
import com.droiddock.companion.network.PhoneCall
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap

class DroidDockInCallService : InCallService() {

    companion object {
        private const val TAG = "DroidDockInCall"
        var activeInstance: DroidDockInCallService? = null
            private set

        private val activeCalls = ConcurrentHashMap<String, Call>()

        fun executeCallAction(payload: CallActionPayload) {
            val service = activeInstance ?: run {
                Log.w(TAG, "No active InCallService instance")
                return
            }

            val targetCall = payload.callId?.let { activeCalls[it] } ?: activeCalls.values.firstOrNull()

            when (payload.action) {
                "answer" -> {
                    targetCall?.let {
                        it.answer(VideoProfile.STATE_AUDIO_ONLY)
                        service.routeAudioToBluetooth()
                        Log.d(TAG, "Answered call via Mac instruction")
                    }
                }
                "reject" -> {
                    targetCall?.let {
                        it.reject(false, null)
                        Log.d(TAG, "Rejected call via Mac instruction")
                    }
                }
                "hangup" -> {
                    targetCall?.let {
                        it.disconnect()
                        Log.d(TAG, "Hung up call via Mac instruction")
                    }
                }
                "dtmf" -> {
                    payload.dtmfDigit?.firstOrNull()?.let { digit ->
                        targetCall?.playDtmfTone(digit)
                        targetCall?.stopDtmfTone()
                        Log.d(TAG, "Played DTMF tone: $digit")
                    }
                }
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeInstance = this
        val callId = call.hashCode().toString()
        activeCalls[callId] = call

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                handleCallStateChange(callId, call, state)
            }
        })

        handleCallStateChange(callId, call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callId = call.hashCode().toString()
        activeCalls.remove(callId)

        val phoneCall = PhoneCall(
            id = callId,
            phoneNumber = getPhoneNumber(call),
            contactName = getCallerName(call),
            state = "ended"
        )
        sendCallUpdate(EnvelopeTypes.CALL_STATE_UPDATE, phoneCall)

        if (activeCalls.isEmpty()) {
            activeInstance = null
        }
    }

    private fun handleCallStateChange(callId: String, call: Call, state: Int) {
        val number = getPhoneNumber(call)
        val name = getCallerName(call)

        when (state) {
            Call.STATE_RINGING -> {
                val phoneCall = PhoneCall(
                    id = callId,
                    phoneNumber = number,
                    contactName = name,
                    state = "incoming",
                    direction = "incoming"
                )
                sendCallUpdate(EnvelopeTypes.CALL_INCOMING, phoneCall)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                val phoneCall = PhoneCall(
                    id = callId,
                    phoneNumber = number,
                    contactName = name,
                    state = "dialing",
                    direction = "outgoing"
                )
                sendCallUpdate(EnvelopeTypes.CALL_STATE_UPDATE, phoneCall)
            }
            Call.STATE_ACTIVE -> {
                routeAudioToBluetooth()
                val phoneCall = PhoneCall(
                    id = callId,
                    phoneNumber = number,
                    contactName = name,
                    state = "active",
                    startTime = System.currentTimeMillis()
                )
                sendCallUpdate(EnvelopeTypes.CALL_STATE_UPDATE, phoneCall)
            }
            Call.STATE_DISCONNECTED -> {
                val phoneCall = PhoneCall(
                    id = callId,
                    phoneNumber = number,
                    contactName = name,
                    state = "ended"
                )
                sendCallUpdate(EnvelopeTypes.CALL_STATE_UPDATE, phoneCall)
            }
        }
    }

    fun routeAudioToBluetooth() {
        try {
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            Log.d(TAG, "Audio routed to Bluetooth Hands-Free device")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route audio to Bluetooth", e)
        }
    }

    private fun getPhoneNumber(call: Call): String {
        return call.details?.handle?.schemeSpecificPart ?: "Unknown"
    }

    private fun getCallerName(call: Call): String {
        val displayName = call.details?.callerDisplayName
        return if (!displayName.isNullOrBlank()) displayName else getPhoneNumber(call)
    }

    private fun sendCallUpdate(type: String, call: PhoneCall) {
        val envelope = MessageEnvelope(
            type = type,
            payload = Gson().toJson(call)
        )
        ConnectionManager.getInstance(applicationContext).sendEnvelope(envelope)
    }
}
