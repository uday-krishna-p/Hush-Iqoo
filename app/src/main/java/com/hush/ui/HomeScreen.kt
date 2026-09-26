package com.hush.ui

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hush.Alerting
import com.hush.Engine
import com.hush.R
import com.hush.WalkLocator
import com.hush.audio.SoundAlerts
import com.hush.audio.WhistleCounter
import com.hush.model.SensorEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Persona B's HOME screen. Two tabs. FIND: this phone's own arrow at a repeating noise, the prompt (stand still, walk
 * to the side, turn around), warmer/colder, and a small map of the walk with the marks, their bearing lines and the
 * cross-hair where they cross ([Engine.walk]). COUNT: the pressure-cooker whistle count in the largest type, the
 * target with − and +, the time of every whistle, the kitchen-timer beeps heard meanwhile ([Engine.whistles]).
 * Pure display; the DONE alarm and the timer alerts go through [Alerting].
 */
class HomeScreen(private val activity: Activity) : Engine.Listener {

    // tabs
    private val tabFind: Button = activity.findViewById(R.id.homeTabFind)
    private val tabCount: Button = activity.findViewById(R.id.homeTabCount)
    private val findPane: View = activity.findViewById(R.id.homeFind)
    private val countPane: View = activity.findViewById(R.id.homeCountPane)
    private val status: TextView = activity.findViewById(R.id.homeStatus)
    private val hearing: TextView = activity.findViewById(R.id.homeHearing)
    private var findShowing = true

    // FIND
    private val arrow: ArrowView = activity.findViewById(R.id.findArrow)
    private val prompt: TextView = activity.findViewById(R.id.findPrompt)
    private val warmer: TextView = activity.findViewById(R.id.findWarmer)
    private val map: MapView = activity.findViewById(R.id.findMap)
    private val marksText: TextView = activity.findViewById(R.id.findMarks)
    private var arrowTicks = 0
    private val arrowTick = object : Runnable {
        override fun run() {
            if (Engine.role != Engine.ROLE_HOME) return
            if (findShowing) updateFindArrow()
            arrow.postDelayed(this, 50)
        }
    }

    // COUNT
    private val targetText: TextView = activity.findViewById(R.id.homeTarget)
    private val panel: LinearLayout = activity.findViewById(R.id.homePanel)
    private val count: TextView = activity.findViewById(R.id.homeCount)
    private val countWord: TextView = activity.findViewById(R.id.homeCountWord)
    private val times: TextView = activity.findViewById(R.id.homeTimes)
    private val timers: TextView = activity.findViewById(R.id.homeTimers)
    private val idleColour = 0xFF37474F.toInt()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timerLines = ArrayList<String>()
    private var whistleWallMs = ArrayList<Long>()

    init {
        tabFind.setOnClickListener { showTab(find = true) }
        tabCount.setOnClickListener { showTab(find = false) }
        activity.findViewById<Button>(R.id.findMark).setOnClickListener { prompt.text = Engine.walkMark(); refreshFind() }
        activity.findViewById<Button>(R.id.findReset).setOnClickListener { Engine.walkReset(); refreshFind() }
        activity.findViewById<Button>(R.id.homeTargetMinus).setOnClickListener { setTarget(Engine.whistles.target - 1) }
        activity.findViewById<Button>(R.id.homeTargetPlus).setOnClickListener { setTarget(Engine.whistles.target + 1) }
        activity.findViewById<Button>(R.id.homeReset).setOnClickListener {
            com.hush.HLog.d("WHISTLE reset by the user (was ${Engine.whistles.count})")
            Engine.resetWhistles(); whistleWallMs.clear(); Alerting.dismiss(activity); refreshCount()
        }
        targetText.text = Engine.whistles.target.toString()
        status.text = activity.getString(R.string.alert_status_starting)
        showTab(find = true)
        refreshCount()
        arrow.post(arrowTick)
    }

    private fun showTab(find: Boolean) {
        findShowing = find
        findPane.visibility = if (find) View.VISIBLE else View.GONE
        countPane.visibility = if (find) View.GONE else View.VISIBLE
        tabFind.alpha = if (find) 1f else 0.5f
        tabCount.alpha = if (find) 0.5f else 1f
        com.hush.HLog.d("HOME tab: ${if (find) "FIND" else "COUNT"}")
        if (find) refreshFind() else refreshCount()
    }

    // ---------------- FIND ----------------

    /** 20×/s: the arrow follows the compass; the rest of the FIND pane refreshes once a second. */
    private fun updateFindArrow() {
        val s = Engine.walkState
        val heading = Engine.headingDeg
        val fix = s.fix
        if (fix != null && s.bearingToFixDeg != null) {
            arrow.active = true
            arrow.angleDeg = ((s.bearingToFixDeg - heading + 720.0) % 360.0).toFloat()
            arrow.twinAngleDeg = null
            arrow.label = activity.getString(R.string.home_find_fix, s.distanceToFixM ?: 0.0, fix.radius, fix.marks)
            return
        }
        val own = Engine.homeArrow()
        if (own != null) {
            arrow.active = true
            arrow.angleDeg = own.screenDeg
            arrow.twinAngleDeg = own.twinDeg
            arrow.label = activity.getString(if (own.twinDeg == null) R.string.home_find_own else R.string.home_find_own_twin, own.knocks)
        } else {
            arrow.active = false
            arrow.twinAngleDeg = null
            arrow.label = activity.getString(R.string.home_find_no_arrow)
        }
    }

    private fun refreshFind() {
        val s = Engine.walkState
        prompt.text = when (s.prompt) {
            WalkLocator.Prompt.LISTENING -> activity.getString(R.string.home_prompt_listening)
            WalkLocator.Prompt.STAND_STILL -> activity.getString(R.string.home_prompt_stand_still)
            WalkLocator.Prompt.HOLD_ON -> activity.getString(R.string.home_prompt_hold_on, s.knocksHere, s.stillS)
            WalkLocator.Prompt.WALK_SIDEWAYS -> activity.getString(R.string.home_prompt_walk)
            WalkLocator.Prompt.TURN_OR_THIRD -> activity.getString(R.string.home_prompt_turn_or_third)
            WalkLocator.Prompt.FIX -> activity.getString(R.string.home_prompt_fix, s.distanceToFixM ?: 0.0)
        }
        val w = s.warmerDb
        warmer.text = when {
            w == null -> ""
            w > 2f -> activity.getString(R.string.home_warmer, w)
            w < -2f -> activity.getString(R.string.home_colder, -w)
            else -> activity.getString(R.string.home_same)
        }
        drawMap(s)
        marksText.text = s.marks.joinToString("\n") { m ->
            "mark %d  (%.1f, %.1f) m  →%.0f°%s  %.0f dB  %d knocks".format(m.n, m.x, m.y, m.bearingDeg, m.twinDeg?.let { "/%.0f°".format(it) } ?: "", m.levelDb, m.knocks)
        } + (s.fix?.let { "\nnoise at (%.1f, %.1f) m ±%.1f%s".format(it.x, it.y, it.radius, if (it.tieBrokenByLoudness) " (left/right settled by loudness)" else "") } ?: "")
    }

    /** The walk on the square map: north up, the walker as A, marks as 1 2 3 with their bearing lines, the fix as the red cross-hair. */
    private fun drawMap(s: WalkLocator.State) {
        val here = Engine.walkerPosition()
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        xs.add(here.first); ys.add(here.second)
        for (m in s.marks) { xs.add(m.x); ys.add(m.y) }
        s.fix?.let { xs.add(it.x); ys.add(it.y) }
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        val span = max(max(xs.max() - xs.min(), ys.max() - ys.min()) * 1.4, 4.0)   // at least a 4 m square
        fun toMap(x: Double, y: Double) = ((0.5 + (x - cx) / span).toFloat()) to ((0.5 - (y - cy) / span).toFloat())
        map.dots.clear(); map.bearings.clear()
        for (m in s.marks) {
            map.dots["${m.n}"] = toMap(m.x, m.y)
            map.bearings["${m.n}"] = m.bearingDeg.toFloat() to m.twinDeg?.toFloat()
        }
        map.dots["A"] = toMap(here.first, here.second)
        map.source = s.fix?.let { toMap(it.x, it.y) }
        map.sourceRadius = s.fix?.let { (it.radius / span).toFloat() } ?: 0f
        map.sourceFar = false
        map.strongest = null
        map.invalidate()
    }

    // ---------------- COUNT ----------------

    private fun setTarget(n: Int) {
        Engine.whistles.target = n.coerceIn(1, 20)
        targetText.text = Engine.whistles.target.toString()
        com.hush.HLog.d("WHISTLE target = ${Engine.whistles.target}")
        refreshCount()
    }

    private fun refreshCount() {
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
        if (event.kind != WhistleCounter.Kind.STALE) whistleWallMs.add(System.currentTimeMillis())
        refreshCount()
        if (event.kind == WhistleCounter.Kind.STALE) countWord.text = activity.getString(R.string.home_count_stale)
    }

    override fun onAlert(alert: SoundAlerts.Alert) {
        if (alert.extended || alert.category == SoundAlerts.Category.COOKER) return
        timerLines.add(0, "${clock.format(Date())}  ${alert.word}")
        while (timerLines.size > 10) timerLines.removeAt(timerLines.size - 1)
        timers.text = timerLines.joinToString("\n")
    }

    override fun onOwnWindow(w: Engine.Window) {
        status.text = activity.getString(if (findShowing) R.string.home_find_status else R.string.home_status)
        val above = if (w.floor > 0f) w.rms / w.floor else 0f
        hearing.text = if (findShowing) {
            "loud ×%.1f above the room · onsets this second %d (peak ×%.0f)%s\n%s".format(above, w.tap.taps, w.tap.peakRatio,
                if (w.accel.moving) " · walking" else " · still", w.cls?.top5?.take(3)?.joinToString(", ") { (n, s) -> "%s %.2f".format(n, s) } ?: "")
        } else {
            "whistle score %.2f · loud ×%.1f · %s\n%s".format(w.cls?.sum(WhistleCounter.CLASSES) ?: 0f, above,
                Engine.whistles.lastReason.ifEmpty { "quiet" }, w.cls?.top5?.take(3)?.joinToString(", ") { (n, s) -> "%s %.2f".format(n, s) } ?: "")
        }
        if (findShowing) refreshFind() else if (Engine.whistles.count > 0) refreshCount()
    }

    override fun onLinkStatus(text: String) { if (text.contains("MICROPHONE", ignoreCase = true)) status.text = text }
    override fun onCountdown(secondsLeft: Int) {}
    override fun onEvent(event: SensorEvent, peerName: String) {}
    override fun onPeers(peers: List<Engine.Peer>) {}
    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {}
}
