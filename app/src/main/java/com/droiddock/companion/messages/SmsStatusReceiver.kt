package com.droiddock.companion.messages

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.droiddock.companion.network.ConnectionManager
import com.droiddock.companion.network.EnvelopeTypes
import com.droiddock.companion.network.MessageEnvelope
import com.google.gson.Gson

/** Explicit PendingIntents report the carrier's result, including every multipart segment. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("messageId") ?: return
        val delivery = intent.getBooleanExtra("delivery", false)
        val part = intent.getIntExtra("part", -1)
        val count = intent.getIntExtra("count", 0)
        if (part !in 0 until count) return
        val reportStatus = if (delivery) {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            if (pdu != null && format != null) {
                android.telephony.SmsMessage.createFromPdu(pdu, format)?.status
            } else null
        } else null
        // GSM status 32..63 is temporary; a later report may still confirm delivery.
        if (reportStatus != null && reportStatus in 32..63) return
        val key = "$id:${if (delivery) "delivery" else "sent"}"
        val prefs = context.getSharedPreferences("sms_send_results", Context.MODE_PRIVATE)
        val status: String
        synchronized(SmsStatusReceiver::class.java) {
            val results = prefs.getStringSet(key, emptySet())!!.toMutableSet()
            // A failed segment makes the whole message fail; later segments cannot undo it.
            if ("failed" in results || "done" in results) return
            if (resultCode != Activity.RESULT_OK || (reportStatus != null && reportStatus >= 64)) {
                prefs.edit().putStringSet(key, setOf("failed")).apply()
                status = "failed"
            } else {
                results.add(part.toString())
                if (results.size < count) {
                    prefs.edit().putStringSet(key, results).apply()
                    return
                }
                prefs.edit().putStringSet(key, setOf("done")).apply()
                status = if (delivery) "delivered" else "sent"
            }
        }
        val payload = mapOf("id" to id, "status" to status, "protocol" to "SMS")
        ConnectionManager.getInstance(context).sendEnvelope(
            MessageEnvelope(type = EnvelopeTypes.CHAT_STATUS_UPDATE, payload = Gson().toJson(payload))
        )
    }

    companion object {
        fun pendingIntent(context: Context, id: String, part: Int, count: Int, delivery: Boolean): PendingIntent {
            val intent = Intent(context, SmsStatusReceiver::class.java).apply {
                data = Uri.Builder().scheme("droiddock").authority("sms-result")
                    .appendPath(id).appendPath(part.toString()).appendPath(delivery.toString()).build()
                putExtra("messageId", id)
                putExtra("part", part)
                putExtra("count", count)
                putExtra("delivery", delivery)
            }
            return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
