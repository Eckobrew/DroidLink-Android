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
import kotlinx.coroutines.*

class RcsNotificationListenerService : NotificationListenerService() {
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        fun syncStoredImages(context: Context) {
            val gson = Gson()
            val messages = context.getSharedPreferences("received_image_messages", Context.MODE_PRIVATE).all.values
                .mapNotNull { value -> try { gson.fromJson(value as? String, ChatMessage::class.java) } catch (_: Exception) { null } }
            for (chunk in messages.chunked(50)) {
                ConnectionManager.getInstance(context).sendEnvelope(MessageEnvelope(
                    type = EnvelopeTypes.CHAT_SYNC, payload = gson.toJson(chunk)))
            }
        }

        fun replyToRcsConversation(context: Context, threadOrSender: String, text: String): Boolean {
            val contactsHelper = ContactsHelper(context)
            val resolvedName = contactsHelper.resolveContactName(threadOrSender)
            val clean = cleanNumber(threadOrSender)
            val suffix = if (clean.length >= 7) clean.takeLast(7) else clean

            Log.d(TAG, "Attempting RCS reply for: '$threadOrSender' (resolved: '$resolvedName', clean: '$clean')")

            val action = findActionInActiveNotifications(threadOrSender, resolvedName, clean, suffix)

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

        fun canReplyToConversation(context: Context, address: String): Boolean {
            val clean = cleanNumber(address)
            return findActionInActiveNotifications(address, ContactsHelper(context).resolveContactName(address),
                clean, clean.takeLast(7)) != null
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

                    val candidates = listOf(title, convTitle).filter { it.isNotBlank() }
                    val matches = candidates.any { candidate ->
                        candidate.equals(target, ignoreCase = true) ||
                        (name.isNotBlank() && candidate.equals(name, ignoreCase = true)) ||
                        (clean.length >= 7 && cleanNumber(candidate).length >= 7 && cleanNumber(candidate).endsWith(suffix))
                    }

                    if (matches) {
                        val actions = sbn.notification.actions ?: continue
                        for (act in actions) {
                            if (act.remoteInputs?.any { it.allowFreeFormInput } == true) {
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

        fun isSystemNoiseNotification(title: String, text: String, pkg: String, isOngoing: Boolean, canReply: Boolean): Boolean {
            val t = title.lowercase().trim()
            val txt = text.lowercase().trim()
            val isMsg = pkg.contains("messaging") || pkg == "com.google.android.apps.messaging" || pkg.contains("mms")

            if (t == "device pairing" || t == "pairing code" || t.contains("device pairing")) return true
            if (txt.contains("messages is doing work") || txt.contains("doing work in the background")) return true
            if (txt.contains("your messages are available") || txt.contains("pairing with")) return true
            if (txt.contains("chat features") || txt.contains("rcs chats")) return true
            if (txt.contains("connected to messages for web")) return true
            if (isMsg && (t == "messages" || t == "unknown" || t.isEmpty()) && !canReply) return true
            if (isMsg && isOngoing && !canReply) return true

            return false
        }

        fun isControllableMediaNotification(sbn: StatusBarNotification): Boolean {
            val extras = sbn.notification.extras ?: return false
            val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                    extras.containsKey("android.mediaSession")
            val isTransportCategory = sbn.notification.category == Notification.CATEGORY_TRANSPORT
            val hasMediaActions = sbn.notification.actions?.any { act ->
                val at = act.title?.toString()?.lowercase() ?: ""
                at.contains("pause") || at.contains("play") || at.contains("previous") || at.contains("next")
            } == true

            return hasMediaSession || isTransportCategory || (sbn.isOngoing && hasMediaActions)
        }

        fun controlMedia(context: Context, action: String) {
            if (activeInstance == null) return
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            val component = android.content.ComponentName(context, RcsNotificationListenerService::class.java)
            try {
                val controllers = manager.getActiveSessions(component)
                val controller = controllers.firstOrNull {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                } ?: controllers.firstOrNull() ?: return
                val controls = controller.transportControls
                when (action) {
                    "play_pause" -> if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else { controls.play() }
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    "volume_up" -> controller.adjustVolume(android.media.AudioManager.ADJUST_RAISE, 0)
                    "volume_down" -> controller.adjustVolume(android.media.AudioManager.ADJUST_LOWER, 0)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification access is required for media controls", e)
            }
        }

        fun syncAllActiveNotifications(context: Context) {
            val instance = activeInstance ?: return
            try {
                val sbns = instance.activeNotifications ?: return
                val notifs = mutableListOf<AppNotification>()
                val pm = context.packageManager

                for (sbn in sbns) {
                    if (sbn.packageName == context.packageName) continue
                    if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                    val extras = sbn.notification.extras ?: continue
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: ""
                    if (title.isBlank() && text.isBlank()) continue

                    var canReply = false
                    sbn.notification.actions?.forEach { act ->
                        if (!act.remoteInputs.isNullOrEmpty()) canReply = true
                    }

                    if (isSystemNoiseNotification(title, text, sbn.packageName, sbn.isOngoing, canReply)) continue

                    val appName = try {
                        val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        sbn.packageName
                    }

                    val artworkBase64 = extractArtworkBase64(context, sbn)
                    val isMedia = isControllableMediaNotification(sbn)
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
                            isClearable = sbn.isClearable,
                            isMediaPlayback = isMedia
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
        imageScope.cancel()
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

    private fun forwardNotificationImages(sbn: StatusBarNotification, title: String) {
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        val imageMessages = style?.messages?.filter { it.dataUri != null && it.dataMimeType?.startsWith("image/") == true } ?: emptyList()
        val hasPicture = sbn.notification.extras.containsKey(Notification.EXTRA_PICTURE)
        if (imageMessages.isEmpty() && !hasPicture) return
        fun send(localUrl: String, timestamp: Long, sender: String, text: String) {
            if (sender.isBlank()) return
            val groupTitle = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: title
            val isGroup = style?.isGroupConversation == true
            val threadId = if (isGroup) "notification_group_" + java.security.MessageDigest.getInstance("SHA-256")
                .digest("${sbn.packageName}:$groupTitle".toByteArray()).joinToString("") { "%02x".format(it) } else sender
            val image = ChatMessage(
                id = "notification_image_" + localUrl.substringAfter(":"), threadId = threadId,
                senderName = if (isGroup) groupTitle else title, senderAddress = if (isGroup) groupTitle else sender, isFromMe = false,
                text = text, timestamp = timestamp, status = "delivered", messageType = "Unconfirmed",
                attachments = listOf(com.droiddock.companion.network.MessageAttachment(localUrl, "image/jpeg")))
            val json = Gson().toJson(image)
            synchronized(ImageMessages) {
                val prefs = getSharedPreferences("received_image_messages", MODE_PRIVATE)
                prefs.edit().putString(image.id, json).apply()
                // Retain a bounded received-image history, including images received while offline.
                if (prefs.all.size > 200) {
                    val oldest = prefs.all.mapNotNull { (key, value) ->
                        try {
                            val stored = Gson().fromJson(value as? String, ChatMessage::class.java)
                            if (stored == null) null else key to stored
                        } catch (_: Exception) { null }
                    }.minByOrNull { it.second.timestamp }
                    if (oldest != null) {
                        prefs.edit().remove(oldest.first).apply()
                        oldest.second.attachments.forEach { attachment ->
                            val key = attachment.url.removePrefix("notification-image:")
                            if (Regex("[a-f0-9]{64}").matches(key)) java.io.File(java.io.File(filesDir, "received_images"), "$key.jpg").delete()
                        }
                    }
                }
            }
            ConnectionManager.getInstance(applicationContext).sendEnvelope(MessageEnvelope(
                type = EnvelopeTypes.CHAT_MESSAGE, payload = json))
        }
        for (message in imageMessages) {
            if (message.dataUri?.authority == "mms") {
                ConnectionManager.getInstance(applicationContext).syncRecentMessages()
                continue
            }
            // A null sender represents the current user, rather than a new incoming image.
            if (message.person == null) continue
            try {
                val url = ImageMessages.captureNotificationImage(applicationContext, message.dataUri!!,
                    "${sbn.key}:${message.timestamp}:${message.dataUri}")
                val sender = message.person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:") ?: title
                send(url, message.timestamp, sender, message.text?.toString() ?: "")
            } catch (e: Exception) { Log.w(TAG, "Messaging app did not expose a readable image", e) }
        }
        if (imageMessages.isEmpty()) {
            // BigPicture is another public notification representation of an incoming image.
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 33) {
                sbn.notification.extras.getParcelable(Notification.EXTRA_PICTURE, android.graphics.Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                sbn.notification.extras.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE)
            }
            if (bitmap != null) {
                try {
                    val url = ImageMessages.captureNotificationBitmap(applicationContext, bitmap, "${sbn.key}:${sbn.postTime}")
                    send(url, sbn.postTime, title, "")
                } catch (e: Exception) { Log.w(TAG, "Notification photo was unavailable", e) }
            }
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
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
        if (isSystemNoiseNotification(title, cleanText, pkg, sbn.isOngoing, canReply)) {
            Log.d(TAG, "Ignoring Google Messages background service notification: '$title' / '$cleanText'")
            return
        }

        // 1. If messaging app, cache action for RemoteInput reply and trigger fast database sync
        if (isMessagingApp && cleanText.isNotEmpty() && !sbn.isOngoing && canReply) {
            val sender = if (title.isNotBlank()) title else "Unknown"
            val contactsHelper = ContactsHelper(applicationContext)
            val resolvedName = contactsHelper.resolveContactName(sender)

            val replyAction = actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            if (replyAction != null) {
                cacheAction(replyAction, sender, resolvedName, null)
            }

            // Sync authoritative message directly from telephony provider to avoid duplicate synthetic messages
            ConnectionManager.getInstance(applicationContext).syncRecentMessages()
        }

        if (isMessagingApp && !sbn.isOngoing) {
            imageScope.launch {
                try { forwardNotificationImages(sbn, title) }
                catch (e: Exception) { Log.w(TAG, "Could not inspect notification images", e) }
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
        val isMedia = isControllableMediaNotification(sbn)

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
            isClearable = sbn.isClearable,
            isMediaPlayback = isMedia
        )

        val notifEnvelope = MessageEnvelope(
            type = EnvelopeTypes.NOTIFICATION_POSTED,
            payload = Gson().toJson(appNotification)
        )
        ConnectionManager.getInstance(applicationContext).sendEnvelope(notifEnvelope)
    }
}
