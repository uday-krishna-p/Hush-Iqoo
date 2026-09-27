package com.hush.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import com.hush.Alerting
import com.hush.Engine
import com.hush.R
import com.hush.Speak
import com.hush.audio.SoundAlerts
import com.hush.model.SensorEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10

/**
 * Persona C's screen: what was heard last (big, in the category's colour, flashing while fresh); a live view of
 * what the phone hears now (level meter, sound names, recent sounds); live captions; type to speak; the history;
 * one switch and a TEST button per category; TEACH rows; sensitivity, torch and night switches.
 * Pure display; the detector is [Engine.soundAlerts], the effects are [Alerting].
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
    private val torch: Switch = activity.findViewById(R.id.alertTorch)
    private val night: Switch = activity.findViewById(R.id.alertNight)
    private val hearing: TextView = activity.findViewById(R.id.alertHearing)
    // hearing now
    private val level: ProgressBar = activity.findViewById(R.id.hearLevel)
    private val levelText: TextView = activity.findViewById(R.id.hearLevelText)
    private val hearNow: TextView = activity.findViewById(R.id.hearNow)
    private val hearRecent: TextView = activity.findViewById(R.id.hearRecent)
    // captions
    private val captionsButton: Button = activity.findViewById(R.id.captionsButton)
    private val captionsName: EditText = activity.findViewById(R.id.captionsName)
    private val captionsStatus: TextView = activity.findViewById(R.id.captionsStatus)
    private val captionsText: TextView = activity.findViewById(R.id.captionsText)
    // speak
    private val speakText: EditText = activity.findViewById(R.id.speakText)
    private val speakButton: Button = activity.findViewById(R.id.speakButton)
    private val speakPhrases: LinearLayout = activity.findViewById(R.id.speakPhrases)
    // teach
    private val teachButton: Button = activity.findViewById(R.id.teachButton)
    private val teachStatus: TextView = activity.findViewById(R.id.teachStatus)
    private val teachList: LinearLayout = activity.findViewById(R.id.teachList)

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

    init {
        dismissBtn.setOnClickListener { dismiss() }
        banner.setOnClickListener { if (dismissBtn.visibility == View.VISIBLE) dismiss() }
        sensitivity.isChecked = Engine.soundAlerts.sensitivity < 1f
        sensitivity.setOnCheckedChangeListener { _, on ->
            Engine.soundAlerts.sensitivity = if (on) 0.7f else 1f
            com.hush.HLog.d("ALERT sensitivity ${if (on) "high (x0.7)" else "normal"}")
        }
        torch.isChecked = Alerting.torch
        torch.setOnCheckedChangeListener { _, on -> Alerting.torch = on; com.hush.HLog.d("ALERT torch flash ${if (on) "on" else "off"}") }
        night.isChecked = Alerting.night
        night.setOnCheckedChangeListener { _, on -> Alerting.night = on; com.hush.HLog.d("ALERT night mode ${if (on) "on" else "off"}") }
        activity.findViewById<Button>(R.id.alertFlashSettings).setOnClickListener {
            try { activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { com.hush.HLog.d("ALERT: accessibility settings failed $e") }
        }
        buildCategoryRows()
        showHistory()
        status.text = activity.getString(R.string.alert_status_starting)
        teachButton.setOnClickListener { askNameAndTeach() }
        buildTaughtRows()
        captionsButton.setOnClickListener { toggleCaptions() }
        showCaptionsButton()
        speakButton.setOnClickListener { speakTyped() }
        speakText.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { speakTyped(); true } else false }
        buildPhrases()
        Speak.init(activity)
    }

    // ---- hearing now ----

    /** Classes that only describe the room, never worth naming. */
    private val ambient = setOf("Silence", "Inside, small room", "Inside, large room or hall", "Inside, public space", "Outside, rural or natural",
        "Outside, urban or manmade", "White noise", "Pink noise", "Static", "Noise", "Environmental noise", "Sound effect", "Field recording",
        "Speech synthesizer", "Vibration", "Hum", "Mains hum", "Echo", "Reverberation", "Mechanisms")
    private val recent = ArrayList<String>()
    private var lastNamed = ""

    private fun updateHearing(w: Engine.Window) {
        val above = if (w.floor > 0f) w.rms / w.floor else 0f
        val db = if (above > 1f) (20.0 * log10(above.toDouble())).toInt() else 0
        level.progress = db.coerceIn(0, 40)
        levelText.text = when {
            db >= 24 -> activity.getString(R.string.hear_very_loud, db)
            db >= 14 -> activity.getString(R.string.hear_loud, db)
            db >= 6 -> activity.getString(R.string.hear_some, db)
            else -> activity.getString(R.string.hear_quiet)
        }
        val names = w.cls?.top5?.filter { (n, s) -> s >= 0.12f && n !in ambient }?.take(3)?.map { it.first } ?: emptyList()
        hearNow.text = if (names.isEmpty() || above < 1.5f) activity.getString(R.string.hear_nothing) else names.joinToString(" · ")
        val strongest = w.cls?.top5?.firstOrNull { (n, s) -> s >= 0.3f && n !in ambient }?.first
        if (strongest != null && above >= 2f && strongest != lastNamed) {
            lastNamed = strongest
            recent.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + strongest)
            while (recent.size > 8) recent.removeAt(recent.size - 1)
            hearRecent.text = recent.joinToString("\n")
        }
    }

    // ---- captions ----

    private val captionLines = ArrayList<String>()
    private var partial = ""

    private fun toggleCaptions() {
        if (Engine.captionsOn) Engine.setCaptions(false) else { captionLines.clear(); partial = ""; captionsText.text = ""; Engine.setCaptions(true) }
        showCaptionsButton()
    }

    private fun showCaptionsButton() {
        captionsButton.text = activity.getString(if (Engine.captionsOn) R.string.captions_stop else R.string.captions_start)
    }

    override fun onCaption(text: String, final: Boolean) {
        if (final) { captionLines.add(0, text); while (captionLines.size > 6) captionLines.removeAt(captionLines.size - 1); partial = "" } else partial = text
        val name = captionsName.text.toString().trim()
        val all = (if (partial.isNotEmpty()) "… $partial\n" else "") + captionLines.joinToString("\n")
        captionsText.text = all
        if (name.isNotEmpty() && text.contains(name, ignoreCase = true) && final) {
            // Somebody said the person's name: make it as visible as an alert.
            val c = SoundAlerts.Category.SPEECH
            onAlert(SoundAlerts.Alert(c, activity.getString(R.string.captions_name_called, name), text, 1f, c.pattern, false, SystemClock.elapsedRealtime(), false))
            com.hush.audio.Haptics.vibrate(activity, c.pattern, "Name called")
            com.hush.HLog.d("CAPTIONS: name '$name' heard in '$text'")
        }
    }

    override fun onCaptionStatus(status: String) { captionsStatus.text = status; showCaptionsButton() }

    // ---- type to speak ----

    private fun speakTyped() {
        val t = speakText.text.toString()
        if (t.isBlank()) return
        Speak.say(activity, t)
        speakText.setText("")
    }

    private fun buildPhrases() {
        val phrases = activity.resources.getStringArray(R.array.speak_phrases)
        var row: LinearLayout? = null
        phrases.forEachIndexed { i, p ->
            if (i % 2 == 0) { row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }; speakPhrases.addView(row) }
            row!!.addView(Button(activity).apply { text = p; textSize = 14f; isAllCaps = false; setOnClickListener { Speak.say(activity, p) } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---- TEACH a sound ----

    private fun askNameAndTeach() {
        if (Engine.library.session != null) { Engine.cancelTeach(); teachStatus.text = ""; teachButton.text = activity.getString(R.string.teach_button); return }
        val input = EditText(activity).apply { hint = activity.getString(R.string.teach_name_hint); setSingleLine() }
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
                    val a = SoundAlerts.Alert(c, t.name.uppercase() + " (test)", "test", 1f, c.pattern, false, SystemClock.elapsedRealtime(), false, t.colour)
                    Alerting.fire(a, true); onAlert(a)
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

    // ---- categories ----

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

    /** Alerts worth listing: KNOCK entries from older builds (table clatter, the phone's own motor) stay in the file but are not shown. */
    private fun shownHistory() = Alerting.history().filter { it.category != SoundAlerts.Category.KNOCK.name }

    private fun showHistory() {
        val h = shownHistory()
        history.text = if (h.isEmpty()) activity.getString(R.string.alert_history_empty)
                       else h.take(30).joinToString("\n") { Alerting.format(it) }
    }

    private fun alertsToday(): Int {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val midnight = cal.timeInMillis
        return shownHistory().count { it.atWallMs >= midnight }
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
        status.text = activity.getString(R.string.alert_status, uptime(), alertsToday()) + (if (Engine.captionsOn) activity.getString(R.string.alert_status_captions) else "")
        updateHearing(w)
        val above = if (w.floor > 0f) w.rms / w.floor else 0f
        val top = w.cls?.top5?.take(3)?.joinToString(", ") { (n, s) -> "%s %.2f".format(n, s) } ?: activity.getString(R.string.alert_hearing_quiet)
        // No rescue label (HUMAN TAPPING…) and no knock onsets here: the alerts do not use the knock detector.
        hearing.text = activity.getString(R.string.alert_hearing, above, top) +
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
