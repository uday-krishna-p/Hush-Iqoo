package com.hush

import com.hush.model.Onset
import com.hush.model.OnsetReport
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds WHERE a sound comes from (not which sensor is nearest). Commander only.
 *
 * Every candidate spot on a grid over the map is scored against three kinds of evidence, then the
 * scores of one knock after another are added up; the best cell is the source.
 *
 *  1. TIME DIFFERENCE OF ARRIVAL. Each phone reports the exact sample at which it first heard the knock.
 *     Sound travels 7 mm per sample, so "B heard it 100 samples before C" means the source is 0.7 m
 *     closer to B than to C: a hyperbola between B and C. Two or more hyperbolas cross at the source.
 *     The phones' audio clocks are unrelated, so the chirp round is used to measure each clock's
 *     offset against the commander's (a chirp is heard by everyone at a known distance; see [updateClocks]).
 *  2. LOUDNESS. Sound level falls with distance (about 1/r). After the chirp-calibrated mic gains,
 *     the ratio of levels across phones says roughly how far from each the source is. Coarse, but it
 *     tells "in front" from "behind" when the timing alone cannot, and it works for voices.
 *  3. TWO-MIC DIRECTION. Each phone's two mics give the angle between the sound and the phone's long
 *     axis. Which way that axis points on the map is learnt from the chirps too ([calibrateAxes]).
 *     A two-mic line cannot tell left from right, so each phone contributes two candidate bearings and
 *     the other evidence decides.
 *
 * Coordinates: metres in the map frame, x to the right, y up, origin at the centre of the map square.
 */
class Locator {

    companion object {
        const val FS = 48_000.0
        const val C = 343.0                    // m/s, same value the ranging uses
        const val CELL = 0.25                  // metres between candidate positions
        const val MIN_HALF_EXTENT = 12.0       // the grid reaches at least this far from the array's centre
        const val SIGMA_T = 0.0005             // s: onset timing + clock offset error (≈ 17 cm)
        const val SIGMA_A = 0.7                // ln units (≈ 6 dB): loudness is a rough cue
        const val SIGMA_DOA = 12.0             // degrees, per-phone direction error at broadside
        const val SIGMA_DOA_VOICE = 20.0
        const val WEIGHT_AMPLITUDE = 0.5
        const val DECAY_S = 25.0               // older knocks fade with this time constant
        const val HOLD_S = 3.5                 // wait this long for late reports before matching a knock
        /**
         * m: inside this a chirp's two-mic angle is not used. Was 0.8. Lowered 27 Sep for the 1.00/1.20/0.65 m
         * layout: with mics ~0.15 m apart, the far-field formula is off by < 1° even at 0.5 m (exact path
         * difference vs s·cosθ: 0.5 % at 0.65 m); the real error that close is the chirping phone's speaker sitting
         * up to ~8 cm from its centre, ≤ 7° at 0.65 m, below the locator's 12° two-mic sigma. At 0.8 m the
         * commander (0.65 m from 991e) got no DoA alignment and two of three phones no mic axis.
         */
        const val NEAR_FIELD = 0.5
        const val REGION_DROP = 3.0            // cells within this log-likelihood of the peak form the "likely region"
        const val MAX_AGE_MS = 60_000L         // a fix older than this is no longer shown
        const val DEFAULT_MIC_SPACING = 0.10   // m, until the chirp rounds have solved it
    }

    /** Where the source most likely is. */
    data class Fix(
        val x: Double, val y: Double,          // metres, map frame
        val radius: Double,                    // metres, RMS spread of the likely region around the peak
        val bearingSpreadDeg: Double,          // seen from the commander, how uncertain the direction is
        val nearest: Double,                   // metres from the commander to the nearest cell of the likely region
        val knocks: Int, val voiceSeconds: Int,
        val atMs: Long,
        val edge: Boolean,                     // the likely region touches the grid edge: direction ok, distance not
        val detail: String
    )

    @Volatile var fix: Fix? = null
        private set
    var micSpacing = DEFAULT_MIC_SPACING

    // ---- Phones and grid --------------------------------------------------------------------------

    private var letters: List<String> = emptyList()
    private var px = DoubleArray(0)
    private var py = DoubleArray(0)
    private var nx = 0; private var ny = 0
    private var x0 = 0.0; private var y0 = 0.0
    private var dist: Array<DoubleArray> = emptyArray()     // [phone][cell] metres
    private var acc = DoubleArray(0)                        // accumulated log-likelihood per cell
    private var lastAccMs = 0L
    private var maxPair = 1.0
    private var knocks = 0
    private var voiceSeconds = 0

    /** Positions in metres. Rebuilds the grid (and forgets old evidence) only when the set of phones changes. */
    fun setPositions(p: Map<String, Pair<Double, Double>>) {
        if (p.size < 2) return
        val ls = p.keys.sorted()
        val xs = DoubleArray(ls.size) { p[ls[it]]!!.first }
        val ys = DoubleArray(ls.size) { p[ls[it]]!!.second }
        val sameLetters = ls == letters
        if (sameLetters && ls.indices.all { abs(xs[it] - px[it]) < 0.01 && abs(ys[it] - py[it]) < 0.01 }) return
        val inside = nx > 0 && xs.all { it > x0 && it < x0 + (nx - 1) * CELL } && ys.all { it > y0 && it < y0 + (ny - 1) * CELL }
        letters = ls; px = xs; py = ys
        maxPair = 0.5
        for (i in ls.indices) for (j in i + 1 until ls.size) maxPair = maxOf(maxPair, hypot(xs[i] - xs[j], ys[i] - ys[j]))
        if (!sameLetters || !inside) {
            val cx = xs.average(); val cy = ys.average()
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
            val half = maxOf(MIN_HALF_EXTENT, 2.5 * span)
            nx = (2 * half / CELL).toInt() + 1; ny = nx
            x0 = cx - half; y0 = cy - half
            acc = DoubleArray(nx * ny)
            knocks = 0; voiceSeconds = 0
            HLog.d("Locator: grid %dx%d cells of %.2f m around (%.1f, %.1f), phones %s".format(nx, ny, CELL, cx, cy, ls.joinToString()))
        }
        dist = Array(ls.size) { i -> DoubleArray(nx * ny) { c -> hypot(cellX(c) - xs[i], cellY(c) - ys[i]) } }
    }

    private fun cellX(c: Int) = x0 + (c % nx) * CELL
    private fun cellY(c: Int) = y0 + (c / nx) * CELL
    private fun idx(letter: String) = letters.indexOf(letter)

    fun reset() {
        letters = emptyList(); nx = 0; acc = DoubleArray(0); dist = emptyArray(); fix = null
        clock.clear(); axis.clear(); pool.clear(); voiceLatest.clear(); knocks = 0; voiceSeconds = 0
    }

    // ---- Clocks -----------------------------------------------------------------------------------

    private class ClockPoint(val aSample: Long, val offset: Double)
    private val clock = HashMap<String, ArrayList<ClockPoint>>()
    private val noClockWarned = HashSet<String>()

    /**
     * From one chirp round: every phone heard chirp j at sample t(hearer, j) on its own clock. For sensor i:
     *   t(i, j) − t(A, j) = offset_i + (d(i, j) − d(A, j)) · fs / c
     * because both heard the same emission, just from different distances. One estimate per chirp,
     * the median wins. Several rounds give the drift rate (crystals differ by parts per million, which
     * is a millimetre per second of listening: after minutes it matters).
     */
    fun updateClocks(heard: Map<String, Map<String, Long>>, dist: Map<String, Double>, letters: List<String>) {
        fun d(i: String, j: String): Double? = if (i == j) Ranging.SPEAKER_MIC_OFFSET_M else dist["$i$j"] ?: dist["$j$i"]
        val a = "A"
        val heardA = heard[a] ?: return
        val epoch = letters.firstNotNullOfOrNull { heardA[it] } ?: return
        for (i in letters) {
            if (i == a) continue
            val hi = heard[i] ?: continue
            val est = ArrayList<Double>()
            for (j in letters) {
                val ti = hi[j] ?: continue
                val ta = heardA[j] ?: continue
                val dij = d(i, j) ?: continue
                val daj = d(a, j) ?: continue
                est.add((ti - ta) - (dij - daj) * FS / C)
            }
            if (est.isEmpty()) continue
            est.sort()
            val off = est[est.size / 2]
            val spread = est.last() - est.first()
            val h = clock.getOrPut(i) { ArrayList() }
            val predicted = offsetAt(i, epoch)
            if (predicted != null && abs(predicted - off) > 0.5 * FS) {
                HLog.d("Clock: $i jumped by %.0f samples (audio restarted?), history cleared".format(off - predicted))
                h.clear()
            }
            h.add(ClockPoint(epoch, off))
            while (h.size > 12) h.removeAt(0)
            HLog.d("Clock: sensor $i = %+.1f samples vs A (spread %.1f, %d chirps, %d rounds, drift %.1f ppm)".format(off, spread, est.size, h.size, driftPpm(i)))
        }
    }

    private fun driftPpm(letter: String): Double {
        val h = clock[letter] ?: return 0.0
        if (h.size < 2) return 0.0
        val t0 = h.first().aSample
        val xs = h.map { (it.aSample - t0).toDouble() }; val ys = h.map { it.offset }
        val span = xs.max() - xs.min()
        if (span < 20 * FS) return 0.0
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (k in xs.indices) { sxy += (xs[k] - mx) * (ys[k] - my); sxx += (xs[k] - mx) * (xs[k] - mx) }
        val rate = sxy / sxx
        return if (abs(rate) < 200e-6) rate * 1e6 else 0.0
    }

    /** Offset of [letter]'s clock (its sample minus A's sample for the same instant) at A-sample [aSample]. */
    private fun offsetAt(letter: String, aSample: Long): Double? {
        if (letter == "A") return 0.0
        val h = clock[letter] ?: return null
        if (h.isEmpty()) return null
        val last = h.last()
        return last.offset + driftPpm(letter) * 1e-6 * (aSample - last.aSample)
    }

    /** A sensor's own sample index → the same instant on the commander's audio clock. */
    fun toA(letter: String, sample: Long): Double? {
        if (letter == "A") return sample.toDouble()
        val h = clock[letter] ?: return null
        if (h.isEmpty()) return null
        val rough = sample - h.last().offset
        val off = offsetAt(letter, rough.toLong()) ?: return null
        return sample - off
    }

    fun hasClock(letter: String) = letter == "A" || !clock[letter].isNullOrEmpty()

    // ---- Mic axes ---------------------------------------------------------------------------------

    private class Axis(val betaMap: Double, val headingAtCal: Float, val spreadDeg: Double)
    private val axis = HashMap<String, Axis>()

    /**
     * Which way each phone's mic line points on the map. In a chirp round every phone hears the others
     * from directions the map already knows; the inter-mic delay of each chirp gives the angle from the
     * phone's axis (either side of it), so the axis bearing is the one that agrees for all chirps heard.
     * [doa][hearer][chirping letter] = (mic delay in samples, hearer's compass heading at that moment).
     */
    fun calibrateAxes(doa: Map<String, Map<String, Pair<Float, Float>>>, dist: Map<String, Double>) {
        if (letters.isEmpty()) return
        fun d(i: String, j: String): Double? = dist["$i$j"] ?: dist["$j$i"]
        for ((hearer, byFrom) in doa) {
            val hi = idx(hearer); if (hi < 0) continue
            val cues = ArrayList<Pair<Double, Double>>()    // (true map bearing to the chirper, angle from axis)
            var heading = 0f
            for ((from, v) in byFrom) {
                val fi = idx(from); if (fi < 0 || from == hearer) continue
                val dd = d(hearer, from) ?: continue
                if (dd < NEAR_FIELD) continue
                cues.add(bearing(px[hi], py[hi], px[fi], py[fi]) to angleFromAxis(v.first))
                heading = v.second
            }
            if (cues.size < 2) { HLog.d("Axis: $hearer needs 2 chirps from ≥ %.1f m, has ${cues.size}".format(NEAR_FIELD)); continue }
            // Two candidate axis bearings per chirp (source on either side); pick the combination that agrees.
            var bestBeta = 0.0; var bestErr = 1e9
            for (sign0 in listOf(1.0, -1.0)) {
                val beta0 = norm(cues[0].first - sign0 * cues[0].second)
                var err = 0.0; var sx = cos(Math.toRadians(beta0)); var sy = sin(Math.toRadians(beta0))
                for (k in 1 until cues.size) {
                    val b1 = norm(cues[k].first - cues[k].second); val b2 = norm(cues[k].first + cues[k].second)
                    val pick = if (angDiff(b1, beta0) <= angDiff(b2, beta0)) b1 else b2
                    err += angDiff(pick, beta0)
                    sx += cos(Math.toRadians(pick)); sy += sin(Math.toRadians(pick))
                }
                if (err < bestErr) { bestErr = err; bestBeta = norm(Math.toDegrees(atan2(sy, sx))) }
            }
            val spread = bestErr / (cues.size - 1)
            if (spread > 25.0) { HLog.d("Axis: $hearer inconsistent (spread %.0f°), not calibrated".format(spread)); continue }
            axis[hearer] = Axis(bestBeta, heading, spread)
            HLog.d("Axis: $hearer mic line points to map bearing %.0f° (±%.0f°, from %d chirps, spacing %.3f m, heading %.0f°)".format(bestBeta, spread, cues.size, micSpacing, heading))
        }
    }

    /** Angle (degrees, 0..180) between the sound and the mic line, from the inter-mic delay in samples. */
    private fun angleFromAxis(delaySamples: Float): Double {
        val maxDelay = micSpacing * FS / C
        return Math.toDegrees(acos((delaySamples / maxDelay).coerceIn(-1.0, 1.0)))
    }

    /** Direction error of that angle when the delay is off by one sample; huge near the ends of the line. */
    private fun angleSigma(theta: Double, base: Double): Double {
        val maxDelay = micSpacing * FS / C
        val s = sin(Math.toRadians(theta)).coerceAtLeast(0.15)
        val perSample = Math.toDegrees(1.0 / (s * maxDelay))
        return sqrt(base * base + perSample * perSample)
    }

    // ---- Evidence pool ----------------------------------------------------------------------------

    private class Entry(val letter: String, val aSample: Double, val onset: Onset, val heading: Float)
    private val pool = ArrayList<Entry>()

    /** A phone's onsets for one second. Dropped until that phone's clock offset is known (needs a chirp round). */
    fun addReport(r: OnsetReport) {
        if (r.moving) return
        if (!hasClock(r.letter)) {
            if (noClockWarned.add(r.letter)) HLog.d("Locator: onsets from ${r.letter} ignored until a chirp round has measured its clock")
            return
        }
        for (o in r.onsets) {
            val a = toA(r.letter, o.sample) ?: continue
            pool.add(Entry(r.letter, a, o, r.heading))
        }
    }

    private class VoiceCue(val letter: String, val level: Float, val micDelay: Float?, val micQ: Float?, val heading: Float, val atMs: Long, var used: Boolean = false)
    private val voiceLatest = HashMap<String, VoiceCue>()

    /** One second of voice at a phone: gain-corrected loudness above the floor, plus the two-mic delay over that second. */
    fun addVoice(letter: String, level: Float, micDelay: Float?, micQ: Float?, heading: Float, moving: Boolean, nowMs: Long) {
        if (moving || level <= 0f) return
        voiceLatest[letter] = VoiceCue(letter, level, micDelay, micQ, heading, nowMs)
    }

    /**
     * Once a second. Groups onsets that are the same knock (within the time sound needs to cross the
     * array), scores each group on the grid, adds it to the running picture, and refreshes [fix].
     * [gain] = a phone's mic gain relative to the commander. [mirror] = the map is drawn mirrored.
     * Returns true when the fix changed.
     */
    fun process(nowASample: Long, nowMs: Long, mirror: Boolean, gain: (String) -> Float, useKnocks: Boolean, useVoice: Boolean): Boolean {
        if (letters.size < 2 || nx == 0) return false
        var changed = false
        if (useKnocks) {
            pool.sortBy { it.aSample }
            val span = maxPair / C * FS + 0.004 * FS
            val ripeBefore = nowASample - HOLD_S * FS
            val keep = ArrayList<Entry>()
            var i = 0
            while (i < pool.size) {
                val first = pool[i]
                if (first.aSample > ripeBefore) { keep.addAll(pool.subList(i, pool.size)); break }
                val members = arrayListOf(first)
                var j = i + 1
                while (j < pool.size && pool[j].aSample - first.aSample <= span) {
                    if (members.none { it.letter == pool[j].letter }) members.add(pool[j])
                    j++
                }
                i = j
                if (members.size < 2) continue
                val ll = DoubleArray(nx * ny)
                val cues = scoreKnock(members, ll, gain, mirror)
                if (cues == 0) continue
                accumulate(ll, nowMs)
                knocks++
                changed = true
                HLog.d("LOCATE knock #%d heard by %s: %s".format(knocks, members.joinToString(",") { it.letter }, describeKnock(members)))
            }
            pool.clear(); pool.addAll(keep)
        } else {
            pool.clear()
        }
        if (useVoice) {
            val fresh = voiceLatest.values.filter { !it.used && nowMs - it.atMs <= 1500 && idx(it.letter) >= 0 }
            if (fresh.size >= 2) {
                fresh.forEach { it.used = true }
                val ll = DoubleArray(nx * ny)
                val cues = scoreVoice(fresh, ll, mirror)
                if (cues > 0) {
                    accumulate(ll, nowMs)
                    voiceSeconds++
                    changed = true
                    HLog.d("LOCATE voice second #%d from %s (%d cues)".format(voiceSeconds, fresh.joinToString(",") { it.letter }, cues))
                }
            }
        }
        if (changed) fix = computeFix(nowMs)
        else fix?.let { if (nowMs - it.atMs > MAX_AGE_MS) { fix = null; changed = true } }
        return changed
    }

    private fun accumulate(ll: DoubleArray, nowMs: Long) {
        var mx = -1e300
        for (v in ll) if (v > mx) mx = v
        val k = if (lastAccMs == 0L) 1.0 else exp(-(nowMs - lastAccMs) / 1000.0 / DECAY_S)
        lastAccMs = nowMs
        for (c in acc.indices) acc[c] = acc[c] * k + (ll[c] - mx)
    }

    /** Heavy-tailed penalty: one wrong onset (an echo, a second knock) cannot drag the answer far. */
    private fun loss(r: Double) = -ln(1.0 + 0.5 * r * r)

    private fun scoreKnock(members: List<Entry>, ll: DoubleArray, gain: (String) -> Float, mirror: Boolean): Int {
        val n = members.size
        val pi = IntArray(n) { idx(members[it].letter) }
        if (pi.any { it < 0 }) return 0
        var cues = 0
        // 1. Timing, every pair.
        val pairs = ArrayList<Triple<Int, Int, Double>>()   // (i, j, sigma) with measured difference in dm
        val dm = ArrayList<Double>()
        for (a in 0 until n) for (b in a + 1 until n) {
            val felt = members[a].onset.felt || members[b].onset.felt
            pairs.add(Triple(pi[a], pi[b], if (felt) SIGMA_T * 3 else SIGMA_T))
            dm.add((members[a].aSample - members[b].aSample) / FS)
        }
        cues += pairs.size
        for (c in ll.indices) {
            var s = 0.0
            for (k in pairs.indices) {
                val (i, j, sig) = pairs[k]
                val dp = (dist[i][c] - dist[j][c]) / C
                s += loss((dm[k] - dp) / sig)
            }
            ll[c] += s
        }
        // 2. Loudness ratios (mic gain corrected, 1/r law, unknown source level cancels in the mean).
        val la = DoubleArray(n) { ln(maxOf(members[it].onset.peak / gain(members[it].letter), 1e-4f).toDouble()) }
        cues += 1
        for (c in ll.indices) {
            var mean = 0.0
            val e = DoubleArray(n) { la[it] + ln(maxOf(dist[pi[it]][c], 0.3)) }
            for (v in e) mean += v
            mean /= n
            var s = 0.0
            for (v in e) s += loss((v - mean) / SIGMA_A)
            ll[c] += WEIGHT_AMPLITUDE * s
        }
        // 3. Two-mic direction at each phone whose axis is known.
        for (m in members) {
            val dl = m.onset.micDelay ?: continue
            val q = m.onset.micQ ?: continue
            if (q < 0.4f) continue
            cues += addDirection(ll, idx(m.letter), m.letter, dl, m.heading, SIGMA_DOA, mirror)
        }
        return cues
    }

    private fun scoreVoice(cuesIn: List<VoiceCue>, ll: DoubleArray, mirror: Boolean): Int {
        val n = cuesIn.size
        val pi = IntArray(n) { idx(cuesIn[it].letter) }
        val la = DoubleArray(n) { ln(maxOf(cuesIn[it].level, 1e-5f).toDouble()) }
        var cues = 1
        for (c in ll.indices) {
            var mean = 0.0
            val e = DoubleArray(n) { la[it] + ln(maxOf(dist[pi[it]][c], 0.3)) }
            for (v in e) mean += v
            mean /= n
            var s = 0.0
            for (v in e) s += loss((v - mean) / SIGMA_A)
            ll[c] += WEIGHT_AMPLITUDE * s
        }
        for (v in cuesIn) {
            val dl = v.micDelay ?: continue
            val q = v.micQ ?: continue
            if (q < 0.3f) continue
            cues += addDirection(ll, idx(v.letter), v.letter, dl, v.heading, SIGMA_DOA_VOICE, mirror)
        }
        return cues
    }

    /** Adds one phone's two-candidate bearing cue. Returns 1 if the phone's axis was known, else 0. */
    private fun addDirection(ll: DoubleArray, i: Int, letter: String, delay: Float, headingNow: Float, base: Double, mirror: Boolean): Int {
        val ax = axis[letter] ?: return 0
        // The phone may have been turned since the chirps: follow its compass. Clockwise in the world is
        // clockwise on the map unless the map is drawn mirrored.
        var dh = (headingNow - ax.headingAtCal).toDouble()
        if (dh > 180) dh -= 360; if (dh < -180) dh += 360
        val beta = norm(ax.betaMap + (if (mirror) -dh else dh))
        val theta = angleFromAxis(delay)
        val sigma = angleSigma(theta, base)
        val b1 = norm(beta + theta); val b2 = norm(beta - theta)
        val x = px[i]; val y = py[i]
        for (c in ll.indices) {
            val r = dist[i][c]
            val pred = bearing(x, y, cellX(c), cellY(c))
            val res = minOf(angDiff(pred, b1), angDiff(pred, b2))
            val sig = if (r < NEAR_FIELD) sigma * 3 else sigma
            ll[c] += loss(res / sig)
        }
        return 1
    }

    private fun computeFix(nowMs: Long): Fix {
        var best = 0; var mx = -1e300
        for (c in acc.indices) if (acc[c] > mx) { mx = acc[c]; best = c }
        val bx = cellX(best); val by = cellY(best)
        var sumSq = 0.0; var count = 0; var edge = false
        val ai = idx("A")
        var sx = 0.0; var sy = 0.0
        var nearest = 1e9
        for (c in acc.indices) {
            if (acc[c] < mx - REGION_DROP) continue
            val cx = cellX(c); val cy = cellY(c)
            sumSq += (cx - bx) * (cx - bx) + (cy - by) * (cy - by); count++
            val ix = c % nx; val iy = c / nx
            if (ix == 0 || iy == 0 || ix == nx - 1 || iy == ny - 1) edge = true
            if (ai >= 0) {
                val b = Math.toRadians(bearing(px[ai], py[ai], cx, cy)); sx += cos(b); sy += sin(b)
                if (dist[ai][c] < nearest) nearest = dist[ai][c]
            }
        }
        if (nearest > 1e8) nearest = 0.0
        val radius = sqrt(sumSq / maxOf(count, 1))
        val spread = if (ai >= 0 && count > 0) Math.toDegrees(sqrt(-2 * ln((hypot(sx, sy) / count).coerceIn(1e-9, 1.0)))) else 180.0
        val toPhones = letters.indices.joinToString(" ") { "→%s %.1f m".format(letters[it], hypot(bx - px[it], by - py[it])) }
        val detail = "peak (%.1f, %.1f) region %d cells radius %.1f m nearest %.1f m spread ±%.0f° knocks %d voice %d%s | %s".format(bx, by, count, radius, nearest, spread, knocks, voiceSeconds, if (edge) " EDGE" else "", toPhones)
        HLog.d("LOCATE fix: $detail")
        return Fix(bx, by, radius, spread.coerceAtMost(180.0), nearest, knocks, voiceSeconds, nowMs, edge, detail)
    }

    private fun describeKnock(members: List<Entry>): String {
        val first = members.minByOrNull { it.aSample }!!
        return members.joinToString(" ") { m ->
            "%s:+%.1fms pk=%.3f%s%s".format(m.letter, (m.aSample - first.aSample) / FS * 1000, m.onset.peak,
                m.onset.micDelay?.let { " dl=%.1f/q%.2f".format(it, m.onset.micQ ?: 0f) } ?: "", if (m.onset.felt) " felt" else "")
        }
    }

    // ---- Small geometry helpers ----

    /** Map bearing (degrees clockwise from map-up) from (x1, y1) to (x2, y2). */
    private fun bearing(x1: Double, y1: Double, x2: Double, y2: Double) = norm(Math.toDegrees(atan2(x2 - x1, y2 - y1)))
    private fun norm(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
    private fun angDiff(a: Double, b: Double): Double { val d = abs(norm(a) - norm(b)); return if (d > 180) 360 - d else d }
}
