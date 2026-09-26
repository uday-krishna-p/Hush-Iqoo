package com.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the microphone and the Nearby link alive when the screen turns off
 * or the app is backgrounded. All the real work is in [Engine]; this only holds the process open.
 */
class SensorService : Service() {

    companion object {
        const val EXTRA_ROLE = "role"
        private const val CHANNEL = "hush"
        private const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val role = intent?.getStringExtra(EXTRA_ROLE) ?: Engine.role ?: Engine.ROLE_SENSOR
        HLog.d("SensorService onStartCommand role=$role")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hush listening", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hush")
            .setContentText("Listening as $role")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Engine.start(applicationContext, role)
        return START_STICKY
    }

    override fun onDestroy() {
        HLog.d("SensorService destroyed")
        Engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
