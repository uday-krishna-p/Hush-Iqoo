package com.hush

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.hush.model.Alert

/**
 * Texts and calls the emergency contacts, then opens the dialer on 112 (docs/PLAN-crash.md, build B).
 *
 * Team decision 27 Sep: the attempt is the deliverable. Every step is tried for real whether or not a SIM is present,
 * and its outcome is handed to [report] ("SMS Priya: sent", "call Ravi: not connected (no SIM card)", "call Ravi:
 * rang 14 s, not answered"). Those lines are the evidence; the app never writes to the system call log. In DRY RUN
 * (the default until the team switches it) every step is only described, nothing is sent.
 *
 * Order: texts to all contacts first (a text gets through where a call does not), then calls one contact after
 * another on speaker (off-hook ≥ 20 s counts as answered, voicemail included: honest limit), then the dialer on 112
 * (a normal app may not place an emergency call itself; one tap remains). Texts repeat every 5 min with the fresh
 * position until I'M OK.
 */
object EmergencyDialer {

    private const val ANSWERED_AFTER_MS = 20_000L
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val GIVE_UP_CALL_MS = 60_000L
    private const val NEXT_CALL_GAP_MS = 5_000L
    private const val REPEAT_SMS_MS = 5 * 60_000L
    private const val ACTION_SENT = "com.hush.SMS_SENT"
    private const val ACTION_DELIVERED = "com.hush.SMS_DELIVERED"

    private val main = Handler(Looper.getMainLooper())
    private var report: ((String) -> Unit)? = null
    private var alert: Alert? = null
    private var contacts: List<EmergencyContacts.Contact> = emptyList()
    private var callIndex = -1
    private var callStartedMs = 0L
    private var offHookMs = 0L
    private var sawOffHook = false
    private var callback: Any? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var smsSent = false
    private var active = false

    fun escalate(context: Context, dryRun: Boolean, cellular: Boolean, cellularWhy: String, a: Alert, report: (String) -> Unit) {
        cancelInternal(context, "restart")
        this.report = report; alert = a; active = true
        contacts = EmergencyContacts.list(context)
        val number = EmergencyContacts.emergencyNumber(context)
        val text = smsText(context, a)
        HLog.d("Crash: SMS text: $text")
        if (contacts.isEmpty()) report("no emergency contacts set (EMERGENCY CONTACTS on the first screen)")
        if (!cellular) report("texts and calls will probably fail: $cellularWhy (trying anyway, outcomes below)")
        if (dryRun) {
            for (c in contacts) report("DRY RUN: would SMS ${c.label} ${EmergencyContacts.mask(c.number)}")
            for (c in contacts) report("DRY RUN: would call ${c.label} ${EmergencyContacts.mask(c.number)} on speaker")
            report("DRY RUN: would open the dialer on $number")
            return
        }
        val canSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (!canSms) report("SMS permission not granted: no texts (Settings → Apps → Hush → Permissions)")
        if (!canCall) report("phone permission not granted: no automatic calls, only the dialer")
        if (canSms && contacts.isNotEmpty()) sendAllSms(context, text)
        if (canCall && contacts.isNotEmpty()) { callIndex = -1; main.postDelayed({ callNext(context) }, 1500) }
        else openDialer(context, number)
        if (canSms && contacts.isNotEmpty()) main.postDelayed(repeatSms, REPEAT_SMS_MS)
    }

    private val repeatSms = object : Runnable {
        override fun run() {
            val ctx = Engine.appContextOrNull() ?: return
            if (!active || CrashGuard.state != CrashGuard.State.ESCALATED) return
            val a = CrashGuard.buildAlert(Alert.ESCALATED)
            report?.invoke("repeating the text with the fresh position")
            sendAllSms(ctx, smsText(ctx, a))
            main.postDelayed(this, REPEAT_SMS_MS)
        }
    }

    // ---- SMS ----

    private fun sendAllSms(context: Context, text: String) {
        ensureSmsReceiver(context)
        for ((i, c) in contacts.withIndex()) {
            try {
                val sm = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                val sent = PendingIntent.getBroadcast(context, 100 + i, Intent(ACTION_SENT).setPackage(context.packageName).putExtra("who", c.label), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val delivered = PendingIntent.getBroadcast(context, 200 + i, Intent(ACTION_DELIVERED).setPackage(context.packageName).putExtra("who", c.label), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val parts = sm.divideMessage(text)
                if (parts.size == 1) sm.sendTextMessage(c.number, null, text, sent, delivered)
                else sm.sendMultipartTextMessage(c.number, null, parts, arrayListOf<PendingIntent>().apply { repeat(parts.size) { add(sent) } }, arrayListOf<PendingIntent>().apply { repeat(parts.size) { add(delivered) } })
                smsSent = true
                report?.invoke("SMS ${c.label} ${EmergencyContacts.mask(c.number)}: handed to the phone (${parts.size} part${if (parts.size == 1) "" else "s"}), waiting for the result")
            } catch (e: Exception) {
                report?.invoke("SMS ${c.label}: failed at once ($e)")
            }
        }
    }

    private fun ensureSmsReceiver(context: Context) {
        if (smsReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val who = intent.getStringExtra("who") ?: "?"
                val code = resultCode
                val line = when (intent.action) {
                    ACTION_SENT -> "SMS $who: " + when (code) {
                        Activity.RESULT_OK -> "sent"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "NOT sent (no service)"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "NOT sent (radio off / airplane mode)"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "NOT sent (empty)"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "NOT sent (generic failure: no SIM?)"
                        else -> "NOT sent (result $code)"
                    }
                    else -> "SMS $who: delivered"
                }
                report?.invoke(line)
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_SENT); addAction(ACTION_DELIVERED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(r, filter)
        smsReceiver = r
    }

    // ---- Calls, one contact after another ----

    private fun callNext(context: Context) {
        if (!active) return
        callIndex++
        if (callIndex >= contacts.size) { openDialer(context, EmergencyContacts.emergencyNumber(context)); return }
        val c = contacts[callIndex]
        sawOffHook = false; offHookMs = 0L; callStartedMs = SystemClock.elapsedRealtime()
        watchCalls(context)
        try {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${c.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            report?.invoke("call ${c.label} ${EmergencyContacts.mask(c.number)}: dialling on speaker")
            HLog.d("Crash: ACTION_CALL to ${EmergencyContacts.mask(c.number)}")
        } catch (e: Exception) {
            report?.invoke("call ${c.label}: could not start ($e)")
            main.postDelayed({ callNext(context) }, NEXT_CALL_GAP_MS)
            return
        }
        main.postDelayed(connectCheck, CONNECT_TIMEOUT_MS)
        main.postDelayed(giveUp, GIVE_UP_CALL_MS)
    }

    private val connectCheck = Runnable {
        val ctx = Engine.appContextOrNull() ?: return@Runnable
        if (!active || sawOffHook) return@Runnable
        val (_, why) = CrashGuard.cellular(ctx)
        report?.invoke("call ${contacts.getOrNull(callIndex)?.label}: not connected ($why)")
        main.removeCallbacks(giveUp)
        main.postDelayed({ callNext(ctx) }, NEXT_CALL_GAP_MS)
    }

    private val giveUp = Runnable {
        // Off-hook for a minute with no end: somebody is talking (or voicemail). Stop the sequence, leave the call alone.
        if (!active || !sawOffHook) return@Runnable
        report?.invoke("call ${contacts.getOrNull(callIndex)?.label}: in progress for ${(SystemClock.elapsedRealtime() - offHookMs) / 1000} s, treating as answered")
        unwatchCalls()
    }

    private fun onCallState(context: Context, state: Int) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!sawOffHook) { sawOffHook = true; offHookMs = now; main.removeCallbacks(connectCheck); speakerOn(context) }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (!sawOffHook) return
                val talked = (now - offHookMs) / 1000
                main.removeCallbacks(giveUp)
                val c = contacts.getOrNull(callIndex)?.label
                if (now - offHookMs >= ANSWERED_AFTER_MS) {
                    report?.invoke("call $c: ended after $talked s, counted as answered")
                    unwatchCalls()
                } else {
                    report?.invoke("call $c: ended after $talked s, not answered")
                    unwatchCalls()
                    main.postDelayed({ callNext(context) }, NEXT_CALL_GAP_MS)
                }
            }
        }
    }

    private fun watchCalls(context: Context) {
        unwatchCalls()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                report?.invoke("phone-state permission not granted: cannot tell whether a call is answered")
                return
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { onCallState(context, state) }
                }
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                callback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { onCallState(context, state) }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                callback = l
            }
        } catch (e: Exception) { HLog.d("Crash: could not watch call state: $e") }
    }

    private fun unwatchCalls() {
        val ctx = Engine.appContextOrNull() ?: return
        val cb = callback ?: return
        callback = null
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is android.telephony.PhoneStateListener) @Suppress("DEPRECATION") tm.listen(cb, android.telephony.PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) { HLog.d("Crash: unwatch call state failed $e") }
    }

    private fun speakerOn(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                val spk = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val ok = spk != null && am.setCommunicationDevice(spk)
                HLog.d("Crash: speakerphone ${if (ok) "on" else "not set"}")
            } else {
                @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
                HLog.d("Crash: speakerphone on (legacy)")
            }
        } catch (e: Exception) { HLog.d("Crash: speakerphone failed $e") }
    }

    private fun openDialer(context: Context, number: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            report?.invoke("dialer opened on $number: tap the green button to call emergency services")
        } catch (e: Exception) { report?.invoke("dialer on $number failed ($e)") }
    }

    /** I'M OK: stop the sequence; contacts that were texted get a cancellation text. */
    fun cancel(context: Context) {
        if (!active) return
        val hadSms = smsSent
        cancelInternal(context, "I'M OK")
        if (hadSms && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            val text = "Hush: the owner of phone ${Engine.name} pressed I'M OK, the alarm is cancelled."
            for (c in EmergencyContacts.list(context)) try {
                val sm = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                sm.sendTextMessage(c.number, null, text, null, null)
                HLog.d("Crash: cancellation SMS to ${EmergencyContacts.mask(c.number)}")
            } catch (e: Exception) { HLog.d("Crash: cancellation SMS failed $e") }
        }
    }

    private fun cancelInternal(context: Context, why: String) {
        if (active) HLog.d("Crash: dialer sequence stopped ($why)")
        active = false
        main.removeCallbacks(connectCheck); main.removeCallbacks(giveUp); main.removeCallbacks(repeatSms)
        unwatchCalls()
        smsReceiver?.let { try { context.unregisterReceiver(it) } catch (e: Exception) { HLog.d("Crash: unregister sms receiver failed $e") } }
        smsReceiver = null; smsSent = false; callIndex = -1; report = null; alert = null
    }

    /** The text every contact gets: who, when, where (a map link), battery. */
    fun smsText(context: Context, a: Alert): String {
        val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.atMs))
        val where = if (a.lat != null && a.lon != null) "Position https://maps.google.com/?q=%.5f,%.5f (±%.0f m)".format(a.lat, a.lon, a.gpsAcc ?: 0f) else "No GPS position"
        return "Hush: possible ${a.kind.lowercase()} of the owner of phone ${a.name} at $at, no response for ${CrashGuard.COUNTDOWN_S} s. $where. Battery ${a.battery} %."
    }

    /** For the contacts screen: what the escalation would do right now, without doing it. */
    fun describe(context: Context): String {
        val contacts = EmergencyContacts.list(context)
        val (cell, why) = CrashGuard.cellular(context)
        val a = CrashGuard.buildAlert(Alert.ESCALATED).copy(kind = "TEST", atMs = System.currentTimeMillis())
        val sb = StringBuilder()
        sb.append("Mode: ").append(if (CrashGuard.dryRun(context)) "DRY RUN (nothing is sent)" else "LIVE").append('\n')
        sb.append("Network: ").append(why).append('\n')
        sb.append("Permissions: SMS ").append(granted(context, Manifest.permission.SEND_SMS)).append(", calls ").append(granted(context, Manifest.permission.CALL_PHONE)).append(", call state ").append(granted(context, Manifest.permission.READ_PHONE_STATE)).append('\n')
        if (contacts.isEmpty()) sb.append("No contacts: only the dialer on ").append(EmergencyContacts.emergencyNumber(context)).append('\n')
        for (c in contacts) sb.append("SMS ").append(c.label).append(' ').append(EmergencyContacts.mask(c.number)).append('\n')
        for (c in contacts) sb.append("call ").append(c.label).append(" on speaker, next after 20 s unanswered").append('\n')
        sb.append("then the dialer on ").append(EmergencyContacts.emergencyNumber(context)).append('\n')
        sb.append("mesh: ").append(if (Engine.isRunning) "rescue team told" else "not in a role, nobody told").append('\n')
        sb.append("\nText:\n").append(smsText(context, a))
        if (!cell) sb.append("\n\nTexts and calls will fail: ").append(why)
        return sb.toString()
    }

    private fun granted(context: Context, p: String) = if (ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED) "ok" else "MISSING"
}
