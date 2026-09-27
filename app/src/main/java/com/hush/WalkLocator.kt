package com.hush

import com.hush.audio.KnockBearing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

/**
 * Persona B, FIND a noise with ONE phone by walking (docs/PLAN-personas-bc.md, step 3).
 *
 * The phone's own two-mic arrow ([KnockBearing]) says which way a repeating noise is from where the walker stands,
 * with a left/right mirror that turning the phone resolves. One phone cannot cross lines with itself unless it moves,
 * so: stand still, let the arrow settle, a MARK is recorded (position from step counting, the bearing and its twin,
 * the loudness); walk a couple of metres to the side, stand still, second mark; third mark. The marks' bearing lines
 * are crossed by [Crossing] exactly as the commander crosses its sensors' lines. With two marks both still mirrored,
 * every combination fits exactly; loudness breaks the tie when it can (the louder mark should be the nearer one),
 * otherwise the prompt asks for a turn or a third spot. Frame: x = east, y = north, metres from where FIND started,
 * bearings clockwise from north. Pure logic; [resetBearings] is called on arrival at a new spot so the spot's
 * arrow is not polluted by the last spot's knocks (that difference is what gives the distance).
 */
class WalkLocator(private val resetBearings: () -> Unit = {}) {

    data class Mark(val n: Int, val x: Double, val y: Double, val bearingDeg: Double, val twinDeg: Double?,
                    val confidence: Float, val levelDb: Float, val knocks: Int, val atMs: Long)

    data class Fix(val x: Double, val y: Double, val radius: Double, val marks: Int, val tieBrokenByLoudness: Boolean)

    enum class Prompt { LISTENING, STAND_STILL, HOLD_ON, WALK_SIDEWAYS, TURN_OR_THIRD, FIX }

    data class State(
        val marks: List<Mark>,
        val fix: Fix?,
        val prompt: Prompt,
        val knocksHere: Int,          // usable knocks the arrow has at this spot
        val stillS: Int,              // seconds standing still
        val warmerDb: Float?,         // loudness now against the last mark (+ = warmer), null before the first mark
        val bearingToFixDeg: Double?, // from where the walker stands now, clockwise from north
        val distanceToFixM: Double?
    )

    companion object {
        /** Standing still this long (accelerometer quiet) at a spot with enough knocks makes a mark. */
        const val STILL_S = 3
        /** A new spot is at least this far from the last mark. */
        const val SPOT_MIN_M = 1.5
        const val MAX_RANGE_M = 15.0
        /** With two mirrored marks, loudness decides only when one combination explains the level difference this much better (dB). */
        const val LOUD_TIE_MARGIN_DB = 3.0
        const val LEVEL_SMOOTH_S = 5
    }

    private val marks = ArrayList<Mark>()
    private var fix: Fix? = null
    private var stillSinceMs = -1L
    private var spotX = 0.0; private var spotY = 0.0        // where the current spot's listening started
    private var awaySinceLastMark = false                   // walked ≥ SPOT_MIN_M since the last mark
    private val levels = ArrayDeque<Float>()
    private var lastLogged = ""

    fun reset() {
        marks.clear(); fix = null; stillSinceMs = -1L; awaySinceLastMark = true; levels.clear(); lastLogged = ""
        spotX = 0.0; spotY = 0.0
        resetBearings()
        HLog.d("WALK reset")
    }

    val state: State get() = build(null, 0, 0, 0.0, 0.0)

    /**
     * Once a second. [x], [y] metres east/north of the start (step counting), [estimate] the own arrow at this
     * moment (null while it has too few knocks), [levelDb] loudness above the room's floor in dB (null when quiet).
     */
    fun onSecond(nowMs: Long, x: Double, y: Double, moving: Boolean, estimate: KnockBearing.Estimate?, levelDb: Float?): State {
        if (levelDb != null) { levels.addLast(levelDb); while (levels.size > LEVEL_SMOOTH_S) levels.removeFirst() }
        val last = marks.lastOrNull()
        val fromLast = if (last == null) Double.MAX_VALUE else hypot(x - last.x, y - last.y)
        if (fromLast >= SPOT_MIN_M) awaySinceLastMark = true
        if (moving) {
            if (stillSinceMs >= 0) HLog.d("WALK moving again")
            stillSinceMs = -1L
        } else if (stillSinceMs < 0) {
            stillSinceMs = nowMs
            // Arrived somewhere. A fresh histogram if this is a new spot, so its arrow speaks for this spot only.
            if (last == null || hypot(x - spotX, y - spotY) >= SPOT_MIN_M) {
                spotX = x; spotY = y
                resetBearings()
                HLog.d("WALK new spot at (%.1f, %.1f) m".format(x, y))
            }
        }
        val stillS = if (stillSinceMs < 0) 0 else ((nowMs - stillSinceMs) / 1000).toInt()
        val knocksHere = estimate?.knocks ?: 0

        if (estimate != null && !moving && stillS >= STILL_S) {
            val level = levels.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            if (last != null && !awaySinceLastMark) {
                // Same spot: refresh the mark (the twin may have resolved by turning, more knocks, a better level).
                val m = last.copy(x = x, y = y, bearingDeg = estimate.bearingDeg.toDouble(), twinDeg = estimate.twinBearingDeg?.toDouble(),
                    confidence = estimate.confidence, levelDb = level, knocks = estimate.knocks, atMs = nowMs)
                if (m.bearingDeg != last.bearingDeg || m.twinDeg != last.twinDeg || m.knocks != last.knocks) {
                    marks[marks.size - 1] = m
                    log("WALK mark #%d updated at (%.1f, %.1f) m bearing %.0f° twin %s conf %.2f level %.0f dB knocks %d".format(
                        m.n, m.x, m.y, m.bearingDeg, m.twinDeg?.let { "%.0f°".format(it) } ?: "-", m.confidence, m.levelDb, m.knocks))
                    solve()
                }
            } else if (last == null || fromLast >= SPOT_MIN_M) {
                val m = Mark(marks.size + 1, x, y, estimate.bearingDeg.toDouble(), estimate.twinBearingDeg?.toDouble(), estimate.confidence, level, estimate.knocks, nowMs)
                marks.add(m)
                awaySinceLastMark = false
                log("WALK mark #%d at (%.1f, %.1f) m bearing %.0f° twin %s conf %.2f level %.0f dB knocks %d".format(
                    m.n, m.x, m.y, m.bearingDeg, m.twinDeg?.let { "%.0f°".format(it) } ?: "-", m.confidence, m.levelDb, m.knocks))
                solve()
            }
        }
        return build(estimate, knocksHere, stillS, x, y)
    }

    /** The MARK button: record this spot now, whatever the timers say (needs an arrow). */
    fun markNow(nowMs: Long, x: Double, y: Double, estimate: KnockBearing.Estimate?): String {
        estimate ?: return "no arrow yet: the phone has not heard enough knocks here"
        val level = levels.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val last = marks.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < SPOT_MIN_M) {
            marks[marks.size - 1] = last.copy(x = x, y = y, bearingDeg = estimate.bearingDeg.toDouble(), twinDeg = estimate.twinBearingDeg?.toDouble(),
                confidence = estimate.confidence, levelDb = level, knocks = estimate.knocks, atMs = nowMs)
            solve()
            return "mark #${last.n} refreshed (you are still at the same spot)"
        }
        val m = Mark(marks.size + 1, x, y, estimate.bearingDeg.toDouble(), estimate.twinBearingDeg?.toDouble(), estimate.confidence, level, estimate.knocks, nowMs)
        marks.add(m); awaySinceLastMark = false
        log("WALK mark #%d (button) at (%.1f, %.1f) m bearing %.0f° twin %s".format(m.n, m.x, m.y, m.bearingDeg, m.twinDeg?.let { "%.0f°".format(it) } ?: "-"))
        solve()
        return "mark #${m.n} placed"
    }

    private fun solve() {
        val lines = marks.map { Crossing.Line("${it.n}", it.x, it.y, listOfNotNull(it.bearingDeg, it.twinDeg)) }
        val cands = Crossing.candidates(lines, MAX_RANGE_M)
        var tieByLoudness = false
        val chosen: Crossing.Result? = when {
            cands.isEmpty() -> null
            cands.size == 1 -> cands[0]
            else -> {
                // Crossing's own rule first (a clear residual winner), then loudness: the louder mark should be nearer.
                Crossing.solve(lines, MAX_RANGE_M) ?: run {
                    val scored = cands.map { it to loudnessError(it) }.sortedBy { it.second }
                    if (scored.size >= 2 && scored[1].second - scored[0].second >= LOUD_TIE_MARGIN_DB) { tieByLoudness = true; scored[0].first } else null
                }
            }
        }
        val newFix = chosen?.let { Fix(it.x, it.y, it.radius, marks.size, tieByLoudness) }
        if (newFix != fix) {
            fix = newFix
            if (newFix != null) log("WALK cross (%.1f, %.1f) m ±%.1f from %d marks%s".format(newFix.x, newFix.y, newFix.radius, marks.size, if (tieByLoudness) " (mirror settled by loudness)" else ""))
            else log("WALK: no point from ${marks.size} marks (${cands.size} candidates)" + (if (marks.size >= 2) ": turn around once or walk to a third spot" else ""))
        }
    }

    /** How badly a candidate point disagrees with the marks' loudness (1/r law): rms dB error over mark pairs. */
    private fun loudnessError(c: Crossing.Result): Double {
        var sum = 0.0; var n = 0
        for (i in marks.indices) for (j in i + 1 until marks.size) {
            val ri = hypot(c.x - marks[i].x, c.y - marks[i].y).coerceAtLeast(0.3)
            val rj = hypot(c.x - marks[j].x, c.y - marks[j].y).coerceAtLeast(0.3)
            val predicted = 20.0 * log10(rj / ri)            // level_i − level_j if the source is at c
            val measured = (marks[i].levelDb - marks[j].levelDb).toDouble()
            sum += (predicted - measured) * (predicted - measured); n++
        }
        return if (n == 0) 0.0 else Math.sqrt(sum / n)
    }

    private fun build(estimate: KnockBearing.Estimate?, knocksHere: Int, stillS: Int, x: Double, y: Double): State {
        val f = fix
        val last = marks.lastOrNull()
        val levelNow = levels.lastOrNull()
        val warmer = if (last != null && levelNow != null) levelNow - last.levelDb else null
        val prompt = when {
            f != null -> Prompt.FIX
            marks.size >= 2 -> Prompt.TURN_OR_THIRD
            marks.size == 1 && !awaySinceLastMark -> Prompt.WALK_SIDEWAYS
            estimate == null && knocksHere == 0 -> Prompt.LISTENING
            stillS == 0 -> Prompt.STAND_STILL
            else -> Prompt.HOLD_ON
        }
        val bearing = f?.let { Crossing.bearing(x, y, it.x, it.y) }
        val dist = f?.let { hypot(it.x - x, it.y - y) }
        return State(marks.toList(), f, prompt, knocksHere, stillS, warmer, bearing, dist)
    }

    private fun log(s: String) { if (s != lastLogged) { lastLogged = s; HLog.d(s) } }

    /** For tests: the marks' bearing lines as the crossing sees them. */
    fun lines(): List<Crossing.Line> = marks.map { Crossing.Line("${it.n}", it.x, it.y, listOfNotNull(it.bearingDeg, it.twinDeg)) }
}
