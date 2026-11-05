package com.droiddock.companion.mirroring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.droiddock.companion.network.ConnectionManager
import com.droiddock.companion.network.EnvelopeTypes
import com.droiddock.companion.network.MessageEnvelope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Secrets stay in process memory; intents carry only a session identifier. */
object MirrorCoordinator {
    data class Request(val offer: MirrorOffer, val host: String, val expiresAt: Long)
    private var request: Request? = null
    private val currentState = MutableStateFlow("Not sharing")
    val state = currentState.asStateFlow()
    private val pendingState = MutableStateFlow<String?>(null)
    val pending = pendingState.asStateFlow()
    @Volatile var activeSession: String? = null
        private set

    @Synchronized fun receive(context: Context, offer: MirrorOffer, host: String) {
        require(offer.port in 1..65535 && offer.maxSize in 320..1920 && offer.fps in 15..60 && offer.bitrate in 500_000..12_000_000)
        java.util.UUID.fromString(offer.sessionId)
        require(java.util.Base64.getDecoder().decode(offer.key).size == 32)
        if (activeSession == null && request != null && android.os.SystemClock.elapsedRealtime() >= request!!.expiresAt) {
            request = null; pendingState.value = null
        }
        if (activeSession != null || request != null) {
            status(context, offer.sessionId, "error", "A screen sharing session is already pending or active. Stop it on the phone first.")
            return
        }
        request = Request(offer, host, android.os.SystemClock.elapsedRealtime() + 120_000)
        pendingState.value = offer.sessionId
        currentState.value = "Mac requested screen sharing"
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("mirror_requests", "Screen sharing requests", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MirrorConsentActivity::class.java).putExtra("sessionId", offer.sessionId)
        val action = PendingIntent.getActivity(context, offer.sessionId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            notifications.notify(4101, NotificationCompat.Builder(context, "mirror_requests")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Share your screen with Droid Link?")
                .setContentText("Tap to review and approve sharing with your paired Mac.")
                .setContentIntent(action).setAutoCancel(true).setTimeoutAfter(120_000).build())
        } catch (_: SecurityException) { /* The same action remains available in the companion dashboard. */ }
        status(context, offer.sessionId, "consent", "Open Droid Link on your phone or tap its notification to approve screen sharing.")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            synchronized(this) {
                if (request?.offer?.sessionId == offer.sessionId && activeSession == null) {
                    stop(context, offer.sessionId, "The sharing request expired. Start mirroring again on the Mac.")
                }
            }
        }, 120_000)
    }

    @Synchronized fun get(sessionId: String): Request? = request?.takeIf {
        it.offer.sessionId == sessionId && (activeSession == sessionId || android.os.SystemClock.elapsedRealtime() < it.expiresAt)
    }

    @Synchronized fun activate(context: Context, sessionId: String): Request? {
        val result = get(sessionId) ?: return null
        activeSession = sessionId
        pendingState.value = null
        context.getSystemService(NotificationManager::class.java).cancel(4101)
        currentState.value = "Sharing screen with Mac"
        MirrorAccessibilityService.instance?.sessionChanged(true)
        return result
    }

    @Synchronized fun stop(context: Context, sessionId: String? = null, message: String = "Screen sharing stopped", reason: String = "stopped") {
        val id = activeSession ?: request?.offer?.sessionId ?: return
        if (sessionId != null && sessionId != id) return
        activeSession = null
        request = null
        pendingState.value = null
        currentState.value = message
        context.getSystemService(NotificationManager::class.java).cancel(4101)
        context.stopService(Intent(context, ScreenCaptureService::class.java))
        MirrorAccessibilityService.instance?.sessionChanged(false)
        status(context, id, reason, message)
    }

    fun requestControlSettings(context: Context) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val action = PendingIntent.getActivity(context, 4103, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            notifications.notify(4103, NotificationCompat.Builder(context, "mirror_requests")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Enable Droid Link remote control")
                .setContentText("Tap to open Accessibility settings, then enable Droid Link remote control.")
                .setContentIntent(action).setAutoCancel(true).setTimeoutAfter(120_000).build())
        } catch (_: SecurityException) {}
    }

    fun status(context: Context, id: String, state: String, message: String) {
        ConnectionManager.getInstance(context).sendEnvelope(MessageEnvelope(type = EnvelopeTypes.MIRROR_STATUS,
            payload = Gson().toJson(MirrorStatus(id, state, message))))
    }
}
