package com.hush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hush.audio.Haptics
import com.hush.audio.SoundAlerts
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone DOES when [SoundAlerts] names a household sound (persona C): a vibration pattern unique to the
 * category (alarm-class, full strength, like the Hush buzz), a high-importance notification whose full-screen
 * intent opens [AlertActivity] over the lock screen when the screen is off (the same mechanism as the rescue
 * beacon), and a line in the history file so somebody who was asleep sees "Doorbell 14:32".
 * The ALERT screen, when it is showing, gets the alert through [Engine.Listener.onAlert] and flashes itself.
 */
object Alerting {
    const val CHANNEL = "alerts"
    const val NOTIFICATION_ID = 4
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_WORD = "word"
    const val EXTRA_COLOUR = "colour"
    const val EXTRA_AT = "at"
    const val EXTRA_REPEATS = "repeats"
    const val HISTORY_FILE = "alerts.jsonl"
    const val HISTORY_KEEP = 100
    /** A repeating (ALARM) buzz stops on its own after this if nobody dismisses it. */
    private const val REPEAT_MAX_MS = 60_000L

    data class Entry(val atWallMs: Long, val category: String, val word: String, val detail: String, val score: Float)

    /** Settings from the ALERT screen: flash the camera light with alerts; night mode = torch for everything and the alert screen stays lit. */
    @Volatile var torch = true
    @Volatile var night = false
    private val main = Handler(Looper.getMainLooper())
    private val stopRepeat = Runnable { appContext?.let { Haptics.cancel(it, "Alert buzz") } }
    private var appContext: Context? = null
    private val history = ArrayList<Entry>()
    private var historyLoaded = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
        loadHistory()
    }

    /** Main thread. */
    fun fire(a: SoundAlerts.Alert, screenShowing: Boolean) {
        val ctx = appContext ?: return
        val wall = System.currentTimeMillis()
        HLog.d("ALERT ${a.category} word='${a.word}' ${a.detail}" + (if (a.extended) " (continuing)" else "") + " screenShowing=$screenShowing")
        if (a.extended) {
            // The sound is still going: keep the notification current, no new buzz.
            notify(ctx, a, wall)
            return
        }
        record(Entry(wall, a.category.name, a.word, a.detail, a.score))
        if (torch || night) Torch.strobe(ctx, if (a.repeats) REPEAT_MAX_MS else 3000L)
        main.removeCallbacks(stopRepeat)
        if (a.repeats) {
            Haptics.vibrate(ctx, a.pattern, "Alert buzz ${a.category}", repeatFrom = 1)
            main.postDelayed(stopRepeat, REPEAT_MAX_MS)
        } else {
            Haptics.vibrate(ctx, a.pattern, "Alert buzz ${a.category}")
        }
        notify(ctx, a, wall)
        if (!screenShowing) {
            // Screen off or another app in front: the full-screen intent above turns the screen on and shows
            // AlertActivity over the lock screen; on phones that refuse full-screen intents the notification remains.
            HLog.d("ALERT: screen not showing, relying on the full-screen intent")
        }
    }

    private fun notify(ctx: Context, a: SoundAlerts.Alert, wall: Long) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Household sound alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Doorbell, knocks, alarms, shouting heard by this phone"
                enableVibration(false)          // we vibrate ourselves, with the category's pattern
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            val full = Intent(ctx, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CATEGORY, a.category.name).putExtra(EXTRA_WORD, a.word)
                .putExtra(EXTRA_COLOUR, a.colour).putExtra(EXTRA_AT, wall).putExtra(EXTRA_REPEATS, a.repeats)
            val pi = PendingIntent.getActivity(ctx, 4, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(a.word)
                .setContentText("Heard at ${clock.format(Date(wall))} · tap to see")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(a.colour)
                .setColorized(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setOngoing(a.repeats)
                .setAutoCancel(true)
                .build()
            val canFull = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            nm.notify(NOTIFICATION_ID, n)
            if (!a.extended) HLog.d("ALERT notification posted (enabled=${nm.areNotificationsEnabled()}, fullScreen=$canFull)")
        } catch (e: Exception) {
            HLog.d("ALERT notification failed: $e")
        }
    }

    /** The person saw it: stop a repeating buzz and drop the notification. */
    fun dismiss(context: Context) {
        val ctx = context.applicationContext
        main.removeCallbacks(stopRepeat)
        Torch.stop()
        Haptics.cancel(ctx, "Alert buzz")
        try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (e: Exception) { HLog.d("ALERT dismiss failed: $e") }
        HLog.d("ALERT dismissed")
    }

    /** The TEST row and the laptop hook: everything a real alert does, without a sound. */
    fun test(category: SoundAlerts.Category, screenShowing: Boolean) {
        val word = if (category == SoundAlerts.Category.KNOCK) "KNOCK ×3 (test)" else category.word + " (test)"
        val pattern = if (category == SoundAlerts.Category.KNOCK) longArrayOf(0, 120, 380, 120, 380, 120) else category.pattern
        fire(SoundAlerts.Alert(category, word, "test", 1f, pattern, category.repeats, android.os.SystemClock.elapsedRealtime(), extended = false), screenShowing)
    }

    // ---- history ----

    @Synchronized fun history(): List<Entry> = history.toList()

    @Synchronized private fun record(e: Entry) {
        history.add(0, e)
        while (history.size > HISTORY_KEEP) history.removeAt(history.size - 1)
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, HISTORY_FILE)
            f.appendText(JSONObject().put("t", e.atWallMs).put("cat", e.category).put("word", e.word).put("detail", e.detail).put("score", e.score.toDouble()).toString() + "\n")
            // Keep the file from growing forever: rewrite it when it holds twice what we show.
            if (f.length() > 64 * 1024) f.writeText(history.reversed().joinToString("") {
                JSONObject().put("t", it.atWallMs).put("cat", it.category).put("word", it.word).put("detail", it.detail).put("score", it.score.toDouble()).toString() + "\n" })
        } catch (e: Exception) { HLog.d("ALERT history write failed: $e") }
    }

    @Synchronized private fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, HISTORY_FILE)
            if (!f.exists()) return
            val lines = f.readLines().filter { it.isNotBlank() }.takeLast(HISTORY_KEEP)
            for (l in lines.reversed()) {
                val o = JSONObject(l)
                history.add(Entry(o.getLong("t"), o.getString("cat"), o.getString("word"), o.optString("detail"), o.optDouble("score", 0.0).toFloat()))
            }
            HLog.d("ALERT history loaded: ${history.size} entries")
        } catch (e: Exception) { HLog.d("ALERT history load failed: $e") }
    }

    // ---- taught sounds (SoundLibrary) ----

    const val SOUNDS_FILE = "sounds.txt"

    fun loadLibrary(lib: SoundLibrary) {
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, SOUNDS_FILE)
            if (f.exists()) { lib.fromLines(f.readLines()); HLog.d("TEACH: ${lib.sounds.size} taught sound(s) loaded: " + lib.sounds.joinToString { it.name }) }
        } catch (e: Exception) { HLog.d("TEACH load failed: $e") }
    }

    fun saveLibrary(lib: SoundLibrary) {
        val ctx = appContext ?: return
        try { File(ctx.filesDir, SOUNDS_FILE).writeText(lib.toLines().joinToString("\n") + "\n"); HLog.d("TEACH: ${lib.sounds.size} sound(s) saved") }
        catch (e: Exception) { HLog.d("TEACH save failed: $e") }
    }

    fun format(e: Entry): String = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(e.atWallMs)) + "  " + e.word
}
