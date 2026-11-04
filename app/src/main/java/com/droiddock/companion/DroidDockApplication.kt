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
                "DroidDock Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains persistent end-to-end encrypted link with your Mac"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
