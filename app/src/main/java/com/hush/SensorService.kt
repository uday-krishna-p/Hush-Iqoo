package com.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the microphone and the Nearby link alive when the screen turns off
 * or the app is backgrounded. All the real work is in [Engine]; this only holds the process open.
 *
 * Android only lets a microphone service start while the app is on screen. If the system ever restarts
 * this service on its own (it will not: START_NOT_STICKY), or a start slips through from the background,
 * the start is refused; we log it, post a plain "tap to listen again" notification and stop. The passive
 * probe port stays armed regardless, so the next probe wakes the phone properly.
 */
class SensorService : Service() {

    companion object {
        const val EXTRA_ROLE = "role"
        const val EXTRA_PROBE_ACTIVATED = "probeActivated"
        private const val CHANNEL = "hush"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_STOPPED_ID = 3
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HLog.init(applicationContext)
        val role = intent?.getStringExtra(EXTRA_ROLE) ?: Engine.role ?: Engine.ROLE_SENSOR
        val byProbe = intent?.getBooleanExtra(EXTRA_PROBE_ACTIVATED, false) ?: false
        HLog.d("SensorService onStartCommand role=$role byProbe=$byProbe intent=${intent != null}")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hush listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hush")
            .setContentText(when {
                byProbe -> "Rescue sensor active (woken by a rescuer's probe)"
                Engine.isHousehold(role) -> "Listening for household sounds (nothing leaves the phone)"
                else -> "Listening as $role"
            })
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            HLog.d("SensorService: startForeground refused ($e). Not on screen? Posting a tap-to-listen notification and stopping.")
            try {
                nm.notify(NOTIFICATION_STOPPED_ID, Notification.Builder(this, CHANNEL)
                    .setContentTitle("Hush stopped listening")
                    .setContentText("Tap to open Hush and listen again.")
                    .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                    .setContentIntent(open).setAutoCancel(true).build())
            } catch (e: Exception) { HLog.d("SensorService: ignored $e") }
            stopSelf()
            return START_NOT_STICKY
        }
        nm.cancel(NOTIFICATION_STOPPED_ID)
        // startForeground can return without an exception and still leave the service in the background
        // (ef39, 27 Sep 23:38: started by the laptop with its screen off; startForegroundCount stayed 0 and the
        // microphone was silenced 5 s later). foregroundServiceType is 0 unless it really is foreground.
        if (Build.VERSION.SDK_INT >= 29) {
            val type = foregroundServiceType
            HLog.d(if (type != 0) "SensorService: foreground, type=0x${Integer.toHexString(type)}"
                   else "SensorService: WARNING startForeground did not take effect (screen off?); the microphone will be silenced once the app leaves the screen")
        }
        Engine.start(applicationContext, role)
        if (byProbe) Engine.markActivatedByProbe()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        HLog.d("SensorService destroyed")
        Engine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
