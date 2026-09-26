package com.hush.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.hush.Engine
import com.hush.R
import com.hush.model.SensorEvent

/**
 * Commander: mode switch, HUSH button, big countdown, the brief, the ranked sensor list,
 * plus the commander's own mic block (it is Sensor A).
 */
class CommanderScreen(activity: Activity) : SensorScreen(activity, activity.getString(R.string.my_mic_a)) {

    private val btnHush: Button = activity.findViewById(R.id.btnHush)
    private val bigCountdown: TextView = activity.findViewById(R.id.bigCountdown)
    private val briefText: TextView = activity.findViewById(R.id.briefText)
    private val peersText: TextView = activity.findViewById(R.id.peersText)
    private val commanderStatus: TextView = activity.findViewById(R.id.commanderStatus)
    private val modeButtons = mapOf(
        Engine.Mode.TAPPING to activity.findViewById<Button>(R.id.btnModeTapping),
        Engine.Mode.VOICE to activity.findViewById<Button>(R.id.btnModeVoice),
        Engine.Mode.ANY to activity.findViewById<Button>(R.id.btnModeAny)
    )

    private var peers: List<Engine.Peer> = emptyList()
    private val latest = LinkedHashMap<String, SensorEvent>()   // letter → newest event
    private var ranks: List<Engine.Rank> = emptyList()
    private val map: MapView = activity.findViewById(R.id.map)
    private val arrow: ArrowView = activity.findViewById(R.id.arrow)
    private val arrowTick = object : Runnable {
        override fun run() {
            val fix = Engine.sourceFix
            val target = ranks.firstOrNull()?.takeIf { it.score > 0f }?.letter
            val angle = target?.let { Engine.arrowAngleTo(it) }
            val srcAngle = if (fix != null) Engine.arrowAngleToSource() else null
            if (fix != null && srcAngle != null) {
                // A located source beats "the nearest sensor": point at the sound itself.
                arrow.active = true
                arrow.angleDeg = srcAngle
                val d = Engine.sourceDistanceMetres() ?: 0f
                val n = if (fix.knocks > 0) fix.knocks else fix.voiceSeconds
                arrow.label = if (fix.edge) activity.getString(R.string.arrow_source_far, fix.nearest.toFloat(), fix.bearingSpreadDeg.toFloat())
                              else activity.getString(R.string.arrow_source, d, fix.radius.toFloat(), n)
            } else if (fix != null && Engine.mapRotationDeg == null) {
                arrow.active = false; arrow.label = activity.getString(R.string.arrow_walk_to_align)
            } else if (target == null) {
                arrow.active = false; arrow.label = activity.getString(R.string.arrow_no_target)
            } else if (angle == null) {
                arrow.active = false
                arrow.label = if (Engine.mapDots.containsKey(target)) activity.getString(R.string.arrow_walk_to_align) else activity.getString(R.string.arrow_place_first, target)
            } else {
                arrow.active = true
                arrow.angleDeg = angle
                val d = Engine.mapDistanceMetres("A", target)
                val base = if (d != null) activity.getString(R.string.arrow_target_dist, target, d) else activity.getString(R.string.arrow_target, target)
                arrow.label = if (Engine.alignSource.isNotEmpty()) "$base · north via ${Engine.alignSource}" else base
            }
            arrow.postDelayed(this, 100)
        }
    }
    private val placeRow: android.widget.LinearLayout = activity.findViewById(R.id.placeRow)

    /** One "place X" button per known letter; tapping it arms the map for that letter. */
    private fun renderPlaceButtons() {
        val letters = (listOf("A") + peers.map { it.letter } + Engine.mapDots.keys).distinct().sorted()
        placeRow.removeAllViews()
        for (l in letters) {
            val b = Button(activity)
            b.text = activity.getString(R.string.place_button, l)
            b.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            b.alpha = if (Engine.mapDots.containsKey(l)) 0.6f else 1f
            b.setOnClickListener { map.placing = l }
            placeRow.addView(b)
        }
    }

    init {
        btnHush.setOnClickListener { Engine.allowSoloHush = false; Engine.hush(20) }
        btnHush.setOnLongClickListener { Engine.allowSoloHush = true; Engine.hush(20); true }
        activity.findViewById<Button>(R.id.btnExport).setOnClickListener {
            val name = Engine.exportLog()
            val msg = if (name != null) activity.getString(R.string.export_ok, name) else activity.getString(R.string.export_failed)
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        modeButtons.forEach { (mode, btn) -> btn.setOnClickListener { Engine.mode = mode; renderMode() } }
        // Dots live in Engine so they survive the screen being recreated.
        map.dots.putAll(Engine.mapDots)
        map.onPlaced = { letter -> Engine.mapDots[letter] = map.dots[letter]!!; renderPlaceButtons() }
        map.strongest = Engine.lastRanking.firstOrNull()?.takeIf { it.score > 0f }?.letter
        renderSource()
        activity.findViewById<Button>(R.id.btnAutoPlace).setOnClickListener { Engine.autoPlace() }
        activity.findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stopAll() }
        arrow.post(arrowTick)
        activity.findViewById<Button>(R.id.btnFlip).setOnClickListener {
            Engine.mirror = !Engine.mirror
            val flipped = Engine.mapDots.mapValues { (_, p) -> p.first to (1f - p.second) }
            Engine.mapDots.clear(); Engine.mapDots.putAll(flipped)
            map.dots.clear(); map.dots.putAll(flipped); map.invalidate()
        }
        renderPlaceButtons()
        renderMode()
        render()
    }

    private fun renderMode() {
        modeButtons.forEach { (mode, btn) -> btn.alpha = if (mode == Engine.mode) 1f else 0.45f }
    }

    override fun onLinkStatus(text: String) {
        super.onLinkStatus(text)
        commanderStatus.text = listOf(text, Engine.rangingStatus, if (Engine.radioStatus.isEmpty()) "" else "Radio: ${Engine.radioStatus}").filter { it.isNotEmpty() }.joinToString("\n")
        // Ranging may have replaced the dots, and the locator may have moved the source.
        map.dots.clear(); map.dots.putAll(Engine.mapDots)
        renderSource()
        map.invalidate()
    }

    private fun renderSource() {
        val fix = Engine.sourceFix
        map.source = Engine.sourceOnMap()
        map.sourceRadius = if (fix != null && Engine.mapMetresPerUnit != null) (fix.radius / Engine.mapMetresPerUnit!!).toFloat() else 0f
        map.sourceFar = fix?.edge == true || (map.source?.let { it.first < 0f || it.first > 1f || it.second < 0f || it.second > 1f } == true)
    }

    override fun onCountdown(secondsLeft: Int) {
        super.onCountdown(secondsLeft)
        if (secondsLeft < 0) {
            bigCountdown.visibility = View.GONE
            btnHush.isEnabled = true
            btnHush.text = activity.getString(R.string.hush_button)
        } else if (secondsLeft == 99) {
            bigCountdown.visibility = View.VISIBLE
            bigCountdown.text = activity.getString(R.string.ranging_now)
            btnHush.isEnabled = false
            btnHush.text = activity.getString(R.string.hush_running)
        } else {
            bigCountdown.visibility = View.VISIBLE
            bigCountdown.text = secondsLeft.toString()
            btnHush.isEnabled = false
            btnHush.text = activity.getString(R.string.hush_running)
            if (secondsLeft >= 19) { ranks = emptyList(); map.strongest = null; briefText.text = activity.getString(R.string.brief_listening) }
        }
    }

    override fun onEvent(event: SensorEvent, peerName: String) {
        latest[event.sensorId] = event
        render()
    }

    override fun onPeers(peers: List<Engine.Peer>) {
        this.peers = peers
        map.dots.clear(); map.dots.putAll(Engine.mapDots); map.invalidate()
        renderPlaceButtons()
        render()
    }

    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {
        this.ranks = ranks
        map.strongest = ranks.firstOrNull()?.takeIf { it.score > 0f }?.letter
        briefText.text = brief
        briefText.setTextColor(if (ranks.firstOrNull()?.let { it.evidence >= 0.9f } == true) 0xFF1B8A3A.toInt() else 0xFF333333.toInt())
        render()
    }

    private fun render() {
        val names = HashMap<String, String>().apply { peers.forEach { put(it.letter, it.name) } }
        val sb = SpannableStringBuilder()
        val strongest = ranks.firstOrNull()?.takeIf { it.score > 0f }?.letter
        val rankByLetter = ranks.associateBy { it.letter }
        val letters = if (ranks.isNotEmpty()) ranks.map { it.letter } + (latest.keys - ranks.map { it.letter }.toSet())
                      else (listOf("A") + peers.map { it.letter } + latest.keys).distinct().sorted()
        for (l in letters) {
            val e = latest[l]
            val r = rankByLetter[l]
            val name = if (l == "A") "${activity.getString(R.string.this_phone)} ${Engine.name.takeLast(4)}" else names[l]?.takeLast(4) ?: activity.getString(R.string.offline)
            val start = sb.length
            sb.append(if (l == strongest) "★ " else "   ").append(l).append("  ").append(name)
            if (r != null) sb.append("   score %.4f  evidence %.0f%%".format(r.score, r.evidence * 100))
            if (r != null && r.source > 0) sb.append("  source ${r.source}")
            sb.append('\n')
            if (l == strongest) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.25f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFF1B8A3A.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (e != null) {
                val rh = if (e.rhythm != null) " · ${e.rhythm}" else ""
                val bat = if (e.battery >= 0) "  ${e.battery}%" else ""
                val mov = if (e.moving) "  ⚠moving" else ""
                sb.append("      ${e.label}$rh$mov$bat\n")
                sb.append("      rms %.4f  taps %d  rhythm %.1f  voice %.2f  machine %.2f\n".format(e.rms, e.taps, e.rhythmScore, e.human, e.machine))
            } else {
                sb.append("      ").append(activity.getString(R.string.no_data_yet)).append('\n')
            }
        }
        peersText.text = sb
    }
}
