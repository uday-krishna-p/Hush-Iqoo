package com.hush.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hush.Alerting
import com.hush.Engine
import com.hush.R
import com.hush.audio.SoundAlerts
import com.hush.audio.WhistleCounter
import com.hush.model.SensorEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persona B's HOME screen, COUNT part: the pressure-cooker whistle count in the largest type, the target with − and +,
 * the time of every whistle, and the kitchen-timer beeps heard meanwhile. Pure display; the counting is
 * [Engine.whistles], the DONE alarm goes through [Alerting].
 */
class HomeScreen(private val activity: Activity) : Engine.Listener {

    private val status: TextView = activity.findViewById(R.id.homeStatus)
    private val targetText: TextView = activity.findViewById(R.id.homeTarget)
    private val panel: LinearLayout = activity.findViewById(R.id.homePanel)
    private val count: TextView = activity.findViewById(R.id.homeCount)
    private val countWord: TextView = activity.findViewById(R.id.homeCountWord)
    private val times: TextView = activity.findViewById(R.id.homeTimes)
    private val timers: TextView = activity.findViewById(R.id.homeTimers)
    private val hearing: TextView = activity.findViewById(R.id.homeHearing)
    private val idleColour = 0xFF37474F.toInt()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timerLines = ArrayList<String>()
    private var whistleWallMs = ArrayList<Long>()

    init {
        activity.findViewById<Button>(R.id.homeTargetMinus).setOnClickListener { setTarget(Engine.whistles.target - 1) }
        activity.findViewById<Button>(R.id.homeTargetPlus).setOnClickListener { setTarget(Engine.whistles.target + 1) }
        activity.findViewById<Button>(R.id.homeReset).setOnClickListener {
            com.hush.HLog.d("WHISTLE reset by the user (was ${Engine.whistles.count})")
            Engine.resetWhistles(); whistleWallMs.clear(); Alerting.dismiss(activity); refresh()
        }
        targetText.text = Engine.whistles.target.toString()
        status.text = activity.getString(R.string.alert_status_starting)
        refresh()
    }

    private fun setTarget(n: Int) {
        Engine.whistles.target = n.coerceIn(1, 20)
        targetText.text = Engine.whistles.target.toString()
        com.hush.HLog.d("WHISTLE target = ${Engine.whistles.target}")
        refresh()
    }

    private fun refresh() {
        val w = Engine.whistles
        count.text = w.count.toString()
        val done = w.count >= w.target
        panel.setBackgroundColor(if (done) SoundAlerts.Category.COOKER.colour else idleColour)
        countWord.text = when {
            w.count == 0 -> activity.getString(R.string.home_count_idle)
            done -> activity.getString(R.string.home_count_done, w.count)
            else -> activity.getString(R.string.home_count_of, w.count, w.target)
        }
        val now = SystemClock.elapsedRealtime()
        times.text = if (w.whistleTimesMs.isEmpty()) "" else buildString {
            w.whistleTimesMs.forEachIndexed { i, t ->
                val wall = whistleWallMs.getOrNull(i)
                append("whistle ${i + 1}  ").append(if (wall != null) clock.format(Date(wall)) else "").append("  (%d s ago)\n".format((now - t) / 1000))
            }
            append("since the first: %d min %02d s".format((now - w.whistleTimesMs.first()) / 60_000, ((now - w.whistleTimesMs.first()) / 1000) % 60))
        }
    }

    override fun onWhistle(event: WhistleCounter.Event) {
        when (event.kind) {
            WhistleCounter.Kind.WHISTLE, WhistleCounter.Kind.DONE -> whistleWallMs.add(System.currentTimeMillis())
            WhistleCounter.Kind.STALE -> countWord.text = activity.getString(R.string.home_count_stale)
        }
        refresh()
        if (event.kind == WhistleCounter.Kind.STALE) countWord.text = activity.getString(R.string.home_count_stale)
    }

    override fun onAlert(alert: SoundAlerts.Alert) {
        if (alert.extended || alert.category == SoundAlerts.Category.COOKER) return
        timerLines.add(0, "${clock.format(Date())}  ${alert.word}")
        while (timerLines.size > 10) timerLines.removeAt(timerLines.size - 1)
        timers.text = timerLines.joinToString("\n")
    }

    override fun onOwnWindow(w: Engine.Window) {
        status.text = activity.getString(R.string.home_status)
        val above = if (w.floor > 0f) w.rms / w.floor else 0f
        hearing.text = "whistle score %.2f · loud ×%.1f · %s\n%s".format(w.cls?.sum(WhistleCounter.CLASSES) ?: 0f, above,
            Engine.whistles.lastReason.ifEmpty { "quiet" },
            w.cls?.top5?.take(3)?.joinToString(", ") { (n, s) -> "%s %.2f".format(n, s) } ?: "")
        if (Engine.whistles.count > 0) refresh()   // the "s ago" counters
    }

    override fun onLinkStatus(text: String) { if (text.contains("MICROPHONE", ignoreCase = true)) status.text = text }
    override fun onCountdown(secondsLeft: Int) {}
    override fun onEvent(event: SensorEvent, peerName: String) {}
    override fun onPeers(peers: List<Engine.Peer>) {}
    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {}
}
