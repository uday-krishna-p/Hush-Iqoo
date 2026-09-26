package com.hush.ui

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.hush.Alerting
import com.hush.Engine
import com.hush.R
import com.hush.audio.SoundAlerts
import com.hush.model.SensorEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Persona C's screen: what was heard last (big, in the category's colour, flashing while fresh), the history,
 * one switch and a TEST button per category, a sensitivity switch, and a one-line view of what the microphone
 * hears now. Pure display; the detector is [Engine.soundAlerts], the effects are [Alerting].
 */
class AlertScreen(private val activity: Activity) : Engine.Listener {

    private val status: TextView = activity.findViewById(R.id.alertStatus)
    private val banner: LinearLayout = activity.findViewById(R.id.alertBanner)
    private val bannerWord: TextView = activity.findViewById(R.id.alertBannerWord)
    private val bannerTime: TextView = activity.findViewById(R.id.alertBannerTime)
    private val dismissBtn: Button = activity.findViewById(R.id.alertDismissBtn)
    private val history: TextView = activity.findViewById(R.id.alertHistory)
    private val categories: LinearLayout = activity.findViewById(R.id.alertCategories)
    private val sensitivity: Switch = activity.findViewById(R.id.alertSensitivity)
    private val hearing: TextView = activity.findViewById(R.id.alertHearing)

    private val idleColour = 0xFF37474F.toInt()
    private val startedMs = SystemClock.elapsedRealtime()
    private var flashColour = idleColour
    private var flashesLeft = 0
    private val flash = object : Runnable {
        override fun run() {
            if (flashesLeft <= 0) { banner.setBackgroundColor(flashColour); return }
            flashesLeft--
            banner.setBackgroundColor(if (flashesLeft % 2 == 0) flashColour else Color.BLACK)
            banner.postDelayed(this, 250L)   // 2 Hz, never faster than 3 Hz
        }
    }

    private val teachButton: Button = activity.findViewById(R.id.teachButton)
    private val teachStatus: TextView = activity.findViewById(R.id.teachStatus)
    private val teachList: LinearLayout = activity.findViewById(R.id.teachList)

    init {
        dismissBtn.setOnClickListener { dismiss() }
        banner.setOnClickListener { if (dismissBtn.visibility == View.VISIBLE) dismiss() }
        sensitivity.isChecked = Engine.soundAlerts.sensitivity < 1f
        sensitivity.setOnCheckedChangeListener { _, on ->
            Engine.soundAlerts.sensitivity = if (on) 0.7f else 1f
            com.hush.HLog.d("ALERT sensitivity ${if (on) "high (x0.7)" else "normal"}")
        }
        buildCategoryRows()
        showHistory()
        status.text = activity.getString(R.string.alert_status_starting)
        teachButton.setOnClickListener { askNameAndTeach() }
        buildTaughtRows()
    }

    // ---- TEACH a sound ----


    private fun askNameAndTeach() {
        if (Engine.library.session != null) { Engine.cancelTeach(); teachStatus.text = ""; teachButton.text = activity.getString(R.string.teach_button); return }
        val input = android.widget.EditText(activity).apply { hint = activity.getString(R.string.teach_name_hint); setSingleLine() }
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.teach_dialog_title)
            .setMessage(R.string.teach_dialog_text)
            .setView(input)
            .setPositiveButton(R.string.teach_start) { _, _ ->
                teachStatus.text = Engine.teach(input.text.toString())
                teachButton.text = activity.getString(R.string.teach_cancel)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildTaughtRows() {
        teachList.removeAllViews()
        for (t in Engine.library.sounds) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 6, 0, 6) }
            row.addView(View(activity).apply { setBackgroundColor(t.colour) }, LinearLayout.LayoutParams(36, 36).apply { rightMargin = 16 })
            row.addView(TextView(activity).apply { text = t.name; textSize = 18f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(activity).apply {
                text = activity.getString(R.string.alert_test); textSize = 14f
                setOnClickListener {
                    val c = SoundAlerts.Category.TAUGHT
                    Alerting.fire(SoundAlerts.Alert(c, t.name.uppercase() + " (test)", "test", 1f, c.pattern, false, SystemClock.elapsedRealtime(), false, t.colour), true)
                    onAlert(SoundAlerts.Alert(c, t.name.uppercase() + " (test)", "test", 1f, c.pattern, false, SystemClock.elapsedRealtime(), false, t.colour))
                }
            })
            row.addView(Button(activity).apply {
                text = activity.getString(R.string.teach_forget); textSize = 14f
                setOnClickListener { Engine.forget(t.name) }
            })
            teachList.addView(row)
        }
    }

    override fun onTeaching(status: String, done: Boolean) {
        teachStatus.text = status
        if (done) { teachButton.text = activity.getString(R.string.teach_button); buildTaughtRows() }
    }

    private fun buildCategoryRows() {
        categories.removeAllViews()
        for (c in SoundAlerts.Category.values()) {
            if (!c.listens) continue
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 6, 0, 6) }
            val swatch = View(activity).apply { setBackgroundColor(c.colour) }
            row.addView(swatch, LinearLayout.LayoutParams(36, 36).apply { rightMargin = 16 })
            val sw = Switch(activity).apply {
                text = c.word
                textSize = 18f
                isChecked = c in Engine.soundAlerts.enabled
                setOnCheckedChangeListener { _, on ->
                    if (on) Engine.soundAlerts.enabled.add(c) else Engine.soundAlerts.enabled.remove(c)
                    com.hush.HLog.d("ALERT category $c ${if (on) "on" else "off"}")
                }
            }
            row.addView(sw, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val test = Button(activity).apply {
                text = activity.getString(R.string.alert_test)
                textSize = 14f
                setOnClickListener { com.hush.HLog.d("ALERT TEST tapped: $c"); com.hush.HLog.d(Engine.alertTest(c.name)) }
            }
            row.addView(test)
            categories.addView(row)
        }
    }

    private fun dismiss() {
        Alerting.dismiss(activity)
        dismissBtn.visibility = View.GONE
        flashesLeft = 0
        flashColour = idleColour
        banner.setBackgroundColor(idleColour)
    }

    private fun showHistory() {
        val h = Alerting.history()
        history.text = if (h.isEmpty()) activity.getString(R.string.alert_history_empty)
                       else h.take(30).joinToString("\n") { Alerting.format(it) }
    }

    private fun alertsToday(): Int {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val midnight = cal.timeInMillis
        return Alerting.history().count { it.atWallMs >= midnight }
    }

    private fun uptime(): String {
        val s = (SystemClock.elapsedRealtime() - startedMs) / 1000
        return if (s < 3600) "%d min".format(s / 60) else "%d h %02d min".format(s / 3600, (s % 3600) / 60)
    }

    override fun onAlert(alert: SoundAlerts.Alert) {
        bannerWord.text = alert.word
        bannerTime.text = activity.getString(R.string.alert_heard_at, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        dismissBtn.visibility = View.VISIBLE
        flashColour = alert.colour
        if (!alert.extended) {
            flashesLeft = 12
            banner.removeCallbacks(flash); banner.post(flash)
        } else banner.setBackgroundColor(flashColour)
        showHistory()
    }

    override fun onOwnWindow(w: Engine.Window) {
        status.text = activity.getString(R.string.alert_status, uptime(), alertsToday())
        val above = if (w.floor > 0f) w.rms / w.floor else 0f
        val top = w.cls?.top5?.take(3)?.joinToString(", ") { (n, s) -> "%s %.2f".format(n, s) } ?: activity.getString(R.string.alert_hearing_quiet)
        hearing.text = activity.getString(R.string.alert_hearing, w.event.label, above, top) +
            (if (w.tap.taps > 0) "\nonsets this second: ${w.tap.taps} (peak ×%.0f)".format(w.tap.peakRatio) else "") +
            (if (w.accel.moving) "\nphone being handled: alerts paused (except ALARM)" else "")
    }

    override fun onLinkStatus(text: String) {
        // Household roles have no link; this carries the microphone warnings ("MICROPHONE SILENCED…").
        if (text.contains("MICROPHONE", ignoreCase = true)) status.text = text
    }
    override fun onCountdown(secondsLeft: Int) {}
    override fun onEvent(event: SensorEvent, peerName: String) {}
    override fun onPeers(peers: List<Engine.Peer>) {}
    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {}
}
