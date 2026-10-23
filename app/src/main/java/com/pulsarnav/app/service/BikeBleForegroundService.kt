package com.pulsarnav.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pulsarnav.app.ui.MainActivity

class BikeBleForegroundService : Service {

    constructor() : super()

    companion object {
        const val CHANNEL_ID = "pulsar_nav_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, BikeBleForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Catch any background service start restriction
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BikeBleForegroundService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification = buildNotification("Pulsar Navigation Ready", "Connected to bike console")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Safe guard against missing FGS permissions during development
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pulsar Navigation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth connection active with Bajaj Pulsar cluster"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
