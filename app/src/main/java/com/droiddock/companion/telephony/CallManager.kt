package com.droiddock.companion.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import android.util.Log
import com.droiddock.companion.messages.ContactsHelper
import com.droiddock.companion.network.PhoneCall

class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "DroidDockCallMgr"
    }

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val contactsHelper = ContactsHelper(context)

    fun placeCall(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val telecom = telecomManager ?: return false
        if (!telecom.hasManageOngoingCallsPermission() && telecom.defaultDialerPackage != context.packageName) return false
        return try {
            telecom.placeCall(Uri.fromParts("tel", phoneNumber, null), null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Phone rejected call request", e)
            false
        }
    }

    fun getAllCalls(): List<PhoneCall> {
        val calls = mutableListOf<PhoneCall>()
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_CALL_LOG permission not granted")
                return emptyList()
            }

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val id = it.getString(idIdx)
                    val number = it.getString(numIdx) ?: "Unknown"
                    val cachedName = if (nameIdx != -1) it.getString(nameIdx) else null
                    val resolvedName = if (!cachedName.isNullOrBlank()) cachedName else contactsHelper.resolveContactName(number)

                    val type = it.getInt(typeIdx)
                    val date = it.getLong(dateIdx)
                    val duration = it.getLong(durIdx).toDouble()

                    val (callState, direction) = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "ended" to "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "ended" to "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed" to "incoming"
                        CallLog.Calls.REJECTED_TYPE -> "rejected" to "incoming"
                        else -> "ended" to "incoming"
                    }

                    calls.add(
                        PhoneCall(
                            id = "call_$id",
                            phoneNumber = number,
                            contactName = if (resolvedName.isNotBlank()) resolvedName else number,
                            state = callState,
                            direction = direction,
                            startTime = date,
                            duration = duration
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query CallLog", e)
        }
        return calls
    }
}
