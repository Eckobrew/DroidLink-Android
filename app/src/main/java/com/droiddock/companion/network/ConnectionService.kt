package com.droiddock.companion.network

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.droiddock.companion.DroidDockApplication
import com.droiddock.companion.MainActivity
import com.droiddock.companion.crypto.SecureStorage
import kotlinx.coroutines.*

/** Connected-device foreground service keeps the paired link alive off-screen. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun notification(status: String) = NotificationCompat.Builder(this, DroidDockApplication.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Droid Link connection")
            .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        ServiceCompat.startForeground(this, 7101, notification("Looking for your Mac"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch {
            ConnectionManager.getInstance(applicationContext).transport.collect { transport ->
                val text = when (transport) {
                    "wifi" -> "Connected over Wi-Fi • Bluetooth fallback ready when available"
                    "bluetooth" -> "Syncing over Bluetooth • Looking for Wi-Fi"
                    else -> "Waiting for your Mac over Wi-Fi or Bluetooth"
                }
                try { getSystemService(android.app.NotificationManager::class.java).notify(7101, notification(text)) }
                catch (_: SecurityException) { /* Android still exposes foreground service status in Task Manager. */ }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SecureStorage(this).getStoredPeerPublicKey() == null) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
