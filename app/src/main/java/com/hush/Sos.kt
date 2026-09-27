package com.hush

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOS by text message (27 Sep, team: "for now as we dont have a sim its fine to send a failed message").
 * Two triggers: a woken phone whose owner did not press I AM SAFE within [UNANSWERED_S] seconds (to the ambulance
 * number and the emergency contact), and REPORT AN EMERGENCY on the first screen (to the ambulance number).
 * Only SMS, never a call: Android places emergency CALLS even without a SIM, SMS needs one. With no SIM or in
 * airplane mode the phone's radio answers "failed", which is shown on screen, in a notification and in the log.
 * The team chose the real 108; once a SIM is in, these messages really go out.
 */
object Sos {
    const val AMBULANCE = "108"
    const val UNANSWERED_S = 30
    private const val PREFS = "hush"
    private const val KEY_CONTACT = "sosContact"
    private const val ACTION_SENT = "com.hush.SOS_SENT"
    private const val CHANNEL = "sos"
    private const val FIX_WAIT_MS = 8_000L
    private const val RADIO_WAIT_MS = 20_000L
    private val seq = AtomicInteger(100)
    private val main = Handler(Looper.getMainLooper())

    fun contact(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONTACT, null)?.takeIf { it.isNotBlank() }

    fun setContact(ctx: Context, number: String?) {
        val clean = number?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CONTACT, clean).apply()
        HLog.d("SOS: emergency contact set to ${clean ?: "(none)"}")
    }

    fun canSend(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun phoneName(ctx: Context): String {
        val id = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (e: Exception) { HLog.d("SOS: no ANDROID_ID $e"); "" }
        return "${Build.MODEL}-${id.takeLast(4)}"
    }

    /** The phone did not answer the rescue alert: ambulance + emergency contact. */
    fun sendUnanswered(ctx: Context, onUpdate: (String) -> Unit) {
        val to = listOfNotNull(AMBULANCE, contact(ctx)).distinct()
        send(ctx, "UNANSWERED", to, onUpdate) { where ->
            "SOS (Hush): no reply to a rescue alert on phone ${phoneName(ctx)} for $UNANSWERED_S s. Person may be trapped. $where"
        }
    }

    /** REPORT AN EMERGENCY on the first screen: the ambulance number, with this phone's location. */
    fun sendReport(ctx: Context, onUpdate: (String) -> Unit) {
        send(ctx, "REPORT", listOf(AMBULANCE), onUpdate) { where ->
            "EMERGENCY reported via Hush in this area (building collapse / people may be trapped). $where"
        }
    }

    private fun send(ctx: Context, kind: String, numbers: List<String>, onUpdate: (String) -> Unit, text: (String) -> String) {
        val app = ctx.applicationContext
        HLog.d("SOS $kind: to ${numbers.joinToString()}, finding the location")
        onUpdate("Finding your location…")
        locate(app) { loc ->
            val where = if (loc != null) {
                val ageS = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0)
                "Location: https://maps.google.com/?q=%.5f,%.5f (±%d m, %s)".format(Locale.US, loc.latitude, loc.longitude, loc.accuracy.toInt(),
                    if (ageS < 120) "now" else "${ageS / 60} min old")
            } else "Location unknown (no GPS fix)."
            val body = text(where) + " " + SimpleDateFormat("HH:mm dd MMM", Locale.US).format(Date())
            HLog.d("SOS $kind: location ${loc?.let { "${it.latitude},${it.longitude} ±${it.accuracy} (${it.provider})" } ?: "none"}; message: $body")
            val results = LinkedHashMap<String, String>()
            numbers.forEach { results[it] = "sending…" }
            fun show() {
                val s = results.entries.joinToString("\n") { (n, r) -> "SMS to ${label(app, n)}: $r" }
                onUpdate(s)
                notify(app, kind, s + "\n\n" + body)
            }
            show()
            numbers.forEach { n -> sendOne(app, kind, n, body) { r -> results[n] = r; show() } }
        }
    }

    private fun label(ctx: Context, n: String) = when (n) { AMBULANCE -> "ambulance $n"; contact(ctx) -> "emergency contact $n"; else -> n }

    @SuppressLint("MissingPermission")
    private fun sendOne(app: Context, kind: String, number: String, body: String, done: (String) -> Unit) {
        if (!canSend(app)) {
            HLog.d("SOS $kind: SEND_SMS not granted, opening the messages app for $number instead")
            try {
                app.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                done("not allowed to send by itself: opened in Messages, press send")
            } catch (e: Exception) {
                HLog.d("SOS $kind: could not open the messages app: $e")
                done("FAILED (no SMS permission, no messages app)")
            }
            return
        }
        val action = "$ACTION_SENT.${seq.incrementAndGet()}"
        var answered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (answered) return
                answered = true
                try { app.unregisterReceiver(this) } catch (e: Exception) { HLog.d("SOS: unregister failed $e") }
                val r = resultText(resultCode)
                HLog.d("SOS $kind: SMS to $number -> $r (code $resultCode)")
                done(r)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        main.postDelayed({
            if (answered) return@postDelayed
            answered = true
            try { app.unregisterReceiver(receiver) } catch (e: Exception) { HLog.d("SOS: unregister failed $e") }
            HLog.d("SOS $kind: SMS to $number: no answer from the radio in ${RADIO_WAIT_MS / 1000} s")
            done("FAILED (no answer from the phone's radio)")
        }, RADIO_WAIT_MS)
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            val sent = ArrayList<PendingIntent>()
            repeat(parts.size) {
                sent += PendingIntent.getBroadcast(app, seq.incrementAndGet(), Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
            }
            HLog.d("SOS $kind: SMS to $number handed to the radio (${parts.size} part(s))")
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
        } catch (e: Exception) {
            HLog.d("SOS $kind: SMS to $number threw: $e")
            if (!answered) { answered = true; try { app.unregisterReceiver(receiver) } catch (x: Exception) { HLog.d("SOS: unregister failed $x") }; done("FAILED (${e.javaClass.simpleName}: ${e.message ?: "no SIM?"})") }
        }
    }

    private fun resultText(code: Int) = when (code) {
        Activity.RESULT_OK -> "SENT"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "FAILED (no service: no SIM or no signal)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "FAILED (radio off: airplane mode or no SIM)"
        SmsManager.RESULT_ERROR_NULL_PDU -> "FAILED (message could not be built)"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "FAILED (no SIM / network refused)"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "FAILED (SMS limit reached)"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "FAILED (short number not allowed)"
        else -> "FAILED (code $code)"
    }

    /** A fresh fix if one comes within [FIX_WAIT_MS], else the newest last-known fix of any provider, else null. */
    @SuppressLint("MissingPermission")
    private fun locate(app: Context, done: (Location?) -> Unit) {
        val lm = app.getSystemService(LocationManager::class.java)
        var finished = false
        fun finish(l: Location?, why: String) {
            if (finished) return
            finished = true
            HLog.d("SOS: location $why")
            done(l ?: lastKnown(lm))
        }
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine || Build.VERSION.SDK_INT < 30) { finish(null, "from last known (permission=$fine)"); return }
        val cancel = CancellationSignal()
        main.postDelayed({ cancel.cancel(); finish(null, "no fresh fix in ${FIX_WAIT_MS / 1000} s, using last known") }, FIX_WAIT_MS)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) { HLog.d("SOS: provider $p off"); continue }
                lm.getCurrentLocation(p, cancel, app.mainExecutor) { l -> if (l != null) finish(l, "fresh from $p") }
            } catch (e: Exception) { HLog.d("SOS: getCurrentLocation($p) failed: $e") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? = try {
        lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { HLog.d("SOS: lastKnown($p) $e"); null } }
            .maxByOrNull { it.elapsedRealtimeNanos }
    } catch (e: Exception) { HLog.d("SOS: lastKnown failed $e"); null }

    private fun notify(app: Context, kind: String, text: String) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SOS messages", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(if (kind == "REPORT") "Emergency report" else "SOS: no reply to the rescue alert")
                .setContentText(text.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(if (kind == "REPORT") 31 else 30, n)
        } catch (e: Exception) { HLog.d("SOS: notification failed $e") }
    }
}
