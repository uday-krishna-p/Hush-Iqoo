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
    private val discoveredText: TextView = activity.findViewById(R.id.discoveredText)
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
            val target = Engine.arrowTarget   // loudest phone, with hysteresis
            val angle = target?.takeIf { it != "A" }?.let { Engine.arrowAngleTo(it) }
            val srcAngle = if (fix != null) Engine.arrowAngleToSource() else null
            val own = Engine.ownArrow()
            val shared = if (own == null) Engine.sharedArrow() else null
            val cross = Engine.crossingArrow()
            arrow.twinAngleDeg = own?.twinDeg ?: shared?.twinDeg
            val lines = Engine.bearingLines()
            if (lines != map.bearings) { map.bearings.clear(); map.bearings.putAll(lines); map.invalidate() }
            if (own != null) {
                // The commander's own two mics hear the knocking: first claim on the arrow (no chirps, no map needed).
                arrow.active = true
                arrow.angleDeg = own.screenDeg
                var label = ownArrowLabel(activity, own)
                if (cross != null && angDiff(cross.screenDeg, own.screenDeg) <= 30f) label += activity.getString(R.string.arrow_cross_dist, cross.metres, cross.radius)
                arrow.label = label
            } else if (shared != null) {
                // The sensors hear it and the commander does not: their fused bearing through the commander's compass.
                arrow.active = true
                arrow.angleDeg = shared.screenDeg
                var label = activity.getString(if (shared.twinDeg == null) R.string.arrow_shared else R.string.arrow_shared_unresolved, shared.phones)
                if (cross != null && angDiff(cross.screenDeg, shared.screenDeg) <= 30f) label += activity.getString(R.string.arrow_cross_dist, cross.metres, cross.radius)
                arrow.label = label
            } else if (cross != null) {
                // Two or more phones' arrows cross on the map: point there, with the distance.
                arrow.active = true
                arrow.angleDeg = cross.screenDeg
                arrow.label = activity.getString(R.string.arrow_cross, cross.metres, cross.radius, cross.phones)
            } else if (fix != null && srcAngle != null) {
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
            } else if (target == "A") {
                arrow.active = false; arrow.label = activity.getString(R.string.arrow_here)
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
            if (++ticks % 20 == 0) logArrow(fix, target, angle, srcAngle)
            arrow.postDelayed(this, 50)
        }
    }
    private var ticks = 0

    private fun angDiff(a: Float, b: Float): Float { val d = kotlin.math.abs(((a - b) % 360f + 360f) % 360f); return if (d > 180f) 360f - d else d }

    /**
     * Once a second: what the arrow points at and why, so its direction can be judged from the log (27 Sep):
     * target, map bearing from A (clockwise from map-up), map rotation to north, compass heading, and the
     * resulting angle on screen (bearing + rotation − heading, clockwise from the phone's top).
     */
    private fun logArrow(fix: com.hush.Locator.Fix?, target: String?, angle: Float?, srcAngle: Float?) {
        fun f(v: Float?) = v?.let { "%.0f°".format(it) } ?: "-"
        val rot = Engine.mapRotationDeg
        val line = if (fix != null && srcAngle != null) {
            "ARROW target=SOURCE (%.2f, %.2f) dist=%.2f m mapBearing=%s rotation=%s heading=%.0f° screen=%s | dots from A: %s | north via %s".format(
                fix.x, fix.y, Engine.sourceDistanceMetres() ?: -1f, f(Engine.sourceBearing()), f(rot), Engine.headingDeg, f(srcAngle),
                Engine.mapDots.keys.filter { it != "A" }.sorted().joinToString(" ") { "$it=${f(Engine.mapBearing("A", it))}" }, Engine.alignSource.ifEmpty { "-" })
        } else if (target != null) {
            "ARROW target=Sensor $target dist=%s mapBearing=%s rotation=%s heading=%.0f° screen=%s%s | north via %s".format(
                Engine.mapDistanceMetres("A", target)?.let { "%.2f m".format(it) } ?: "-", f(Engine.mapBearing("A", target)), f(rot), Engine.headingDeg, f(angle),
                if (angle == null) " (no arrow: ${if (rot == null) "map not aligned to north" else "not placed"})" else "", Engine.alignSource.ifEmpty { "-" })
        } else {
            "ARROW no target (no sensor scoring, no located source) rotation=%s heading=%.0f°".format(f(rot), Engine.headingDeg)
        }
        com.hush.HLog.d(line)
    }
    private val placeRow: android.widget.LinearLayout = activity.findViewById(R.id.placeRow)
    private val alignRow: android.widget.LinearLayout = activity.findViewById(R.id.alignRow)

    /** One "Align X" button per sensor on the map: point this phone's top at X, then tap (sets north). */
    private fun renderAlignButtons() {
        alignRow.removeAllViews()
        for (l in peers.map { it.letter }.filter { Engine.mapDots.containsKey(it) }.sorted()) {
            val b = Button(activity)
            b.text = activity.getString(R.string.align_button, l)
            b.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            b.setOnClickListener {
                val msg = Engine.alignByPointing(l)
                android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
                render()
            }
            alignRow.addView(b)
        }
    }

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
        renderAlignButtons()
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
        activity.findViewById<Button>(R.id.btnActivate).setOnClickListener { Engine.activateSensors() }
        onDiscovered(Engine.discoveredPhones())
        activity.findViewById<Button>(R.id.btnAutoPlace).setOnClickListener { Engine.autoPlace() }
        activity.findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stopAll() }
        arrow.post(arrowTick)
        activity.findViewById<Button>(R.id.btnFlip).setOnClickListener {
            Engine.mirror = !Engine.mirror
            val flipped = Engine.mapDots.mapValues { (_, p) -> p.first to (1f - p.second) }
            Engine.mapDots.clear(); Engine.mapDots.putAll(flipped)
            map.dots.clear(); map.dots.putAll(flipped); map.invalidate()
            Engine.applyPointedAlign()
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
        commanderStatus.text = listOf(text, Engine.probeStatus, Engine.rangingStatus, if (Engine.radioStatus.isEmpty()) "" else "Radio: ${Engine.radioStatus}").filter { it.isNotEmpty() }.joinToString("\n")
        // Ranging may have replaced the dots, and the locator may have moved the source.
        map.dots.clear(); map.dots.putAll(Engine.mapDots)
        renderSource()
        map.invalidate()
    }

    private fun renderSource() {
        val fix = Engine.sourceFix
        val cross = if (fix == null) Engine.crossingOnMap() else null   // where the phones' bearing lines meet
        map.source = Engine.sourceOnMap() ?: cross
        map.sourceRadius = if (fix != null && Engine.mapMetresPerUnit != null) (fix.radius / Engine.mapMetresPerUnit!!).toFloat() else if (cross != null) Engine.crossingRadiusOnMap() else 0f
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

    override fun onDiscovered(phones: List<Engine.Discovered>) {
        if (phones.isEmpty()) { discoveredText.text = activity.getString(R.string.discovered_none); return }
        val now = android.os.SystemClock.elapsedRealtime()
        discoveredText.text = phones.joinToString("\n") { p ->
            val age = (now - p.lastSeenMs) / 1000
            buildString {
                append(p.suffix).append("  ").append(p.medianRssi).append(" dBm  ~").append("%.0f".format(p.roughMetres)).append(" m?  ").append(p.trend)
                if (p.awakeByProbe) append("  · woken by probe")
                if (p.battery >= 0) append("  · ").append(p.battery).append(" %")
                append(if (p.letter != null) "  → Sensor ${p.letter}" else "  · not joined yet")
                if (age > 5) append("  (${age} s ago)")
            }
        }
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
