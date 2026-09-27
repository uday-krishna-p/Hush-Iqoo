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
 * The one screen every phone shows (27 Sep, team: "they all need to have the same UI"): mode switch, HUSH button,
 * big countdown, the brief, the arrow, the map, the ranked phone list, plus this phone's own mic block.
 * On the commander everything is computed here; on a sensor the list, brief and status come from the commander's
 * once-a-second Board, the map from its Fix, the arrow from [SensorScreen]'s logic, and the buttons ask the commander.
 * Map editing (Place, Align, Auto-place, Flip) stays on the commander, which owns the map.
 */
class CommanderScreen(activity: Activity, role: String) : SensorScreen(activity, activity.getString(R.string.my_mic_a), role) {

    companion object {
        /** Whether "Technical details" is unfolded; kept while the app runs so a screen rebuild does not fold it again. */
        private var detailsOpen = false
    }

    // The role the phone was started in, not Engine.role: the service sets that a few ms after this screen is built.
    private val isCommander = role == Engine.ROLE_COMMANDER
    private val screenTitle: TextView = activity.findViewById(R.id.screenTitle)
    private val compassText: TextView = activity.findViewById(R.id.compassText)
    private val compassTick = object : Runnable {
        override fun run() { compassText.text = Engine.compassText(); compassText.postDelayed(this, 500) }
    }

    private val btnHush: Button = activity.findViewById(R.id.btnHush)
    private val bigCountdown: TextView = activity.findViewById(R.id.bigCountdown)
    private val briefText: TextView = activity.findViewById(R.id.briefText)
    private val closestText: TextView = activity.findViewById(R.id.closestText)
    private val warmthText: TextView = activity.findViewById(R.id.warmthText)
    private val btnSensitivity: androidx.appcompat.widget.SwitchCompat = activity.findViewById(R.id.btnSensitivity)
    private var settingSwitch = false
    private val connectionText: TextView = activity.findViewById(R.id.connectionText)
    private val phoneChip: TextView = activity.findViewById(R.id.phoneChip)
    private val closestCard: View = activity.findViewById(R.id.closestCard)
    private val closestOverline: TextView = activity.findViewById(R.id.closestOverline)
    private val closestTitle: TextView = activity.findViewById(R.id.closestTitle)
    private val detailsToggle: TextView = activity.findViewById(R.id.detailsToggle)
    private val detailsBody: View = activity.findViewById(R.id.detailsBody)
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
            if (drawKnockArrow(arrow)) { arrow.postDelayed(this, 50); return }
            val target = Engine.arrowTarget   // loudest phone, with hysteresis
            val angle = target?.takeIf { it != "A" }?.let { Engine.arrowAngleTo(it) }
            val srcAngle = if (fix != null) Engine.arrowAngleToSource() else null
            val own = Engine.ownArrow()
            val sharedRaw = Engine.sharedArrow()
            val shared = if (Engine.preferShared(sharedRaw, own)) sharedRaw else null
            val cross = Engine.crossingArrow()
            arrow.twinAngleDeg = null   // one arrow only (team, 27 Sep 03:30)
            arrow.confidence = shared?.confidence ?: own?.confidence ?: 1f
            val lines = Engine.bearingLines()
            if (lines != map.bearings) { map.bearings.clear(); map.bearings.putAll(lines); map.invalidate() }
            val loc = Engine.localSourceArrow()
            if (loc?.screenDeg != null) {
                // The timing locator (every phone's knock timings, clocks from the inaudible chirps) beats any bearing.
                arrow.active = true
                arrow.angleDeg = loc.screenDeg
                arrow.label = activity.getString(R.string.arrow_located, loc.metres, loc.radius, loc.knocks)
            } else if (loc != null && own == null) {
                arrow.active = false
                arrow.label = activity.getString(R.string.arrow_located_no_north, loc.metres, loc.radius)
            } else if (own != null && Engine.ownHeardWell(own)) {
                // The commander hears it: its own mics point from where it lies; the others settled left/right.
                arrow.active = true
                arrow.angleDeg = own.screenDeg
                var label = activity.getString(R.string.arrow_heard_here, own.knocks, own.resolvedBy ?: "turning")
                if (cross != null && angDiff(cross.screenDeg, own.screenDeg) <= 30f) label += activity.getString(R.string.arrow_cross_dist, cross.metres, cross.radius)
                arrow.label = label
            } else if (cross != null) {
                arrow.active = true
                arrow.angleDeg = cross.screenDeg
                arrow.label = activity.getString(R.string.arrow_cross, cross.metres, cross.radius, cross.phones)
            } else if (shared != null) {
                // The network's estimate first (every phone's mics fused, mirrors resolved across phones); the crossing
                // of the bearing lines adds a distance when the map has positions.
                arrow.active = true
                arrow.angleDeg = shared.screenDeg
                var label = activity.getString(if (shared.twinDeg == null) R.string.arrow_shared else R.string.arrow_shared_unresolved, shared.phones, (shared.confidence * 100).toInt())
                if (shared.others) label += activity.getString(R.string.arrow_parallel)
                if (cross != null && angDiff(cross.screenDeg, shared.screenDeg) <= 30f) label += activity.getString(R.string.arrow_cross_dist, cross.metres, cross.radius)
                arrow.label = label
            } else if (own != null) {
                // No fusion yet: the commander's own two mics.
                arrow.active = true
                arrow.angleDeg = own.screenDeg
                var label = ownArrowLabel(activity, own)
                if (cross != null && angDiff(cross.screenDeg, own.screenDeg) <= 30f) label += activity.getString(R.string.arrow_cross_dist, cross.metres, cross.radius)
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
        if (!isCommander) return
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
        if (!isCommander) { renderAlignButtons(); return }
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
        modeButtons.forEach { (mode, btn) -> btn.setOnClickListener { Engine.chooseMode(mode); renderMode() } }
        btnSensitivity.setOnCheckedChangeListener { _, on -> if (!settingSwitch && on != Engine.sensitiveHigh) { Engine.setSensitivity(on); renderClosest() } }
        detailsToggle.setOnClickListener { detailsOpen = !detailsOpen; renderDetails() }
        renderDetails()
        val sync = activity.findViewById<Button>(R.id.btnSyncCompass)
        sync.setOnClickListener { android.widget.Toast.makeText(activity, Engine.syncCompasses(), android.widget.Toast.LENGTH_LONG).show() }
        // POINT & TAP buttons (one per other phone) and Clear are drawn and wired by SensorScreen.
        sync.setOnLongClickListener { android.widget.Toast.makeText(activity, Engine.clearCompassSync(), android.widget.Toast.LENGTH_LONG).show(); true }
        compassText.post(compassTick)
        // Dots live in Engine so they survive the screen being recreated (a sensor gets them from the commander's Fix).
        map.dots.putAll(Engine.screenDots())
        map.onPlaced = { letter -> Engine.mapDots[letter] = map.dots[letter]!!; renderPlaceButtons() }
        map.strongest = mapLeader(Engine.lastRanking)
        renderSource()
        activity.findViewById<Button>(R.id.btnActivate).setOnClickListener { Engine.activateSensors() }
        onDiscovered(Engine.discoveredPhones())
        activity.findViewById<Button>(R.id.btnAutoPlace).setOnClickListener { Engine.autoPlace() }
        activity.findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stopAll() }
        // A sensor's arrow is drawn by SensorScreen (fused bearing first, then its own two mics).
        if (isCommander) arrow.post(arrowTick)
        if (!isCommander) {
            activity.findViewById<Button>(R.id.btnAutoPlace).apply { isEnabled = false; text = activity.getString(R.string.map_edit_on_commander) }
            activity.findViewById<Button>(R.id.btnFlip).visibility = View.GONE
        }
        activity.findViewById<Button>(R.id.btnFlip).setOnClickListener {
            Engine.mirror = !Engine.mirror
            val flipped = Engine.mapDots.mapValues { (_, p) -> p.first to (1f - p.second) }
            Engine.mapDots.clear(); Engine.mapDots.putAll(flipped)
            map.dots.clear(); map.dots.putAll(flipped); map.invalidate()
            Engine.applyPointedAlign()
        }
        renderPlaceButtons()
        renderMode()
        renderClosest()
        render()
    }

    private fun renderDetails() {
        detailsBody.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        detailsToggle.text = activity.getString(if (detailsOpen) R.string.details_hide else R.string.details_show)
    }

    private fun renderMode() {
        modeButtons.forEach { (mode, btn) -> btn.alpha = if (mode == Engine.mode) 1f else 0.45f }
    }

    override fun onLinkStatus(text: String) {
        super.onLinkStatus(text)
        screenTitle.text = if (isCommander) activity.getString(R.string.role_commander_title) else activity.getString(R.string.sensor_title, Engine.letter)
        val me = if (isCommander) "A" else Engine.letter
        phoneChip.text = activity.getString(R.string.phone_chip, if (me == "?") Engine.name.takeLast(4) else "$me · ${Engine.name.takeLast(4)}")
        renderConnection(text)
        commanderStatus.text = (if (isCommander) listOf(text, Engine.probeStatus, Engine.rangingStatus, if (Engine.radioStatus.isEmpty()) "" else "Radio: ${Engine.radioStatus}")
                                else listOf(text, Engine.probeStatus, Engine.boardStatus)).filter { it.isNotEmpty() }.joinToString("\n")
        // Ranging may have replaced the dots, and the locator may have moved the source.
        map.dots.clear(); map.dots.putAll(Engine.screenDots())
        renderSource()
        map.invalidate()
    }

    /** The one plain line under the title: how many sensors (commander) or whether the commander is reached (sensor). */
    private fun renderConnection(link: String = "") {
        val ok: Boolean
        connectionText.text = if (isCommander) {
            ok = peers.isNotEmpty()
            if (ok) activity.getString(R.string.conn_commander, peers.size) else activity.getString(R.string.conn_commander_none)
        } else {
            ok = Engine.letter != "?" && !link.startsWith("Searching") && !link.contains("not connected")
            activity.getString(if (ok) R.string.conn_sensor_ok else R.string.conn_sensor_searching)
        }
        connectionText.setTextColor(activity.getColor(if (ok) R.color.good else R.color.warn))
    }

    private fun renderSource() {
        // The loudness point (LoudnessLocator) first: the one that works on the phones (27 Sep 06:30).
        Engine.whereOnMap()?.let { (xy, r) -> map.source = xy; map.sourceRadius = r; map.sourceFar = false; return }
        if (!isCommander) {
            // The commander's located source or crossing, as carried by its Fix.
            map.source = Engine.screenSource()
            val f = Engine.receivedFix
            map.sourceRadius = if (map.source != null && f?.scale != null && f.scale > 0f) f.radius / f.scale else 0f
            map.sourceFar = f?.edge == true
            return
        }
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
        val text = if (isCommander) Engine.discoveredText(phones) else Engine.boardDiscovered
        discoveredText.text = text.ifEmpty { activity.getString(R.string.discovered_none) }
        discoveredText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onPeers(peers: List<Engine.Peer>) {
        this.peers = peers
        if (isCommander) renderConnection()
        map.dots.clear(); map.dots.putAll(Engine.screenDots()); map.invalidate()
        renderPlaceButtons()
        render()
    }

    /** This phone's WARMER / COLDER (Warmth.kt): the metal-detector line for a phone being carried. */
    private fun renderWarmth() {
        val st = Engine.warmthByLetter[Engine.letter]
        if (st == null) { warmthText.visibility = View.GONE; return }
        warmthText.visibility = View.VISIBLE
        val (title, hint, bg, fg) = when (st.trend) {
            com.hush.Warmth.Trend.WARMER -> listOf(R.string.warm_title, R.string.warm_hint, R.color.warm_soft, R.color.warm)
            com.hush.Warmth.Trend.COLDER -> listOf(R.string.cold_title, R.string.cold_hint, R.color.cold_soft, R.color.cold)
            com.hush.Warmth.Trend.SAME -> listOf(R.string.same_title, R.string.same_hint, R.color.surface_muted, R.color.text)
        }
        val sign = when (st.trend) { com.hush.Warmth.Trend.WARMER -> "▲  "; com.hush.Warmth.Trend.COLDER -> "▼  "; else -> "" }
        val head = sign + activity.getString(title) + "  %+.0f dB".format(st.deltaDb)
        val sb = SpannableStringBuilder(head).append("\n").append(activity.getString(hint))
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.5f), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        warmthText.text = sb
        warmthText.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(bg))
        warmthText.setTextColor(activity.getColor(fg))
    }

    /**
     * The map's green dot (and the arrow to it): the closest phone judged knock by knock (Closest.kt, same as the card),
     * else the live ranking. The ranking alone decays over 15 s, so the map lagged the card by seconds (27 Sep, team).
     */
    private fun mapLeader(ranks: List<Engine.Rank>): String? {
        val t = Engine.closestText
        if (t.startsWith("CLOSEST") || t.startsWith("LEANING")) Regex("(?:Sensor|Commander) ([A-Z])").find(t.lineSequence().first())?.let { return it.groupValues[1] }
        return ranks.firstOrNull()?.takeIf { it.score > 0f }?.letter
    }

    override fun onClosest(text: String) {
        map.strongest = mapLeader(ranks)
        renderClosest()
        map.dots.clear(); map.dots.putAll(Engine.screenDots())
        renderSource()
        map.invalidate()
    }

    /**
     * The headline card: which phone hears the knocking loudest (Closest.kt). Engine's panel text is
     * "CLOSEST|LEANING[ (voice)]: Sensor B · 6a46", then "loudest on ...", the per-phone tally ("A 1  B 5") and the
     * optional "Sound ≈ ..." / "Carried: ..." lines. The card shows the phone big and the readable lines under it;
     * the tally stays in the log.
     */
    private fun renderClosest() {
        renderWarmth()
        if (btnSensitivity.isChecked != Engine.sensitiveHigh) { settingSwitch = true; btnSensitivity.isChecked = Engine.sensitiveHigh; settingSwitch = false }
        val text = Engine.closestText
        val tint: Int
        if (text.isEmpty()) {
            closestOverline.text = activity.getString(R.string.closest_overline)
            closestTitle.text = activity.getString(R.string.closest_idle_title)
            closestText.text = activity.getString(R.string.closest_idle)
            tint = R.color.surface_muted
        } else {
            val lines = text.split('\n')
            val head = lines[0]
            val sure = head.startsWith("CLOSEST")
            val who = head.substringAfter(": ", head)
            val phone = who.substringBefore(" · ")
            val suffix = who.substringAfter(" · ", "")
            closestOverline.text = activity.getString(if (sure) R.string.closest_overline else R.string.closest_overline_leaning) +
                if (head.contains("(voice)")) " · by voice" else ""
            closestTitle.text = if (suffix.isEmpty()) phone else "$phone  ·  $suffix"
            val tally = Regex("^[A-Z] \\d+(\\s|$)")
            closestText.text = lines.drop(1).filter { !tally.containsMatchIn(it) }.joinToString("\n") { l ->
                l.replace(" · red circle on the map", "").replace("≈", "about").replaceFirstChar { it.uppercase() }
            }
            tint = if (sure) R.color.good_soft else R.color.warn_soft
        }
        closestCard.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(tint))
        closestTitle.setTextColor(activity.getColor(when (tint) { R.color.good_soft -> R.color.good; R.color.warn_soft -> R.color.warn; else -> R.color.text }))
    }

    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {
        this.ranks = ranks
        map.strongest = mapLeader(ranks)
        briefText.text = brief
        briefText.setTextColor(activity.getColor(if (ranks.firstOrNull()?.let { it.evidence >= 0.9f } == true) R.color.good else R.color.text))
        render()
    }

    private fun render() {
        val names = HashMap<String, String>().apply { peers.forEach { put(it.letter, it.name) } }
        val sb = SpannableStringBuilder()
        val strongest = ranks.firstOrNull()?.takeIf { it.score > 0f }?.letter
        val rankByLetter = ranks.associateBy { it.letter }
        val me = Engine.letter
        val letters = if (ranks.isNotEmpty()) ranks.map { it.letter } + (latest.keys - ranks.map { it.letter }.toSet())
                      else (listOf("A", me) + peers.map { it.letter } + latest.keys).filter { it != "?" }.distinct().sorted()
        for (l in letters) {
            val e = latest[l]
            val r = rankByLetter[l]
            val name = if (l == me) "${activity.getString(R.string.this_phone)} ${Engine.name.takeLast(4)}" else names[l]?.takeLast(4) ?: activity.getString(R.string.offline)
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
