package com.droiddock.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DroidDockApplication : Application() {
    companion object {
        const val CHANNEL_ID = "droiddock_service_channel"
        lateinit var instance: DroidDockApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connection status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows your connection to the Mac while Droid Link runs in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
