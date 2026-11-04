package com.droiddock.companion.messages

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.droiddock.companion.network.AppNotification
import com.droiddock.companion.network.ChatMessage
import com.droiddock.companion.network.ConnectionManager
import com.droiddock.companion.network.EnvelopeTypes
import com.droiddock.companion.network.MessageEnvelope
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap

class RcsNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "DroidDockRCS"

        @Volatile
        var activeInstance: RcsNotificationListenerService? = null

        // Multi-key cache: phone numbers, clean digits, contact names, thread IDs -> reply Action
        private val cachedReplyActions = ConcurrentHashMap<String, Notification.Action>()

        private val recentNotificationFingerprints = java.util.Collections.synchronizedSet(object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size > 200) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return super.add(element)
            }
        })

        fun replyToRcsConversation(context: Context, threadOrSender: String, text: String): Boolean {
            val contactsHelper = ContactsHelper(context)
            val resolvedName = contactsHelper.resolveContactName(threadOrSender)
            val clean = cleanNumber(threadOrSender)
            val suffix = if (clean.length >= 7) clean.takeLast(7) else clean

            Log.d(TAG, "Attempting RCS reply for: '$threadOrSender' (resolved: '$resolvedName', clean: '$clean')")

            // 1. Look up in cachedReplyActions with all key variations
            var action: Notification.Action? = cachedReplyActions[threadOrSender]
                ?: cachedReplyActions[clean]
                ?: cachedReplyActions[resolvedName]
                ?: cachedReplyActions[resolvedName.lowercase()]

            if (action == null && suffix.isNotEmpty()) {
                for ((key, act) in cachedReplyActions) {
                    if (key.endsWith(suffix) || suffix.endsWith(key) || key.equals(resolvedName, ignoreCase = true)) {
                        action = act
                        break
                    }
                }
            }

            // 2. If not found in cache, scan active status bar notifications directly
            if (action == null) {
                action = findActionInActiveNotifications(threadOrSender, resolvedName, clean, suffix)
            }

            if (action == null) {
                Log.w(TAG, "No active RCS reply action found for $threadOrSender ($resolvedName)")
                return false
            }

            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                Log.w(TAG, "Action found for $threadOrSender has no RemoteInputs")
                return false
            }

            val intent = Intent()
            val bundle = Bundle()
            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, text)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

            return try {
                action.actionIntent.send(context, 0, intent)
                Log.d(TAG, "Successfully dispatched RCS reply to $threadOrSender ($resolvedName) via RemoteInput")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send RCS reply to $threadOrSender", e)
                false
            }
        }

        private fun findActionInActiveNotifications(
            target: String,
            name: String,
            clean: String,
            suffix: String
        ): Notification.Action? {
            val service = activeInstance ?: return null
            try {
                val activeSbns = service.activeNotifications ?: return null
                for (sbn in activeSbns) {
                    val pkg = sbn.packageName
                    if (!pkg.contains("messaging") && pkg != "com.google.android.apps.messaging") continue

                    val extras = sbn.notification.extras ?: continue
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""

                    val matches = title.contains(target, ignoreCase = true) ||
                            title.contains(name, ignoreCase = true) ||
                            convTitle.contains(target, ignoreCase = true) ||
                            convTitle.contains(name, ignoreCase = true) ||
                            (clean.isNotEmpty() && cleanNumber(title).endsWith(suffix))

                    if (matches) {
                        val actions = sbn.notification.actions ?: continue
                        for (act in actions) {
                            if (!act.remoteInputs.isNullOrEmpty()) {
                                // Cache for future lookups
                                cacheAction(act, title, name, clean)
                                return act
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error searching active notifications: ${e.message}")
            }
            return null
        }

        fun cacheAction(action: Notification.Action, sender: String, contactName: String?, rawNumber: String?) {
            if (sender.isNotBlank()) {
                cachedReplyActions[sender] = action
                cachedReplyActions[sender.lowercase()] = action
            }
            if (!contactName.isNullOrBlank()) {
                cachedReplyActions[contactName] = action
                cachedReplyActions[contactName.lowercase()] = action
            }
            if (!rawNumber.isNullOrBlank()) {
                val clean = cleanNumber(rawNumber)
                cachedReplyActions[rawNumber] = action
                cachedReplyActions[clean] = action
                if (clean.length >= 7) {
                    cachedReplyActions[clean.takeLast(7)] = action
                }
            }
            Log.d(TAG, "Cached RCS reply action for aliases: sender='$sender', contact='$contactName', num='$rawNumber'")
        }

        private fun cleanNumber(number: String): String {
            return number.filter { it.isDigit() }
        }

        fun cancelNotificationByKey(key: String): Boolean {
            val instance = activeInstance ?: return false
            return try {
                instance.cancelNotification(key)
                Log.d(TAG, "Cancelled notification: $key")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification: ${e.message}")
                false
            }
        }

        fun syncAllActiveNotifications(context: Context) {
            val instance = activeInstance ?: return
            try {
                val sbns = instance.activeNotifications ?: return
                val notifs = mutableListOf<AppNotification>()
                val pm = context.packageManager

                for (sbn in sbns) {
                    if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                    val extras = sbn.notification.extras ?: continue
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: ""
                    if (title.isBlank() && text.isBlank()) continue

                    val appName = try {
                        val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        sbn.packageName
                    }

                    var canReply = false
                    sbn.notification.actions?.forEach { act ->
                        if (!act.remoteInputs.isNullOrEmpty()) canReply = true
                    }

                    val artworkBase64 = extractArtworkBase64(context, sbn)
                    notifs.add(
                        AppNotification(
                            id = sbn.key,
                            packageName = sbn.packageName,
                            appName = appName,
                            title = title,
                            text = text,
                            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                            timestamp = sbn.postTime,
                            iconBase64 = artworkBase64,
                            canReply = canReply,
                            isClearable = sbn.isClearable
                        )
                    )
                }

                val envelope = MessageEnvelope(
                    type = EnvelopeTypes.NOTIFICATION_SYNC,
                    payload = Gson().toJson(notifs)
                )
                ConnectionManager.getInstance(context).sendEnvelope(envelope)
                Log.d(TAG, "Synced ${notifs.size} active notifications with Mac")
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing active notifications: ${e.message}")
            }
        }

        fun extractArtworkBase64(context: Context, sbn: StatusBarNotification): String? {
            return try {
                val largeIcon = sbn.notification.getLargeIcon()
                val drawable = largeIcon?.loadDrawable(context) ?: context.packageManager.getApplicationIcon(sbn.packageName)
                val bitmap = when (drawable) {
                    is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                    else -> {
                        val w = if (drawable.intrinsicWidth > 0) Math.min(drawable.intrinsicWidth, 128) else 64
                        val h = if (drawable.intrinsicHeight > 0) Math.min(drawable.intrinsicHeight, 128) else 64
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                val stream = java.io.ByteArrayOutputStream()
                val scaled = if (bitmap.width > 128 || bitmap.height > 128) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                } else {
                    bitmap
                }
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        Log.d(TAG, "RcsNotificationListenerService connected")
        refreshActiveNotifications()
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun refreshActiveNotifications() {
        try {
            val sbns = activeNotifications ?: return
            for (sbn in sbns) {
                processNotification(sbn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh active notifications: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val envelope = MessageEnvelope(
            type = EnvelopeTypes.NOTIFICATION_REMOVED,
            payload = Gson().toJson(mapOf("id" to sbn.key))
        )
        ConnectionManager.getInstance(applicationContext).sendEnvelope(envelope)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val cleanText = text.trim()
        if (title.isBlank() && cleanText.isBlank()) return

        val isMessagingApp = pkg.contains("messaging") ||
                pkg.contains("mms") ||
                pkg == "com.google.android.apps.messaging" ||
                pkg == "com.samsung.android.messaging"

        // Inspect notification actions for RemoteInput
        val actions = sbn.notification.actions
        var canReply = false
        if (actions != null) {
            for (action in actions) {
                if (!action.remoteInputs.isNullOrEmpty()) {
                    canReply = true
                    if (isMessagingApp) {
                        val sender = if (title.isNotBlank()) title else "Unknown"
                        val contactsHelper = ContactsHelper(applicationContext)
                        val resolvedName = contactsHelper.resolveContactName(sender)
                        cacheAction(action, sender, resolvedName, null)
                    }
                    break
                }
            }
        }

        // Filter out Google Messages internal background tasks, pairing status, and system noise
        val isSystemNoise = title.equals("Device pairing", ignoreCase = true) ||
                (title.equals("Messages", ignoreCase = true) && !canReply) ||
                title.equals("Unknown", ignoreCase = true) ||
                cleanText.contains("Messages is doing work", ignoreCase = true) ||
                cleanText.contains("Your messages are available", ignoreCase = true) ||
                cleanText.contains("pairing with", ignoreCase = true) ||
                cleanText.contains("Chat features", ignoreCase = true) ||
                cleanText.contains("RCS chats", ignoreCase = true)

        if (isSystemNoise) {
            Log.d(TAG, "Ignoring Google Messages background service notification: '$title' / '$cleanText'")
            return
        }

        // 1. If messaging app, handle RCS/SMS chat pipeline ONLY for real user messages with reply capability
        if (isMessagingApp && cleanText.isNotEmpty() && !sbn.isOngoing && canReply) {
            val sender = if (title.isNotBlank()) title else "Unknown"
            val contactsHelper = ContactsHelper(applicationContext)
            val resolvedName = contactsHelper.resolveContactName(sender)

            val contactMap = contactsHelper.getContactMap()
            var matchedPhone: String? = null
            for ((phone, name) in contactMap) {
                if (name.equals(sender, ignoreCase = true) || name.equals(resolvedName, ignoreCase = true)) {
                    matchedPhone = phone
                    break
                }
            }
            val threadAddress = matchedPhone ?: sender

            val msgFingerprint = "msg:$threadAddress:$cleanText:${sbn.postTime / 10000}"
            if (recentNotificationFingerprints.add(msgFingerprint)) {
                val chatMessage = ChatMessage(
                    id = sbn.key,
                    threadId = threadAddress,
                    senderName = if (resolvedName.isNotBlank() && resolvedName != "Unknown") resolvedName else sender,
                    senderAddress = threadAddress,
                    isFromMe = false,
                    text = cleanText,
                    timestamp = sbn.postTime,
                    status = "delivered",
                    messageType = if (canReply) "RCS" else "SMS"
                )
                val chatEnv = MessageEnvelope(
                    type = EnvelopeTypes.CHAT_MESSAGE,
                    payload = Gson().toJson(chatMessage)
                )
                ConnectionManager.getInstance(applicationContext).sendEnvelope(chatEnv)
            }
        }

        // 2. Forward as general AppNotification for Notifications Tab & macOS Toasts
        val notifFingerprint = "notif:${sbn.key}:${title.hashCode()}:${cleanText.hashCode()}:${sbn.postTime / 5000}"
        if (!recentNotificationFingerprints.add(notifFingerprint)) {
            return
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        val artworkBase64 = extractArtworkBase64(applicationContext, sbn)

        val appNotification = AppNotification(
            id = sbn.key,
            packageName = pkg,
            appName = appName,
            title = if (title.isNotBlank()) title else appName,
            text = cleanText,
            subText = subText,
            timestamp = sbn.postTime,
            iconBase64 = artworkBase64,
            canReply = canReply,
            isClearable = sbn.isClearable
        )

        val notifEnvelope = MessageEnvelope(
            type = EnvelopeTypes.NOTIFICATION_POSTED,
            payload = Gson().toJson(appNotification)
        )
        ConnectionManager.getInstance(applicationContext).sendEnvelope(notifEnvelope)
    }
}
