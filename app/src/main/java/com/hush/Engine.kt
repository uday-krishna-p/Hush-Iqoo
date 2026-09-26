package com.hush

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.os.BatteryManager
import android.os.VibrationAttributes
import com.hush.audio.AccelChannel
import com.hush.audio.AudioCapture
import com.hush.audio.Classifier
import com.hush.audio.Ping
import com.hush.audio.RhythmTracker
import com.hush.audio.TapDetector
import com.hush.model.Command
import com.hush.model.Messages
import com.hush.model.SensorEvent
import com.hush.net.NearbyLink
import java.io.File
import kotlin.math.ceil

/**
 * The one place that owns the microphone, the classifier, the tap detector, the Nearby link and the
 * Hush window. Lives as long as SensorService runs. Screens attach as [Listener] to draw the state.
 */
object Engine : AudioCapture.Listener, NearbyLink.Listener {

    const val ROLE_COMMANDER = "COMMANDER"
    const val ROLE_SENSOR = "SENSOR"

    /** Everything one second of this phone's own hearing produced. */
    data class Window(
        val rms: Float,
        val floor: Float,
        val gain: Float,
        val tap: TapDetector.Result,
        val rhythm: RhythmTracker.Result,
        val accel: AccelChannel.Result,
        val structureConfirmed: Boolean,   // an audio knock and an accelerometer jolt agreed within 100 ms recently
        val cls: Classifier.Result?,
        val event: SensorEvent
    )

    const val LABEL_TAPPING = "HUMAN TAPPING"
    const val LABEL_VOICE = "HUMAN VOICE"
    const val LABEL_MACHINE = "MACHINERY"
    const val LABEL_MOVEMENT = "MOVEMENT"
    const val LABEL_QUIET = "quiet"

    /**
     * The headline for one second, most trusted evidence first. Audio rhythm alone is enough for HUMAN TAPPING:
     * in a collapse nothing guarantees a solid structure to carry vibration, so the accelerometer only ever adds.
     * Movement is reported as a warning flag beside the label, never instead of it.
     * A single impact never makes a headline: rescuers ask victims to tap repeatedly for exactly this reason.
     */
    fun fuseLabel(rhythm: RhythmTracker.Result, cls: Classifier.Result?): String {
        val voice = cls?.human ?: 0f
        val machine = cls?.machine ?: 0f
        return when {
            rhythm.score >= 0.9f -> LABEL_TAPPING
            voice >= 0.30f -> LABEL_VOICE
            machine >= 0.35f -> LABEL_MACHINE
            else -> LABEL_QUIET
        }
    }

    data class Peer(val endpointId: String, val name: String, val letter: String)

    /** What the commander is listening for. Decides which evidence feeds the rank score. */
    enum class Mode { TAPPING, VOICE, ANY }

    /** One sensor's result for one Hush window. */
    data class Rank(
        val letter: String,
        val score: Float,        // mean of the 5 best windows of (rms above floor) × evidence
        val evidence: Float,     // best evidence seen in the window, 0..1
        val rhythm: String?,     // most frequent rhythm name, if any
        val windows: Int,        // seconds of data received
        val moving: Boolean,     // flagged as handled/shaken during the window
        val tempoMs: Int = 0,    // tapping signature
        var source: Int = 0      // 1, 2, … which distinct source this sensor is hearing (0 = none)
    )

    /**
     * Two people tapping do not tap at the same rate. Sensors with tapping evidence are grouped by signature:
     * same pattern name, or tempo within 25 %. Each group is one source, numbered strongest first.
     */
    private fun assignSources(ranks: List<Rank>): Int {
        val tapping = ranks.filter { it.evidence >= 0.9f && it.rhythm != null }
        var next = 1
        for (r in tapping) {
            if (r.source != 0) continue
            r.source = next
            for (o in tapping) {
                if (o.source != 0 || o === r) continue
                val samePattern = r.rhythm != "steady" && r.rhythm == o.rhythm
                val sameTempo = r.tempoMs > 0 && o.tempoMs > 0 &&
                    kotlin.math.abs(r.tempoMs - o.tempoMs).toFloat() / maxOf(r.tempoMs, o.tempoMs) <= 0.25f
                if (samePattern || sameTempo) o.source = next
            }
            next++
        }
        return next - 1
    }

    interface Listener {
        fun onOwnWindow(w: Window)
        fun onLinkStatus(text: String)
        /** Seconds left in the Hush window, or -1 when no window is running. */
        fun onCountdown(secondsLeft: Int)
        /** Commander only: an event from any sensor, including itself. */
        fun onEvent(event: SensorEvent, peerName: String)
        /** Commander only: the connected sensor list changed. */
        fun onPeers(peers: List<Peer>)
        /** Commander only: ranking after a Hush window, strongest first, plus the one-line brief. */
        fun onRanking(ranks: List<Rank>, brief: String)
    }

    @Volatile var mode: Mode = Mode.TAPPING
        set(value) { field = value; HLog.d("Listen mode: $value") }

    private val hushEvents = HashMap<String, MutableList<SensorEvent>>()   // letter → events during the window
    val sessionLog = com.hush.log.SessionLog()

    /** Commander's map: letter → (x, y) fractions of the square. Kept here so it survives screen changes. */
    val mapDots = LinkedHashMap<String, Pair<Float, Float>>()

    // ---- Compass / go-to arrow ----

    private var compass: com.hush.audio.Compass? = null
    private var deadReckoning: com.hush.audio.DeadReckoning? = null
    private var gps: com.hush.audio.Gps? = null
    private var lastPlacementSentSteps = -1
    private var stillSeconds = 0

    /** Commander: latest carried-offset reports from sensors, letter → Placement. */
    private val placements = HashMap<String, com.hush.model.Placement>()

    /** How the map got its north, for the screen. */
    @Volatile var alignSource: String = ""
        private set

    /** Sensor: after walking and then standing still for 3 s, tell the commander how far we were carried. */
    private fun trackPlacement(acc: AccelChannel.Result) {
        val dr = deadReckoning ?: return
        if (acc.moving) { stillSeconds = 0; return }
        stillSeconds++
        if (stillSeconds == 3 && dr.steps != lastPlacementSentSteps && dr.steps >= 2) {
            lastPlacementSentSteps = dr.steps
            val p = com.hush.model.Placement(letter, dr.east, dr.north, dr.steps)
            HLog.d("Placement: carried east=%.1f north=%.1f in %d steps".format(dr.east, dr.north, dr.steps))
            if (role == ROLE_SENSOR) link?.broadcast(p.toJson()) else onPlacement(p)
        }
    }

    private fun onPlacement(p: com.hush.model.Placement) {
        placements[p.letter] = p
        HLog.d("Placement from ${p.letter}: east=%.1f north=%.1f steps=%d".format(p.east, p.north, p.steps))
        alignFromPlacements()
    }

    /**
     * Orient the ranged map from carried offsets: the commander's own offset and each sensor's give
     * north-referenced vectors A→sensor; compare with the map vectors A→sensor to get the rotation.
     */
    private fun alignFromPlacements() {
        val dr = deadReckoning
        val myE = dr?.east ?: 0f; val myN = dr?.north ?: 0f
        val estimates = ArrayList<Pair<Float, Float>>()   // (rotation no-mirror, rotation mirror)
        for ((l, p) in placements) {
            if (l == "A" || p.steps < 3) continue
            val mapB = mapBearing("A", l) ?: continue
            val e = p.east - myE; val n = p.north - myN
            if (kotlin.math.hypot(e, n) < 1.0f) continue
            val worldB = ((Math.toDegrees(kotlin.math.atan2(e.toDouble(), n.toDouble())) + 360.0) % 360.0).toFloat()
            val mapBMirror = (360f - mapB) % 360f
            estimates.add((((worldB - mapB) + 720f) % 360f) to (((worldB - mapBMirror) + 720f) % 360f))
        }
        if (estimates.isEmpty()) return
        fun circMean(xs: List<Float>): Float {
            val r = xs.map { Math.toRadians(it.toDouble()) }
            return ((Math.toDegrees(kotlin.math.atan2(r.map { kotlin.math.sin(it) }.average(), r.map { kotlin.math.cos(it) }.average())) + 360.0) % 360.0).toFloat()
        }
        fun spread(xs: List<Float>): Float {
            val r = xs.map { Math.toRadians(it.toDouble()) }
            return (1.0 - kotlin.math.hypot(r.map { kotlin.math.cos(it) }.average(), r.map { kotlin.math.sin(it) }.average())).toFloat()
        }
        val noM = estimates.map { it.first }; val mi = estimates.map { it.second }
        val useMirror = estimates.size >= 2 && spread(mi) < spread(noM)
        if (useMirror != mirror) { mirror = useMirror; HLog.d("Placement align: mirror=$mirror") }
        mapRotationDeg = if (useMirror) circMean(mi) else circMean(noM)
        alignSource = "placement walk (${estimates.size} sensor${if (estimates.size > 1) "s" else ""})"
        HLog.d("Placement align: rotation %.0f° from %d sensors (mirror=%b)".format(mapRotationDeg, estimates.size, mirror))
        setRangingStatus("Map aligned to north from how the sensors were carried out.")
    }
    /** Degrees to add to a map bearing to get a real compass bearing; set by ALIGN. Null until aligned. */
    @Volatile var mapRotationDeg: Float? = null
        private set
    val headingDeg: Float get() = compass?.headingDeg ?: 0f

    /** Bearing on the map from dot [from] to dot [to], degrees clockwise from map-up. */
    fun mapBearing(from: String, to: String): Float? {
        val a = mapDots[from] ?: return null
        val b = mapDots[to] ?: return null
        val dx = b.first - a.first; val dy = b.second - a.second   // map y grows downward
        return ((Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()) + 360f) % 360f
    }

    /** Map distance between dots in metres, using the last ranging scale (null if the map was placed by hand). */
    fun mapDistanceMetres(from: String, to: String): Float? {
        val scale = mapMetresPerUnit ?: return null
        val a = mapDots[from] ?: return null
        val b = mapDots[to] ?: return null
        return kotlin.math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat() * scale
    }
    var mapMetresPerUnit: Float? = null
        private set


    /** Screen angle (clockwise from the phone's top) of the arrow that points at [letter], or null. */
    fun arrowAngleTo(letter: String): Float? {
        val bearing = mapBearing("A", letter) ?: return null
        val rot = mapRotationDeg ?: return null
        return ((bearing + rot - headingDeg) + 720f) % 360f
    }

    // ---- Acoustic ranging (auto placement) ----

    /** heard[hearer letter][chirping letter] = sample index on the hearer's clock. Commander only. */
    private val heard = HashMap<String, HashMap<String, Long>>()
    private var rangingInProgress = false
    @Volatile var rangingStatus: String = ""
        private set
    var mirror = false   // commander's "flip" for the mirror ambiguity of a triangle

    private const val CHIRP_GAP_MS = 1800L
    private const val SEARCH_BEFORE_MS = 200
    private const val SEARCH_AFTER_MS = 1300

    /** Commander: run one chirp per phone, then place the dots from the pairwise distances. */
    fun autoPlace() {
        if (role != ROLE_COMMANDER || rangingInProgress) return
        val letters = listOf("A") + peers.values.map { it.letter }
        if (letters.size < 3) { setRangingStatus("Need at least 3 phones connected (have ${letters.size})"); return }
        rangingInProgress = true
        heard.clear()
        doaThisRound.clear()
        setRangingStatus("Ranging: chirping ${letters.joinToString(" ")}…")
        sessionLog.addRecord("ranging_start", mapOf("letters" to letters.joinToString(" ")))
        letters.forEachIndexed { i, l ->
            main.postDelayed({ triggerChirp(l) }, chirpToken, i * CHIRP_GAP_MS)
        }
        main.postDelayed({ finishRanging(letters) }, chirpToken, letters.size * CHIRP_GAP_MS + 2500)
    }

    /** Commander: tell [letter] to chirp now; everyone (including us) listens for it. */
    private fun triggerChirp(letter: String) {
        link?.broadcast(Command(Command.CHIRP, letter = letter).toJson())
        onChirpCommand(letter)
    }

    /** Every phone: a chirp from [letter] is about to happen. Play it if it is ours, and search for it. */
    private fun onChirpCommand(letter: String) {
        val cap = capture ?: return
        val startSample = cap.samplesCaptured - AudioCapture.SAMPLE_RATE.toLong() * SEARCH_BEFORE_MS / 1000
        if (letter == this.letter) appContext?.let { com.hush.audio.Chirp.play(it) }
        val count = AudioCapture.SAMPLE_RATE * (SEARCH_BEFORE_MS + SEARCH_AFTER_MS) / 1000
        main.postDelayed(Runnable {
            Thread {
                val audio = cap.snapshot(startSample, count)
                if (audio == null) { HLog.d("Chirp search: audio for $letter not in buffer"); return@Thread }
                val t0 = SystemClock.elapsedRealtime()
                val det = com.hush.audio.Chirp.detect(audio, count)
                val at = startSample + det.offset
                HLog.d("Chirp from $letter heard by ${this.letter}: offset=${det.offset} sample=$at peak=%.2f ratio=%.1f (%d ms)".format(det.peak, det.ratio, SystemClock.elapsedRealtime() - t0))
                if (det.offset < 0 || det.ratio < 5f) { HLog.d("Chirp from $letter: not credible, dropped"); return@Thread }
                // Second mic: same full search, so its peak is judged against the same baseline; the
                // sub-sample difference between the two peaks is the inter-mic delay (direction of arrival).
                var micDelay: Float? = null
                if (cap.stereo && letter != this.letter) {
                    val a2 = cap.snapshot(startSample, count, 1)
                    if (a2 != null) {
                        val d2 = com.hush.audio.Chirp.detect(a2, count)
                        val gap = kotlin.math.abs(d2.offset - det.offset)
                        if (d2.offset >= 0 && d2.ratio >= 5f && gap <= 40) {
                            micDelay = (d2.fine - det.fine).toFloat()
                            HLog.d("Chirp from $letter mic delay: %.2f samples (mic1 − mic0), ratio2=%.1f, heading=%.0f".format(micDelay, d2.ratio, headingDeg))
                        } else {
                            HLog.d("Chirp from $letter: second mic not usable (offset gap $gap, ratio2 %.1f)".format(d2.ratio))
                        }
                    }
                }
                val report = com.hush.model.ChirpReport(this.letter, letter, at, det.ratio, micDelay, headingDeg)
                main.post {
                    if (role == ROLE_COMMANDER) onChirpReport(report) else link?.broadcast(report.toJson())
                }
            }.start()
        }, chirpToken, SEARCH_AFTER_MS + 100L)
    }

    /** Commander's own two-mic delays this round: chirping letter → (delay samples, heading). */
    private val doaThisRound = HashMap<String, Pair<Float, Float>>()

    private fun onChirpReport(r: com.hush.model.ChirpReport) {
        heard.getOrPut(r.hearer) { HashMap() }[r.from] = r.sample
        HLog.d("Chirp report: ${r.hearer} heard ${r.from} at ${r.sample} ratio=%.1f".format(r.ratio))
        if (r.hearer == "A" && r.micDelay != null && r.heading != null) doaThisRound[r.from] = r.micDelay to r.heading
    }

    /**
     * Direction of arrival on the commander: the inter-mic delay of each sensor's chirp gives the angle
     * from the mic axis; the ranged triangle tells the true angle between the sensors, which fixes the
     * unknown mic spacing and the left/right mirror. Result: map rotation to north with nobody moving.
     */
    private fun alignFromDoa(a: String, b: String, c: String, dAB: Double, dAC: Double, dBC: Double) {
        val db = doaThisRound[b] ?: return
        val dc = doaThisRound[c] ?: return
        // True angle at A between B and C from the triangle.
        val cosG = ((dAB * dAB + dAC * dAC - dBC * dBC) / (2 * dAB * dAC)).coerceIn(-1.0, 1.0)
        val gamma = Math.toDegrees(kotlin.math.acos(cosG))
        val fs = com.hush.audio.Chirp.SAMPLE_RATE.toDouble(); val cs = com.hush.audio.Chirp.SPEED_OF_SOUND.toDouble()
        // Try candidate mic spacings and both mirror choices; keep the one whose DoA angles match gamma.
        var bestErr = 1e9; var bestD = 0.0; var bestMirrorC = false; var bestThB = 0.0; var bestThC = 0.0
        var d = 0.06
        while (d <= 0.20) {
            val maxDelay = d * fs / cs
            val cb = (db.first / maxDelay).coerceIn(-1.0, 1.0); val cc = (dc.first / maxDelay).coerceIn(-1.0, 1.0)
            val thB = Math.toDegrees(kotlin.math.acos(cb)); val thC = Math.toDegrees(kotlin.math.acos(cc))
            for (mirrorC in listOf(false, true)) {
                val angleBetween = kotlin.math.abs(thB - (if (mirrorC) -thC else thC))
                val err = kotlin.math.abs(angleBetween - gamma) + 200.0 * kotlin.math.abs(d - 0.14)   // prefer plausible spacing
                if (err < bestErr) { bestErr = err; bestD = d; bestMirrorC = mirrorC; bestThB = thB; bestThC = if (mirrorC) -thC else thC }
            }
            d += 0.005
        }
        if (bestErr > 25.0) { HLog.d("DoA align: no consistent solution (err %.1f°, gamma %.1f°)".format(bestErr, gamma)); return }
        // Angles are measured from the mic axis (phone top). The sign of the mic order is unknown, so two
        // world bearings are possible; the ranged map decides which by comparing map bearings.
        val mapB = mapBearing(a, b) ?: return; val mapC = mapBearing(a, c) ?: return
        var chosenRot = 0f; var chosenSpread = 1e9
        for (signFlip in listOf(1.0, -1.0)) {
            val wb = (db.second + signFlip * bestThB + 720.0) % 360.0
            val wc = (dc.second + signFlip * bestThC + 720.0) % 360.0
            for (mir in listOf(false, true)) {
                val mb = if (mir) (360.0 - mapB) % 360.0 else mapB.toDouble()
                val mc = if (mir) (360.0 - mapC) % 360.0 else mapC.toDouble()
                val r1 = (wb - mb + 720.0) % 360.0; val r2 = (wc - mc + 720.0) % 360.0
                var diff = kotlin.math.abs(r1 - r2); if (diff > 180) diff = 360 - diff
                if (diff < chosenSpread) {
                    chosenSpread = diff
                    val rr = Math.toRadians(r1); val ss = Math.toRadians(r2)
                    chosenRot = ((Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(rr) + kotlin.math.sin(ss), kotlin.math.cos(rr) + kotlin.math.cos(ss))) + 360.0) % 360.0).toFloat()
                    if (mir != mirror) { mirror = mir }
                }
            }
        }
        HLog.d("DoA align: spacing %.3f m, thetaB %.0f° thetaC %.0f° (gamma %.0f°, err %.1f°), rotation %.0f° (spread %.0f°, mirror=%b)".format(bestD, bestThB, bestThC, gamma, bestErr, chosenRot, chosenSpread, mirror))
        if (chosenSpread <= 30.0) {
            mapRotationDeg = chosenRot
            alignSource = "two-mic direction (±%.0f°)".format(chosenSpread / 2)
            setRangingStatus("Map aligned to north from chirp directions.")
        }
    }

    // Sensors B and C define the map frame (they do not move); the commander A moves inside it.
    // Map unit = metres × mapScale, centred on the sensors' midpoint. Fixed after the first ranging.
    private var frameB: String? = null
    private var frameC: String? = null
    private var frameDBC = 0.0
    private var lastAInFrame: Pair<Double, Double>? = null      // metres, B at origin, C on +x
    private var lastAFrameMs = 0L

    private fun finishRanging(letters: List<String>) {
        rangingInProgress = false
        // Sanity: chirps were triggered CHIRP_GAP_MS apart, so on any one phone's clock chirp k must
        // arrive about k × gap after chirp 0. A detection far off that is a wrong peak: drop it.
        val fs = AudioCapture.SAMPLE_RATE
        for ((hearer, byFrom) in heard) {
            val t0 = byFrom[letters[0]] ?: continue
            val bad = byFrom.filter { (from, t) ->
                val k = letters.indexOf(from)
                k > 0 && kotlin.math.abs((t - t0) - k * CHIRP_GAP_MS * fs / 1000) > 0.7 * fs
            }.keys
            if (bad.isNotEmpty()) { HLog.d("Ranging: $hearer had wrong peaks for $bad, dropped"); bad.forEach { byFrom.remove(it) } }
        }
        val dist = HashMap<String, Double>()
        for (i in letters.indices) for (j in i + 1 until letters.size) {
            val d = Ranging.pairDistance(heard, letters[i], letters[j])
            if (d != null) dist["${letters[i]}${letters[j]}"] = d
        }
        val text = dist.entries.joinToString("  ") { "%s %.2f m".format(it.key, it.value) }
        HLog.d("RANGING result: $text  (heard=$heard)")
        sessionLog.addRecord("ranging", mapOf("distances" to text))
        var placed = false
        if (letters.size >= 3) {
            val a = letters[0]; val b = letters[1]; val c = letters[2]
            val dAB = dist["$a$b"]; val dAC = dist["$a$c"]; val dBC = dist["$b$c"]
            if (dAB != null && dAC != null && dBC != null && dBC > 0.05) {
                // A in the B–C frame: B=(0,0), C=(dBC,0).
                val x = (dAB * dAB - dAC * dAC + dBC * dBC) / (2 * dBC)
                val y2 = dAB * dAB - x * x
                val y = (if (y2 > 0) kotlin.math.sqrt(y2) else 0.0) * (if (mirror) -1 else 1)
                val aPos = x to y
                if (frameB != b || frameC != c || mapMetresPerUnit == null) {
                    frameB = b; frameC = c; frameDBC = dBC
                    mapMetresPerUnit = (maxOf(dAB, dAC, dBC) * 1.6).toFloat().coerceAtLeast(1f)   // span so the triangle fits with room to walk
                    lastAInFrame = null
                }
                val scale = 1.0 / mapMetresPerUnit!!
                val cx = dBC / 2; val cy = 0.0
                fun toMap(p: Pair<Double, Double>) = ((0.5 + (p.first - cx) * scale).toFloat()) to ((0.5 - (p.second - cy) * scale).toFloat())
                mapDots[b] = toMap(0.0 to 0.0)
                mapDots[c] = toMap(dBC to 0.0)
                mapDots[a] = toMap(aPos)
                autoAlign(aPos)
                alignFromDoa(a, b, c, dAB, dAC, dBC)
                doaThisRound.clear()
                setRangingStatus("Placed by sound: $text")
                listener?.onPeers(peers.values.toList())
                placed = true
            }
        }
        if (!placed) setRangingStatus(if (dist.isEmpty()) "Ranging failed: no chirps heard. Place manually." else "Ranging incomplete: $text. Place manually.")
        if (pendingHushSeconds > 0) {
            val s = pendingHushSeconds
            pendingHushSeconds = 0
            broadcastHush(s)
        }
    }

    // ---- Automatic alignment to north from the commander's own walking ----

    private var walkHeadingSinX = 0.0
    private var walkHeadingSinY = 0.0
    private var walkSamples = 0
    private var movedSinceRanging = 0f          // seconds of movement since the last ranging
    private var alignSolutions = ArrayList<Pair<Float, Float>>()   // (rotation if not mirrored, rotation if mirrored)

    /** Called once per second from the audio window on the commander: accumulate walking heading. */
    private fun trackWalking(acc: AccelChannel.Result) {
        if (role != ROLE_COMMANDER) return
        if (acc.rmsHp > 0.25f) {
            movedSinceRanging += 1f
            val h = Math.toRadians(headingDeg.toDouble())
            walkHeadingSinX += kotlin.math.cos(h); walkHeadingSinY += kotlin.math.sin(h); walkSamples++
        }
        // Re-range on its own after walking a few seconds, outside a window.
        if (!inHush && !rangingInProgress && movedSinceRanging >= 3f && peers.size >= 2 &&
            SystemClock.elapsedRealtime() - lastAFrameMs > 8000) {
            HLog.d("Auto re-ranging after %.0f s of walking".format(movedSinceRanging))
            autoPlace()
        }
    }

    private fun autoAlign(aPos: Pair<Double, Double>) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastAInFrame
        val walkBearing = if (walkSamples > 0) ((Math.toDegrees(kotlin.math.atan2(walkHeadingSinY, walkHeadingSinX)) + 360.0) % 360.0).toFloat() else null
        if (prev != null && walkBearing != null) {
            val dx = aPos.first - prev.first; val dy = aPos.second - prev.second
            val moved = kotlin.math.hypot(dx, dy)
            if (moved >= 0.5) {
                // Bearing of the move in the map frame (x right, y up): clockwise from up.
                val mapBearingNoMirror = ((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0).toFloat()
                val mapBearingMirror = ((Math.toDegrees(kotlin.math.atan2(dx, -dy)) + 360.0) % 360.0).toFloat()
                val rotNo = ((walkBearing - mapBearingNoMirror) + 720f) % 360f
                val rotMi = ((walkBearing - mapBearingMirror) + 720f) % 360f
                alignSolutions.add(rotNo to rotMi)
                // Pick the mirror hypothesis whose rotation estimates agree across walks.
                fun spread(sel: (Pair<Float, Float>) -> Float): Float {
                    val xs = alignSolutions.map { Math.toRadians(sel(it).toDouble()) }
                    val mx = xs.map { kotlin.math.cos(it) }.average(); val my = xs.map { kotlin.math.sin(it) }.average()
                    return (1.0 - kotlin.math.hypot(mx, my)).toFloat()
                }
                fun mean(sel: (Pair<Float, Float>) -> Float): Float {
                    val xs = alignSolutions.map { Math.toRadians(sel(it).toDouble()) }
                    return ((Math.toDegrees(kotlin.math.atan2(xs.map { kotlin.math.sin(it) }.average(), xs.map { kotlin.math.cos(it) }.average())) + 360.0) % 360.0).toFloat()
                }
                val useMirror = alignSolutions.size >= 2 && spread { it.second } < spread { it.first }
                if (useMirror != mirror && alignSolutions.size >= 2) {
                    HLog.d("Auto-align: switching mirror to $useMirror")
                    mirror = useMirror
                    // Re-place A with the other mirror sign next round; dots for this round stay.
                }
                mapRotationDeg = if (useMirror) mean { it.second } else mean { it.first }
                alignSource = "your walk (${alignSolutions.size})"
                HLog.d("Auto-align: moved %.2f m, walked bearing %.0f°, map rotation now %.0f° (mirror=%b, walks=%d)".format(moved, walkBearing, mapRotationDeg, mirror, alignSolutions.size))
                setRangingStatus("Aligned to north from your walk (%.1f m).".format(moved))
            }
        }
        lastAInFrame = aPos
        lastAFrameMs = now
        walkHeadingSinX = 0.0; walkHeadingSinY = 0.0; walkSamples = 0
        movedSinceRanging = 0f
    }

    private fun setRangingStatus(s: String) {
        rangingStatus = s
        HLog.d(s)
        listener?.onLinkStatus(lastStatus)   // nudges the screen to redraw; it reads rangingStatus
    }

    /** Commander: write the session log to Downloads. Returns the file name or null. */
    fun exportLog(): String? {
        val ctx = appContext ?: return null
        val sensors = org.json.JSONObject().apply { lettersByName.forEach { (name, letter) -> put(letter, name) } }
        val dots = org.json.JSONObject().apply { mapDots.forEach { (l, p) -> put(l, org.json.JSONObject().put("x", p.first.toDouble()).put("y", p.second.toDouble())) } }
        return sessionLog.export(ctx, mapOf("commander" to localName, "sensors" to sensors, "dots" to dots, "mode" to mode.name, "lastBrief" to lastBrief))
    }
    private var hushStartMs = 0L
    var lastRanking: List<Rank> = emptyList()
        private set
    var lastBrief: String = ""
        private set

    private fun evidence(e: SensorEvent): Float = when (mode) {
        Mode.TAPPING -> e.rhythmScore
        Mode.VOICE -> e.human
        Mode.ANY -> maxOf(e.rhythmScore, e.human)
    }

    /** Commander: every event, own or received, lands here. */
    private fun recordEvent(e: SensorEvent) {
        sessionLog.add(e.toJson())
        if (!inHush) return
        // Skip the first second: the start beep and buzz are in it.
        if (SystemClock.elapsedRealtime() < hushStartMs + 1000) return
        hushEvents.getOrPut(e.sensorId) { ArrayList() }.add(e)
    }

    private fun computeRanking() {
        val ranks = hushEvents.map { (letter, events) ->
            val scores = events.map { maxOf(0f, it.rms - it.floor) * evidence(it) }.sortedDescending()
            val best = scores.take(5)
            val rhythm = events.mapNotNull { it.rhythm }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val tempos = events.filter { it.tempoMs > 0 && it.rhythmScore >= 0.9f }.map { it.tempoMs }
            Rank(
                letter = letter,
                score = if (best.isEmpty()) 0f else best.average().toFloat(),
                evidence = events.maxOfOrNull { evidence(it) } ?: 0f,
                rhythm = rhythm,
                windows = events.size,
                moving = events.count { it.moving } > events.size / 3,
                tempoMs = if (tempos.isEmpty()) 0 else tempos.sorted()[tempos.size / 2]
            )
        }.sortedByDescending { it.score }
        val sources = if (mode != Mode.VOICE) assignSources(ranks) else 0
        lastRanking = ranks
        val top = ranks.firstOrNull()
        lastBrief = when {
            top == null -> "No sensor data in this window"
            sources >= 2 -> (1..sources).joinToString("  |  ") { s ->
                val members = ranks.filter { it.source == s }
                val lead = members.first()
                val others = members.drop(1).joinToString(",") { it.letter }
                "Source $s: tapping ${lead.rhythm} %.1f/s · strongest at Sensor ${lead.letter}".format(1000f / lead.tempoMs.coerceAtLeast(1)) +
                    (if (others.isNotEmpty()) " (also $others)" else "")
            }
            top.evidence >= 0.9f && mode != Mode.VOICE && top.rhythm != null ->
                "Human tapping · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter} · rhythm ${top.rhythm}"
            top.evidence >= 0.9f && mode != Mode.VOICE ->
                "Human tapping · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter}"
            top.evidence >= 0.3f && mode != Mode.TAPPING ->
                "Human voice · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter}"
            top.score > 0f -> "Weak signal · ${(top.evidence * 100).toInt()}% · loudest at Sensor ${top.letter}"
            else -> "No human signal detected"
        }
        HLog.d("RANKING ($mode): " + ranks.joinToString(" | ") { "%s score=%.5f ev=%.2f rh=%s n=%d".format(it.letter, it.score, it.evidence, it.rhythm, it.windows) })
        HLog.d("BRIEF: $lastBrief")
        sessionLog.addRecord("ranking", mapOf(
            "mode" to mode.name,
            "brief" to lastBrief,
            "ranks" to org.json.JSONArray(ranks.map { r ->
                org.json.JSONObject().put("id", r.letter).put("score", r.score.toDouble()).put("evidence", r.evidence.toDouble())
                    .put("rhythm", r.rhythm ?: org.json.JSONObject.NULL).put("windows", r.windows).put("moving", r.moving)
            })
        ))
        listener?.onRanking(ranks, lastBrief)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile var role: String? = null
        private set
    @Volatile var letter: String = "?"
        private set
    @Volatile var listener: Listener? = null
        set(value) {
            field = value
            // Give a fresh screen the current state straight away.
            value?.let { l ->
                main.post {
                    l.onLinkStatus(lastStatus)
                    l.onCountdown(if (inHush) secondsLeft() else -1)
                    if (role == ROLE_COMMANDER) {
                        l.onPeers(peers.values.toList())
                        if (lastRanking.isNotEmpty()) l.onRanking(lastRanking, lastBrief)
                    }
                }
            }
        }

    private var appContext: Context? = null
    private var capture: AudioCapture? = null
    private var classifier: Classifier? = null
    private val tapDetector = TapDetector(AudioCapture.SAMPLE_RATE)
    private val rhythmTracker = RhythmTracker()
    private var accel: AccelChannel? = null
    private var lastStructureMs = 0L
    private var link: NearbyLink? = null
    private var localName = "?"
    private var lastStatus = "Not started"

    // Noise floor: mean RMS of the last few seconds before a Hush window starts.
    private val recentRms = ArrayDeque<Float>()
    private const val FLOOR_SECONDS = 3
    @Volatile private var floor = 0f

    // Hush window
    @Volatile var inHush = false
        private set
    private var hushEndMs = 0L

    // Commander bookkeeping
    private val peers = LinkedHashMap<String, Peer>()         // endpointId → peer
    private val lettersByName = LinkedHashMap<String, String>() // advertised name → letter, survives reconnects

    val isRunning get() = role != null

    fun start(context: Context, newRole: String) {
        if (role != null) { HLog.d("Engine.start ignored, already running as $role"); return }
        appContext = context.applicationContext
        role = newRole
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "0000"
        localName = "${Build.MODEL}-${id.takeLast(4)}"
        letter = if (newRole == ROLE_COMMANDER) "A" else "?"
        HLog.d("Engine start role=$newRole name=$localName")

        try {
            classifier = Classifier(context)
        } catch (e: Exception) {
            HLog.d("ERROR loading classifier: $e")
        }
        accel = AccelChannel(context).also { it.start() }
        val cmp = com.hush.audio.Compass(context).also { it.start() }
        compass = cmp
        deadReckoning = com.hush.audio.DeadReckoning(context, cmp).also { it.start() }
        gps = com.hush.audio.Gps(context).also { it.start() }
        // Probe the mics once (blocks ~0.6 s) before the main capture takes the microphone.
        HLog.d(com.hush.audio.MicProbe.run())
        com.hush.net.BleRangingProbe.run(context)
        capture = AudioCapture(context, this).also {
            it.debugWav = File(context.filesDir, "debug.wav")   // debug capture, see CLAUDE.md
            it.start()
        }
        link = NearbyLink(context, localName, this).also {
            if (newRole == ROLE_COMMANDER) it.startCommander() else it.startSensor()
        }
    }

    fun stop() {
        HLog.d("Engine stop")
        capture?.stop(); capture = null
        accel?.stop(); accel = null
        compass?.stop(); compass = null
        deadReckoning?.stop(); deadReckoning = null
        gps?.stop(); gps = null
        placements.clear(); alignSource = ""; lastPlacementSentSteps = -1; stillSeconds = 0
        mapRotationDeg = null
        frameB = null; frameC = null; lastAInFrame = null; alignSolutions.clear(); mapMetresPerUnit = null
        pendingHushSeconds = 0
        classifier?.close(); classifier = null
        link?.stop(); link = null
        peers.clear()
        recentRms.clear()
        rhythmTracker.reset()
        hushEvents.clear()
        lastRanking = emptyList()
        lastBrief = ""
        inHush = false
        role = null
        letter = "?"
        lastStatus = "Not started"
    }

    // ---- Hush window ----

    /** Commander: start a Hush window on every phone including this one. */
    fun hush(seconds: Int = 20): Boolean {
        if (role != ROLE_COMMANDER) return false
        if (peers.isEmpty() && !allowSoloHush) {
            HLog.d("HUSH refused: no sensors connected")
            setRangingStatus("No sensors connected yet. Wait for them, or long-press HUSH to run alone.")
            return false
        }
        HLog.d("HUSH pressed: $seconds s to ${peers.size} sensors")
        sessionLog.addRecord("hush", mapOf("seconds" to seconds, "sensors" to peers.size + 1, "mode" to mode.name))
        // One button: range first (chirps, ~8 s) so the map is fresh, then run the window.
        if (peers.size >= 2 && !rangingInProgress) {
            pendingHushSeconds = seconds
            listener?.onCountdown(99)   // screen shows "ranging…" until the real countdown starts
            autoPlace()
        } else {
            broadcastHush(seconds)
        }
        return true
    }

    private var pendingHushSeconds = 0
    private val chirpToken = Any()

    /** Commander: stop everything running — the window on every phone and any chirp sequence. */
    fun stopAll() {
        if (role != ROLE_COMMANDER) return
        HLog.d("STOP pressed")
        main.removeCallbacksAndMessages(chirpToken)
        rangingInProgress = false
        pendingHushSeconds = 0
        inHush = false
        link?.broadcast(Command(Command.STOP).toJson())
        listener?.onCountdown(-1)
        setRangingStatus("Stopped.")
        sessionLog.addRecord("stop", emptyMap())
    }

    private fun broadcastHush(seconds: Int) {
        link?.broadcast(Command(Command.HUSH, seconds).toJson())
        startHushLocal(seconds)
    }

    /** Set by a long press on HUSH: run a window with only this phone (single-phone testing). */
    @Volatile var allowSoloHush = false

    /** The advertised name of this phone, e.g. "I2501-6a46". Shown next to the letter so people can match phones. */
    val name: String get() = localName

    private fun startHushLocal(seconds: Int) {
        floor = if (recentRms.isEmpty()) 0f else recentRms.average().toFloat()
        inHush = true
        hushStartMs = SystemClock.elapsedRealtime()
        hushEndMs = hushStartMs + seconds * 1000L
        hushEvents.clear()
        HLog.d("Hush window started: $seconds s, noise floor %.5f".format(floor))
        vibrate(longArrayOf(0, 700, 150, 700, 150, 900))   // three long full-strength pulses at the start
        appContext?.let { Ping.play(it, listOf(2500 to 250, 0 to 100, 2500 to 250)) }
        tick()
    }

    private fun secondsLeft(): Int = ceil((hushEndMs - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)

    private fun tick() {
        if (!inHush) return
        val left = secondsLeft()
        if (left <= 0) {
            inHush = false
            HLog.d("Hush window ended")
            vibrate(longArrayOf(0, 400, 120, 400))            // two pulses at the end
            appContext?.let { Ping.play(it, listOf(1800 to 400)) }
            listener?.onCountdown(-1)
            // Give the last sensor events a moment to arrive, then rank.
            if (role == ROLE_COMMANDER) main.postDelayed({ computeRanking() }, 1500)
        } else {
            listener?.onCountdown(left)
            main.postDelayed({ tick() }, 250)
        }
    }

    /**
     * [pattern] is off/on/off/on... in ms. Full amplitude, and tagged as an alarm so the phone does not
     * scale it down the way it does for ordinary app haptics (the first version was barely noticeable).
     */
    private fun vibrate(pattern: LongArray) {
        val ctx = appContext ?: return
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val amplitudes = IntArray(pattern.size) { if (it % 2 == 1) 255 else 0 }
            val effect = if (vib.hasAmplitudeControl()) VibrationEffect.createWaveform(pattern, amplitudes, -1)
                         else VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vib.vibrate(effect)
            }
            HLog.d("vibrate ${pattern.contentToString()} amplitudeControl=${vib.hasAmplitudeControl()}")
        } catch (e: Exception) {
            HLog.d("vibrate failed: $e")
        }
    }

    // ---- Own microphone ----

    override fun onWindow(pcm48k: ShortArray, n48: Int, pcm16k: FloatArray, n16: Int, rms: Float) {
        val now = SystemClock.elapsedRealtime()
        val tap = tapDetector.analyse(pcm48k, n48, now - 1000)
        val acc = accel?.drain() ?: AccelChannel.Result(0, 0f, 0f, emptyList(), false)
        // Rhythm is AUDIO ONLY. Accelerometer jolts were tried as onsets and flooded the history (6–8 per
        // second on a handled phone), which killed every detection. Jolts now only confirm a heard knock.
        rhythmTracker.add(tap.onsetsAbsMs)
        val agreed = tap.onsetsAbsMs.any { a -> acc.spikeTimesMs.any { kotlin.math.abs(it - a) <= RhythmTracker.MERGE_MS } }
        if (agreed) lastStructureMs = now
        val structure = now - lastStructureMs < RhythmTracker.HISTORY_MS
        val rhythm = rhythmTracker.evaluate(now)
        val cls = classifier?.classify(pcm16k, n16)
        val label = fuseLabel(rhythm, cls)
        main.post { trackWalking(acc); trackPlacement(acc) }
        val battery = try {
            (appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) { -1 }
        if (!inHush) {
            recentRms.addLast(rms)
            while (recentRms.size > FLOOR_SECONDS) recentRms.removeFirst()
        }
        val event = SensorEvent(
            sensorId = letter,
            tMs = now,
            rms = rms,
            floor = if (inHush) floor else (if (recentRms.isEmpty()) 0f else recentRms.average().toFloat()),
            human = cls?.human ?: 0f,
            machine = cls?.machine ?: 0f,
            topClass = cls?.topClass ?: "-",
            taps = tap.taps,
            tapScore = tap.score,
            impact = cls?.impact ?: 0f,
            rhythmScore = rhythm.score,
            rhythm = rhythm.rhythm,
            label = label,
            accel = acc.spikes,
            accelMax = acc.maxHp,
            moving = acc.moving,
            battery = battery,
            tempoMs = rhythm.tempoMs,
            lat = gps?.fix?.latitude,
            lon = gps?.fix?.longitude,
            gpsAcc = gps?.fix?.accuracy
        )
        val w = Window(rms, event.floor, classifier?.lastGain ?: 1f, tap, rhythm, acc, structure, cls, event)
        HLog.d("window id=$letter hush=$inHush label=$label rms=%.4f floor=%.4f taps=%d rej=%d peak=x%.0f acc=%d accMax=%.3f accRms=%.3f struct=%b rhythm=%.1f/%s(%d) voice=%.2f impact=%.2f machine=%.2f | %s".format(
            rms, event.floor, tap.taps, tap.rejectedSustained, tap.peakRatio, acc.spikes, acc.maxHp, acc.rmsHp, structure,
            rhythm.score, rhythm.rhythm, rhythm.count, event.human, event.impact, event.machine,
            cls?.top5?.joinToString(", ") { (name, s) -> "%s %.2f".format(name, s) } ?: "-"))
        main.post {
            listener?.onOwnWindow(w)
            when (role) {
                ROLE_SENSOR -> link?.broadcast(event.toJson())
                ROLE_COMMANDER -> { recordEvent(event); listener?.onEvent(event, localName) }
            }
        }
    }

    // ---- Nearby link ----

    override fun onLinkStatus(text: String) {
        lastStatus = text
        listener?.onLinkStatus(text)
    }

    override fun onPeerConnected(endpointId: String, name: String) {
        if (role != ROLE_COMMANDER) return
        val assigned = lettersByName.getOrPut(name) { nextFreeLetter() }
        peers[endpointId] = Peer(endpointId, name, assigned)
        HLog.d("Commander: $name is Sensor $assigned (${peers.size} connected)")
        link?.send(endpointId, Command(Command.ASSIGN, letter = assigned).toJson())
        if (inHush) link?.send(endpointId, Command(Command.HUSH, secondsLeft()).toJson())
        listener?.onPeers(peers.values.toList())
    }

    override fun onPeerDisconnected(endpointId: String) {
        if (role != ROLE_COMMANDER) return
        peers.remove(endpointId)
        listener?.onPeers(peers.values.toList())
    }

    private fun nextFreeLetter(): String {
        val used = lettersByName.values.toSet() + "A"
        return ('B'..'Z').map { it.toString() }.first { it !in used }
    }

    override fun onMessage(endpointId: String, text: String) {
        when (val msg = Messages.parse(text)) {
            is Command -> {
                HLog.d("Command received: ${msg.type} sec=${msg.seconds} letter=${msg.letter}")
                when (msg.type) {
                    Command.ASSIGN -> {
                        letter = msg.letter ?: "?"
                        onLinkStatus("Connected as Sensor $letter")
                    }
                    Command.HUSH -> startHushLocal(msg.seconds)
                    Command.STOP -> { inHush = false; main.removeCallbacksAndMessages(chirpToken); listener?.onCountdown(-1); onLinkStatus("Stopped by commander") }
                    Command.CHIRP -> msg.letter?.let { onChirpCommand(it) }
                }
            }
            is com.hush.model.ChirpReport -> onChirpReport(msg)
            is com.hush.model.Placement -> onPlacement(msg)
            is SensorEvent -> {
                val name = peers[endpointId]?.name ?: endpointId
                recordEvent(msg)
                listener?.onEvent(msg, name)
            }
        }
    }
}
