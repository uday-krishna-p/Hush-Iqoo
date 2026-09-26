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
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import com.hush.audio.AccelChannel
import com.hush.audio.Haptics
import com.hush.audio.Ping
import com.hush.model.Alert
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happens after [CrashDetector] says the owner may have fallen (docs/PLAN-crash.md, build A):
 *
 *   IDLE ──candidate passes──▶ COUNTDOWN (30 s, siren + buzz + red screen) ──I'M OK──▶ IDLE
 *                                   └── expires / CALL NOW ──▶ ESCALATED ──I'M OK──▶ IDLE
 *
 * ESCALATED: the alert goes up the mesh to the commander (and on to every phone), the Bluetooth tag carries the SOS
 * flag, the emergency contacts are texted and called (build B; in DRY RUN every step is only logged), the dialer is
 * opened on 112. Everything the phone did is kept in [actions]: that list is the evidence, never the system call log.
 * Lives outside Engine so an alarm that is already running survives a role change.
 */
object CrashGuard {

    const val COUNTDOWN_S = 30
    private const val ESCALATED_MAX_MS = 10 * 60_000L
    private const val SIREN_EVERY_MS_COUNTDOWN = 3_000L
    private const val SIREN_EVERY_MS_ESCALATED = 6_000L
    private const val REPEAT_ALERT_MS = 10_000L
    private const val TRACE_SECONDS = 300
    const val CHANNEL = "crash"
    const val NOTIFICATION_ID = 4
    private const val PREFS = "hush_crash"
    private const val KEY_MODE = "mode"
    const val MODE_DRY_RUN = "dryrun"
    const val MODE_LIVE = "live"

    enum class State { IDLE, COUNTDOWN, ESCALATED }

    /** The countdown screen redraws on every change. */
    interface Listener { fun onCrashChanged() }

    @Volatile var state = State.IDLE
        private set
    @Volatile var listener: Listener? = null
    @Volatile var kind: String = ""
        private set
    @Volatile var candidate: CrashDetector.Candidate? = null
        private set
    /** What this phone did since it escalated, oldest first ("SMS Priya: no SIM", "call Ravi: rang 14 s"). */
    val actions = ArrayList<String>()
    /** Relayed back by the commander: who is coming / who called (build A2). */
    @Volatile var responders: List<String> = emptyList()
    @Volatile var countdownEndMs = 0L
        private set
    @Volatile var escalatedAtMs = 0L
        private set
    private var atWallMs = 0L
    private var seq = 0
    private var cancelledByUserAtS = -1

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var detector: CrashDetector? = null
    private var accel: AccelChannel? = null
    private var trace: Trace? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Has this phone raised an alarm that is still active (for the SOS bit in the Bluetooth tag)? */
    val sos: Boolean get() = state == State.ESCALATED

    // ---- Detection (attached by Engine.start, detached by Engine.stop) ----

    fun attach(context: Context, channel: AccelChannel) {
        ctx = context.applicationContext
        val det = CrashDetector { c -> main.post { onCandidate(c) } }
        det.maxRangeMs2 = channel.maxRangeMs2
        detector = det
        accel = channel
        startTrace()
        channel.rawSink = { isGyro, t, x, y, z ->
            if (isGyro) det.gyro(t, x, y, z) else det.accel(t, x, y, z)
            trace?.write(isGyro, t, x, y, z)
        }
        HLog.d("Crash: guard attached, mode=${mode(context)}, accelerometer full scale %.1f m/s², contacts=${EmergencyContacts.list(context).size}".format(channel.maxRangeMs2))
    }

    fun detach() {
        accel?.rawSink = null
        accel = null
        detector = null
        trace?.close(); trace = null
        if (state != State.IDLE) HLog.d("Crash: guard detached while $state; the alarm keeps running")
        else HLog.d("Crash: guard detached")
    }

    fun mode(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_DRY_RUN) ?: MODE_DRY_RUN

    fun dryRun(context: Context) = mode(context) != MODE_LIVE

    fun setMode(context: Context, m: String): String {
        val v = if (m.equals(MODE_LIVE, ignoreCase = true)) MODE_LIVE else MODE_DRY_RUN
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, v).apply()
        HLog.d("Crash: mode set to $v")
        return "crash mode = $v"
    }

    private fun onCandidate(c: CrashDetector.Candidate) {
        HLog.d("Crash: candidate $c")
        if (!c.passed) return
        val context = ctx ?: return
        trigger(context, c.kind, c)
    }

    // ---- The alarm ----

    /** Starts the countdown. [kind] FALL / IMPACT / TEST / MANUAL. Ignored while an alarm is already active. */
    fun trigger(context: Context, kindOf: String, c: CrashDetector.Candidate?) {
        val app = context.applicationContext
        ctx = app
        if (state != State.IDLE) { HLog.d("Crash: $kindOf while $state, ignored"); return }
        state = State.COUNTDOWN
        kind = kindOf
        candidate = c
        actions.clear(); responders = emptyList(); cancelledByUserAtS = -1
        seq++
        atWallMs = System.currentTimeMillis()
        countdownEndMs = SystemClock.elapsedRealtime() + COUNTDOWN_S * 1000L
        HLog.d("Crash: COUNTDOWN started ($kindOf, seq $seq, ${COUNTDOWN_S} s, mode=${mode(app)}, ${cellular(app).second})")
        showScreen(app)
        Engine.sessionLog.addRecord("crash", mapOf("state" to Alert.COUNTDOWN, "kind" to kindOf, "seq" to seq, "candidate" to c?.toString()))
        sendAlert(Alert.COUNTDOWN)
        main.removeCallbacks(tick); main.post(tick)
        listener?.onCrashChanged()
    }

    /** The person tapped I'M OK: everything stops, the commander is told. */
    fun imOk() {
        val app = ctx
        if (state == State.IDLE) return
        val was = state
        cancelledByUserAtS = if (was == State.COUNTDOWN) COUNTDOWN_S - secondsLeft() else -1
        HLog.d("Crash: cancelled by user while $was" + if (was == State.COUNTDOWN) " at ${cancelledByUserAtS} s" else " after ${(SystemClock.elapsedRealtime() - escalatedAtMs) / 1000} s")
        state = State.IDLE
        main.removeCallbacks(tick)
        actions.add("${clock.format(Date())} I'M OK pressed")
        Engine.sessionLog.addRecord("crash", mapOf("state" to Alert.CANCELLED, "kind" to kind, "seq" to seq, "actions" to actions.toList()))
        sendAlert(Alert.CANCELLED)
        if (app != null) {
            try { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (e: Exception) { HLog.d("Crash: cancel notification failed $e") }
            Engine.refreshTag()
            EmergencyDialer.cancel(app)
        }
        listener?.onCrashChanged()
    }

    /** The person (or the expired countdown) asks for help now. */
    fun callNow() {
        if (state != State.COUNTDOWN) return
        HLog.d("Crash: CALL NOW pressed with ${secondsLeft()} s left")
        escalate()
    }

    fun secondsLeft(): Int = if (state != State.COUNTDOWN) 0 else ((countdownEndMs - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

    private var lastSirenMs = 0L
    private var lastAlertMs = 0L
    private val tick = object : Runnable {
        override fun run() {
            val app = ctx ?: return
            val now = SystemClock.elapsedRealtime()
            when (state) {
                State.IDLE -> return
                State.COUNTDOWN -> {
                    if (now - lastSirenMs >= SIREN_EVERY_MS_COUNTDOWN) { lastSirenMs = now; siren(app) }
                    if (now >= countdownEndMs) { HLog.d("Crash: countdown expired with no response"); escalate(); return }
                }
                State.ESCALATED -> {
                    if (now - escalatedAtMs > ESCALATED_MAX_MS) {
                        HLog.d("Crash: ${ESCALATED_MAX_MS / 60_000} min escalated, going quiet (alert stays until I'M OK)")
                        return
                    }
                    if (now - lastSirenMs >= SIREN_EVERY_MS_ESCALATED) { lastSirenMs = now; siren(app) }
                    if (now - lastAlertMs >= REPEAT_ALERT_MS) sendAlert(Alert.ESCALATED)
                }
            }
            listener?.onCrashChanged()
            main.postDelayed(this, 1000)
        }
    }

    private fun escalate() {
        val app = ctx ?: return
        if (state != State.COUNTDOWN) return
        state = State.ESCALATED
        escalatedAtMs = SystemClock.elapsedRealtime()
        val dry = dryRun(app)
        val (cell, cellWhy) = cellular(app)
        HLog.d("Crash: ESCALATED (seq $seq, ${if (dry) "DRY RUN" else "LIVE"}, $cellWhy)")
        // 1. the mesh: commander first, everyone else through it (relayed in build A2)
        val routed = Engine.meshRouted()
        actions.add("${clock.format(Date())} " + if (routed) "told the rescue team over the mesh" else "no mesh route: rescue team NOT told")
        // 2. the Bluetooth tag carries the SOS flag (a commander scanning nearby sees it with no mesh at all)
        Engine.refreshTag()
        actions.add("${clock.format(Date())} SOS flag on the Bluetooth tag")
        // 3–5. texts, calls, the dialer on 112 (EmergencyDialer; every attempt and its outcome is appended to actions)
        EmergencyDialer.escalate(app, dry, cell, cellWhy, buildAlert(Alert.ESCALATED)) { line ->
            main.post { actions.add("${clock.format(Date())} $line"); listener?.onCrashChanged(); sendAlert(Alert.ESCALATED) }
        }
        Engine.sessionLog.addRecord("crash", mapOf("state" to Alert.ESCALATED, "kind" to kind, "seq" to seq, "dryRun" to dry, "cellular" to cell, "cellularWhy" to cellWhy))
        sendAlert(Alert.ESCALATED)
        listener?.onCrashChanged()
    }

    /** Whether this phone could text or call right now, with the reason in words for the screen and the log. */
    fun cellular(context: Context): Pair<Boolean, String> = try {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sim = tm.simState == TelephonyManager.SIM_STATE_READY
        when {
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY) -> false to "no phone radio"
            !sim -> false to "no SIM card"
            airplane -> false to "airplane mode is on"
            else -> true to "SIM ready"
        }
    } catch (e: Exception) { HLog.d("Crash: cellular check failed $e"); false to "phone state unknown" }

    fun buildAlert(st: String): Alert {
        val app = ctx
        val fix = Engine.gpsFix()
        val c = candidate
        val (cell, _) = if (app != null) cellular(app) else (false to "")
        val contacts = if (st == Alert.ESCALATED && app != null) EmergencyContacts.list(app).map { it.name to it.number } else emptyList()
        return Alert(Engine.name, Engine.letter.takeIf { it != "?" }, kind, st, seq, atWallMs, c?.peakMs2 ?: 0f, c?.stillSeconds ?: 0,
            Engine.batteryPercent(), fix?.latitude, fix?.longitude, fix?.accuracy, cell, contacts, actions.toList())
    }

    private fun sendAlert(st: String) {
        lastAlertMs = SystemClock.elapsedRealtime()
        try { Engine.sendAlert(buildAlert(st)) } catch (e: Exception) { HLog.d("Crash: sendAlert failed $e") }
    }

    private fun siren(app: Context) {
        try {
            Ping.play(app, listOf(900 to 250, 1400 to 250, 900 to 250, 1400 to 250, 900 to 250, 1400 to 250), 1.0f)
            Haptics.vibrate(app, longArrayOf(0, 700, 150, 700, 150, 700), "Crash: buzz")
        } catch (e: Exception) { HLog.d("Crash: siren failed $e") }
    }

    /**
     * The red countdown screen over the lock screen. A full-screen notification is the legitimate way to bring a screen up
     * from the background (the incoming-call mechanism, as [Activation] does for the probe); when the app is already on
     * screen Android may show it as a small banner instead, so the activity is also started directly then.
     */
    private fun showScreen(app: Context) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fall / crash alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "This phone thinks its owner has fallen and counts down before calling for help"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            val i = Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(app, 4, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(app.getString(R.string.crash_notif_title))
                .setContentText(app.getString(R.string.crash_notif_text, COUNTDOWN_S))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            HLog.d("Crash: alarm notification posted (notifications enabled=${nm.areNotificationsEnabled()}, full-screen allowed=$fsi, app on screen=${Engine.listener != null})")
            if (Engine.listener != null) app.startActivity(i)
        } catch (e: Exception) { HLog.d("Crash: could not show the countdown screen: $e") }
    }

    // ---- Raw motion trace for tuning (files/motion.csv, first TRACE_SECONDS after attach; laptop analysis only) ----

    private class Trace(file: File) {
        private val w = BufferedWriter(FileWriter(file), 1 shl 16)
        private var t0 = -1L
        @Volatile var open = true
        var lines = 0
        fun write(isGyro: Boolean, t: Long, x: Float, y: Float, z: Float) {
            if (!open) return
            try {
                if (t0 < 0) t0 = t
                if (t - t0 > TRACE_SECONDS * 1000L) { close(); return }
                w.write("$t,${if (isGyro) 'g' else 'a'},$x,$y,$z\n"); lines++
            } catch (e: Exception) { HLog.d("Crash: trace write failed $e"); open = false }
        }
        fun close() {
            if (!open) return
            open = false
            try { w.close(); HLog.d("Crash: motion trace closed, $lines lines") } catch (e: Exception) { HLog.d("Crash: trace close failed $e") }
        }
    }

    /** (Re)starts the 300 s trace now; also done at attach. `adb shell am start -n com.hush/.MainActivity --ez motionrec true`. */
    fun startTrace(): String {
        val app = ctx ?: return "engine not running"
        trace?.close()
        return try {
            trace = Trace(File(app.filesDir, "motion.csv"))
            HLog.d("Crash: motion trace started (files/motion.csv, ${TRACE_SECONDS} s, accelerometer + gyroscope at 200 Hz)")
            "motion trace started"
        } catch (e: Exception) { HLog.d("Crash: could not start the motion trace: $e"); "trace failed: $e" }
    }

    /** One line for the sensor screen while an alarm is active. */
    fun statusLine(): String = when (state) {
        State.IDLE -> ""
        State.COUNTDOWN -> "⚠ $kind DETECTED · calling for help in ${secondsLeft()} s unless I'M OK is pressed"
        State.ESCALATED -> "🚨 $kind · help called ${(SystemClock.elapsedRealtime() - escalatedAtMs) / 1000} s ago · ${actions.size} actions"
    }
}
