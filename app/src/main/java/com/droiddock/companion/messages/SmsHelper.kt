package com.droiddock.companion.messages

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.droiddock.companion.network.ChatMessage

class SmsHelper(private val context: Context) {

    companion object {
        private const val TAG = "DroidDockSMS"
        private val CANONICAL_ADDRESSES_URI = Uri.parse("content://mms-sms/canonical-addresses")
        private val SIMPLE_CONVERSATIONS_URI = Uri.parse("content://mms-sms/conversations?simple=true")
        private val MMS_PART_URI = Uri.parse("content://mms/part")
        private val MMS_URI = Uri.parse("content://mms")
    }

    private val contactsHelper = ContactsHelper(context)

    private val smsManager: SmsManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    fun sendSms(destinationAddress: String, text: String): Boolean {
        return try {
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(destinationAddress, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(destinationAddress, null, text, null, null)
            }
            Log.d(TAG, "Sent SMS to $destinationAddress")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $destinationAddress", e)
            false
        }
    }

    fun getThreadToPhoneMap(): Map<Long, String> {
        val recipientAddresses = mutableMapOf<String, String>() // recipient_id -> address
        try {
            val addrCursor = context.contentResolver.query(
                CANONICAL_ADDRESSES_URI,
                arrayOf("_id", "address"),
                null,
                null,
                null
            )
            addrCursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val addrIdx = it.getColumnIndex("address")
                while (it.moveToNext()) {
                    val id = it.getString(idIdx) ?: continue
                    val addr = it.getString(addrIdx) ?: continue
                    recipientAddresses[id] = addr
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query canonical addresses: ${e.message}")
        }

        val threadToPhoneMap = mutableMapOf<Long, String>() // thread_id -> phone number
        try {
            val convCursor = context.contentResolver.query(
                SIMPLE_CONVERSATIONS_URI,
                arrayOf("_id", "recipient_ids"),
                null,
                null,
                null
            )
            convCursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val recIdx = it.getColumnIndex("recipient_ids")
                while (it.moveToNext()) {
                    val threadId = it.getLong(idIdx)
                    val recIds = it.getString(recIdx) ?: continue
                    val firstRecId = recIds.split(" ").firstOrNull() ?: continue
                    recipientAddresses[firstRecId]?.let { phone ->
                        threadToPhoneMap[threadId] = phone
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query conversations: ${e.message}")
        }
        return threadToPhoneMap
    }

    /**
     * Fast query of the most recent messages (SMS + MMS/RCS).
     * Runs in ~3ms instead of scanning all historical rows.
     */
    fun getRecentMessages(limit: Int = 50): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val threadToPhoneMap = getThreadToPhoneMap()

        // 1. Query recent SMS
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "thread_id", "address", "body", "date", "type"),
                null,
                null,
                "date DESC LIMIT $limit"
            )
            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val threadIdx = it.getColumnIndex("thread_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val threadId = it.getLong(threadIdx)
                    var addr = it.getString(addrIdx) ?: ""
                    if (addr.isBlank() || addr == "null") {
                        addr = threadToPhoneMap[threadId] ?: "Unknown"
                    }
                    val body = it.getString(bodyIdx) ?: ""
                    val date = it.getLong(dateIdx)
                    val type = it.getInt(typeIdx)
                    val isOutgoing = (type == Telephony.Sms.MESSAGE_TYPE_SENT)
                    val contactName = contactsHelper.resolveContactName(addr)

                    messages.add(
                        ChatMessage(
                            id = "sms_$id",
                            threadId = addr,
                            senderName = if (isOutgoing) "Me" else contactName,
                            senderAddress = addr,
                            isFromMe = isOutgoing,
                            text = body,
                            timestamp = date,
                            status = "delivered",
                            messageType = "SMS"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying recent SMS: ${e.message}")
        }

        // 2. Query recent MMS/RCS headers
        try {
            val mmsHeaders = mutableListOf<Triple<Long, Long, Long>>() // mid, threadId, dateSec
            val mmsMeta = mutableMapOf<Long, Pair<Boolean, String>>() // mid -> (isOutgoing, isRcs)
            val mmsCursor = context.contentResolver.query(
                MMS_URI,
                arrayOf("_id", "thread_id", "date", "msg_box", "creator", "tr_id", "ct_cls"),
                null,
                null,
                "date DESC LIMIT $limit"
            )
            mmsCursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val threadIdx = it.getColumnIndex("thread_id")
                val dateIdx = it.getColumnIndex("date")
                val boxIdx = it.getColumnIndex("msg_box")
                val creatorIdx = it.getColumnIndex("creator")
                val trIdx = it.getColumnIndex("tr_id")
                val clsIdx = it.getColumnIndex("ct_cls")

                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val threadId = it.getLong(threadIdx)
                    val dateSec = it.getLong(dateIdx)
                    val msgBox = it.getInt(boxIdx)
                    val isOutgoing = (msgBox == 2)
                    val creator = if (creatorIdx != -1) it.getString(creatorIdx) else null
                    val trId = if (trIdx != -1) it.getString(trIdx) else null
                    val ctCls = if (clsIdx != -1) it.getInt(clsIdx) else -1
                    val isRcs = (creator?.contains("messaging") == true) ||
                            (trId?.startsWith("proto:") == true) ||
                            (ctCls == 135) ||
                            (creator != null && creator != "com.android.mms")

                    mmsHeaders.add(Triple(id, threadId, dateSec))
                    mmsMeta[id] = Pair(isOutgoing, if (isRcs) "RCS" else "MMS")
                }
            }

            // Query text parts ONLY for these recent MMS IDs
            if (mmsHeaders.isNotEmpty()) {
                val idsList = mmsHeaders.map { it.first }
                val partCursor = context.contentResolver.query(
                    MMS_PART_URI,
                    arrayOf("mid", "text"),
                    "ct = 'text/plain' AND mid IN (${idsList.joinToString(",")})",
                    null,
                    null
                )
                val partTexts = mutableMapOf<Long, String>()
                partCursor?.use {
                    val midIdx = it.getColumnIndex("mid")
                    val textIdx = it.getColumnIndex("text")
                    while (it.moveToNext()) {
                        val mid = it.getLong(midIdx)
                        val text = it.getString(textIdx) ?: ""
                        if (text.isNotBlank()) {
                            partTexts[mid] = text
                        }
                    }
                }

                for (header in mmsHeaders) {
                    val mid = header.first
                    val threadId = header.second
                    val dateSec = header.third
                    val body = partTexts[mid] ?: continue
                    val meta = mmsMeta[mid] ?: Pair(false, "MMS")
                    val isOutgoing = meta.first
                    val msgType = meta.second
                    val address = threadToPhoneMap[threadId] ?: "Unknown"
                    val contactName = contactsHelper.resolveContactName(address)

                    messages.add(
                        ChatMessage(
                            id = "mms_$mid",
                            threadId = address,
                            senderName = if (isOutgoing) "Me" else contactName,
                            senderAddress = address,
                            isFromMe = isOutgoing,
                            text = body,
                            timestamp = dateSec * 1000L,
                            status = "delivered",
                            messageType = msgType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying recent MMS: ${e.message}")
        }

        val deduped = deduplicate(messages)
        return deduped.sortedByDescending { it.timestamp }.take(limit)
    }

    private fun deduplicate(list: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val seenIds = HashSet<String>()
        val sorted = list.sortedBy { it.timestamp }
        for (msg in sorted) {
            val text = msg.text.trim()
            if (text.isEmpty()) continue
            if (seenIds.contains(msg.id)) continue

            var isDup = false
            val window = result.takeLast(20)
            for (ex in window) {
                if (ex.isFromMe == msg.isFromMe &&
                    ex.text.trim() == text &&
                    Math.abs(ex.timestamp - msg.timestamp) < 60_000L) {
                    isDup = true
                    break
                }
            }
            if (!isDup) {
                seenIds.add(msg.id)
                result.add(msg)
            }
        }
        return result
    }

    fun getAllMessages(): List<ChatMessage> {
        val allMessages = mutableListOf<ChatMessage>()
        val contactMap = contactsHelper.getContactMap()
        val threadToPhoneMap = getThreadToPhoneMap()

        // 2. Query ALL MMS & RCS text parts in batches (to overcome 2MB CursorWindow limit)
        val mmsPartTexts = mutableMapOf<Long, String>() // mid -> text
        try {
            var lastPartId = 0L
            var loopCount = 0
            while (true) {
                loopCount++
                val partCursor = context.contentResolver.query(
                    MMS_PART_URI,
                    arrayOf("_id", "mid", "text"),
                    "ct = 'text/plain' AND _id > $lastPartId",
                    null,
                    "_id ASC"
                )
                if (partCursor == null) {
                    Log.w(TAG, "Part cursor null at loop $loopCount with lastPartId $lastPartId")
                    break
                }

                var batchCount = 0
                val totalInCursor = partCursor.count
                partCursor.use {
                    val idIdx = it.getColumnIndex("_id")
                    val midIdx = it.getColumnIndex("mid")
                    val textIdx = it.getColumnIndex("text")

                    while (it.moveToNext()) {
                        val id = it.getLong(idIdx)
                        val mid = it.getLong(midIdx)
                        val text = it.getString(textIdx) ?: ""
                        if (text.isNotBlank()) {
                            mmsPartTexts[mid] = text
                        }
                        if (id > lastPartId) {
                            lastPartId = id
                        }
                        batchCount++
                    }
                }
                Log.d(TAG, "Part loop $loopCount: read $batchCount of $totalInCursor rows, new lastPartId=$lastPartId, total parts=${mmsPartTexts.size}")
                if (batchCount == 0) break
            }
            Log.d(TAG, "Finished loading ALL ${mmsPartTexts.size} text parts in MMS/RCS store after $loopCount loops")
        } catch (e: Exception) {
            Log.e(TAG, "Could not query MMS parts: ${e.message}", e)
        }

        // 3. Query ALL MMS/RCS headers in batches
        try {
            var lastMmsId = 0L
            var mmsLoopCount = 0
            while (true) {
                mmsLoopCount++
                val mmsCursor = context.contentResolver.query(
                    MMS_URI,
                    arrayOf("_id", "thread_id", "date", "msg_box", "creator", "tr_id", "ct_cls"),
                    "_id > $lastMmsId",
                    null,
                    "_id ASC"
                )
                if (mmsCursor == null) {
                    Log.w(TAG, "MMS cursor null at loop $mmsLoopCount with lastMmsId $lastMmsId")
                    break
                }

                var batchCount = 0
                val totalInCursor = mmsCursor.count
                mmsCursor.use {
                    val idIdx = it.getColumnIndex("_id")
                    val threadIdx = it.getColumnIndex("thread_id")
                    val dateIdx = it.getColumnIndex("date")
                    val boxIdx = it.getColumnIndex("msg_box")
                    val creatorIdx = it.getColumnIndex("creator")
                    val trIdx = it.getColumnIndex("tr_id")
                    val clsIdx = it.getColumnIndex("ct_cls")

                    while (it.moveToNext()) {
                        val id = it.getLong(idIdx)
                        if (id > lastMmsId) {
                            lastMmsId = id
                        }
                        batchCount++

                        val body = mmsPartTexts[id] ?: continue
                        val threadId = it.getLong(threadIdx)
                        val dateSec = it.getLong(dateIdx)
                        val msgBox = it.getInt(boxIdx) // 1 = inbox, 2 = sent
                        val isOutgoing = (msgBox == 2)
                        val creator = if (creatorIdx != -1) it.getString(creatorIdx) else null
                        val trId = if (trIdx != -1) it.getString(trIdx) else null
                        val ctCls = if (clsIdx != -1) it.getInt(clsIdx) else -1

                        val address = threadToPhoneMap[threadId] ?: "Unknown"
                        val contactName = contactsHelper.resolveContactName(address)

                        // Google Messages / RCS tagging
                        val isRcs = (creator?.contains("messaging") == true) ||
                                (trId?.startsWith("proto:") == true) ||
                                (ctCls == 135) ||
                                (creator != null && creator != "com.android.mms")

                        allMessages.add(
                            ChatMessage(
                                id = "mms_$id",
                                threadId = address,
                                senderName = if (isOutgoing) "Me" else contactName,
                                senderAddress = address,
                                isFromMe = isOutgoing,
                                text = body,
                                timestamp = dateSec * 1000L,
                                status = "delivered",
                                messageType = if (isRcs) "RCS" else "MMS"
                            )
                        )
                    }
                }
                Log.d(TAG, "MMS loop $mmsLoopCount: read $batchCount of $totalInCursor rows, new lastMmsId=$lastMmsId, total msgs=${allMessages.size}")
                if (batchCount == 0) break
            }
            Log.d(TAG, "Processed all MMS/RCS messages, count now: ${allMessages.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS/RCS messages", e)
        }

        // 4. Query all SMS messages in batches
        try {
            var lastSmsId = 0L
            while (true) {
                val cursor = context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    "${Telephony.Sms._ID} > $lastSmsId",
                    null,
                    "${Telephony.Sms._ID} ASC"
                ) ?: break

                var batchCount = 0
                cursor.use {
                    val idIdx = it.getColumnIndex(Telephony.Sms._ID)
                    val addrIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                    val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                    val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                    while (it.moveToNext()) {
                        val id = it.getLong(idIdx)
                        if (id > lastSmsId) {
                            lastSmsId = id
                        }
                        batchCount++

                        val addr = it.getString(addrIdx) ?: "Unknown"
                        val body = it.getString(bodyIdx) ?: ""
                        val date = it.getLong(dateIdx)
                        val type = it.getInt(typeIdx)
                        val isOutgoing = (type == Telephony.Sms.MESSAGE_TYPE_SENT)

                        val contactName = contactsHelper.resolveContactName(addr)

                        allMessages.add(
                            ChatMessage(
                                id = "sms_$id",
                                threadId = addr,
                                senderName = if (isOutgoing) "Me" else contactName,
                                senderAddress = addr,
                                isFromMe = isOutgoing,
                                text = body,
                                timestamp = date,
                                status = "delivered",
                                messageType = "SMS"
                            )
                        )
                    }
                }
                if (batchCount == 0) break
            }
            Log.d(TAG, "Processed all SMS messages, total count now: ${allMessages.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS messages", e)
        }

        // 5. Deduplicate and sort all combined messages newest first so Mac receives recent messages immediately
        val deduped = deduplicate(allMessages)
        return deduped.sortedByDescending { it.timestamp }
    }
}
