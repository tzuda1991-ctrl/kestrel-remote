// Kotlin | RemoteService.kt | Android foreground service | visible status and notification polling
package com.kestrel.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.content.Context
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val channelId = "kestrel_connection"
    private val eventChannelId = "kestrel_events"

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(7001, serviceNotification())
        executor.scheduleWithFixedDelay(
            { pollSafely() },
            0,
            30,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun pollSafely() {
        val prefs = getSharedPreferences("kestrel", MODE_PRIVATE)
        val baseUrl = prefs.getString("base_url", null) ?: return
        val token = prefs.getString("token", null) ?: return
        val deviceId = prefs.getString("device_id", null) ?: return

        try {
            val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .coerceIn(0, 100)

            ApiClient.heartbeat(
                baseUrl,
                deviceId,
                token,
                "${Build.MANUFACTURER} ${Build.MODEL}",
                Build.VERSION.RELEASE ?: "Unknown",
                battery
            )

            val commands = ApiClient.commands(baseUrl, deviceId, token)
            for (command in commands) {
                val id = command.optInt("id")
                val type = command.optString("command")
                val payload = command.optString("payload")
                val result = when (type) {
                    "notify" -> {
                        showRemoteNotification(payload)
                        "Notification displayed"
                    }
                    else -> "Unsupported command"
                }
                if (id > 0) {
                    ApiClient.result(baseUrl, deviceId, token, id, result)
                }
            }
        } catch (_: Exception) {
            // Keep the foreground service alive; the next scheduled poll retries.
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Kestrel connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when this device is connected to Kestrel Remote."
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    eventChannelId,
                    "Remote notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Visible notifications sent by your paired administrator."
                }
            )
        }
    }

    private fun serviceNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Kestrel Remote is connected")
            .setContentText("Device status sharing is active. Tap Disconnect in the app to stop.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showRemoteNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, eventChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Message from Kestrel administrator")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
