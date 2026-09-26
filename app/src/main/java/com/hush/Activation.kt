package com.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hush.audio.Haptics

/**
 * What a passive phone does the moment a commander's probe reaches it: a high-priority notification whose
 * full-screen intent turns the screen on and shows [BeaconActivity] over the lock screen (the same
 * mechanism an incoming call uses), plus a long haptic pattern the person can feel through a pocket.
 * BeaconActivity then starts the sensor service; that is allowed because the app is now on screen.
 */
object Activation {
    const val CHANNEL = "rescue"
    const val NOTIFICATION_ID = 2
    const val EXTRA_COMMANDER = "commander"
    const val EXTRA_RSSI = "rssi"
    private const val PREFS = "hush"
    private const val KEY_LAST_PROBE = "lastProbeAt"
    private const val KEY_ACTIVATED_BY = "activatedBy"
    private const val DEBOUNCE_MS = 60_000L

    fun onProbe(context: Context, commander: String, rssi: Int) {
        val app = context.applicationContext
        if (Engine.isRunning) {
            // Already a sensor or the commander: no alert screen, but buzz and say so (27 Sep 02:39: the team pressed
            // the sweep with every phone already in a role and nothing visible happened anywhere).
            HLog.d("Activation: probe from $commander while already running as ${Engine.role}: acknowledged on screen, no alert")
            Engine.noteProbeHeard(commander, rssi)
            return
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_PROBE, 0L) < DEBOUNCE_MS) { HLog.d("Activation: probe from $commander within ${DEBOUNCE_MS / 1000} s of the last one, ignored"); return }
        prefs.edit().putLong(KEY_LAST_PROBE, now).putString(KEY_ACTIVATED_BY, commander).apply()

        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rescue alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A rescue team nearby has activated this phone as a sensor"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val beacon = Intent(app, BeaconActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_COMMANDER, commander).putExtra(EXTRA_RSSI, rssi)
        val pi = PendingIntent.getActivity(app, 1, beacon, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val canFullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(app.getString(R.string.notif_title))
            .setContentText(app.getString(R.string.notif_text))
            .setStyle(Notification.BigTextStyle().bigText(app.getString(R.string.notif_text)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        val allowed = nm.areNotificationsEnabled()
        nm.notify(NOTIFICATION_ID, n)
        HLog.d("Activation: notification posted (notifications enabled=$allowed, full-screen allowed=$canFullScreen)")
        Haptics.rescuePulse(app)
    }

    fun cancel(context: Context) {
        try { context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (e: Exception) { HLog.d("Activation: cancel failed $e") }
    }

    fun activatedBy(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVATED_BY, null)
}
