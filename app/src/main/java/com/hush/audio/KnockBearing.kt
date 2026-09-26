package com.hush.audio

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp

/**
 * Which way the knocking is, from THIS phone's own two microphones. No chirps, no positions, no clock sync
 * (27 Sep, "compass" plan step 1, docs/PLAN-compass.md).
 *
 * A knock reaches the two mics a fraction of a millisecond apart. The delay gives the angle θ between the
 * knock and the phone's long axis: cos θ = delay / maxDelay, maxDelay = mic spacing × 48000 / 343 (≈ 22 samples
 * for 0.155 m). The two mics lie on one line, so the knock is at +θ or −θ from the phone's top: LEFT or RIGHT is
 * a mirror the mics cannot tell. Every knock therefore votes for two world bearings (compass heading ± θ) in a
 * decaying 5° histogram. While the phone lies still both peaks are equal and the screen shows both arrows. When
 * the rescuer turns the phone, the true bearing keeps piling up in the same place while its mirror swings by
 * twice the turn and smears out, so one peak wins ("confirmed").
 *
 * Measured before this was written (calibration logs, 26–27 Sep): the delays of real knocks cluster at 0 and at
 * about ±16–24 samples; the player phone hears its own knocks at +3.07 samples (mic 1 after mic 0), i.e. mic 0
 * is nearer the speaker at the bottom, so mic 1 is taken to be the TOP mic. Both constants can be changed at run
 * time (`--es mic1top false`, `--ef micspacing 0.14`) and are kept in preferences.
 */
object KnockBearing {
    private const val FS = 48_000.0
    private const val C = 343.0

    /** Distance between the two microphones, metres. A 16 cm phone with a mic at each end. */
    @Volatile var micSpacingM = 0.155
    /** True when recording channel 1 is the mic at the TOP of the phone (near the camera). */
    @Volatile var mic1IsTop = true

    /** Older knocks weigh less: the weight halves every TAU × ln 2 ≈ 10 s. */
    const val TAU_MS = 15_000.0
    /** The arrow shows while at least [MIN_KNOCKS] usable knocks were heard in the last [ACTIVE_MS]. */
    const val ACTIVE_MS = 15_000L
    const val MIN_KNOCKS = 3
    /** Two-mic correlation quality below this: the delay is noise. */
    const val MIN_Q = 0.5f
    /** A knock that also shook the phone came through the table (wrong delay): it counts this much. */
    const val FELT_WEIGHT = 0.3
    /** Delays beyond this × the end-fire delay are reflections, not a knock direction. */
    const val JUNK_FACTOR = 1.4
    /** Width of each knock's vote in the histogram, degrees. */
    const val SIGMA_DEG = 12.0
    /** The best peak must be this many times the second peak (≥ 30° away) to count as resolved. */
    const val RESOLVE_RATIO = 1.5
    private const val BINS = 72   // 5° each

    data class Estimate(
        val screenDeg: Float,        // clockwise from the phone's top: where to draw the arrow now
        val twinDeg: Float?,         // the mirror candidate on screen, null once resolved
        val bearingDeg: Float,       // world bearing (compass degrees) of the best candidate
        val twinBearingDeg: Float?,
        val confidence: Float,       // 0..1: share of the vote mass under the best peak (0.5 = mirrors still equal)
        val knocks: Int,             // usable knocks in the last ACTIVE_MS
        val felt: Int,               // of which came through the table
        val resolved: Boolean,
        val turnedDeg: Float         // how far the phone has turned across those knocks (0 = never turned)
    )

    private class Knock(val tMs: Long, val thetaDeg: Double, val headingDeg: Float, val weight: Double, val felt: Boolean)
    private val knocks = ArrayDeque<Knock>()
    private val lock = Any()

    fun maxDelaySamples(): Double = micSpacingM * FS / C

    /**
     * Angle from the phone's TOP, 0..180° (0 = beyond the top edge, 90 = beside the phone, 180 = beyond the
     * bottom edge), or null when the delay is impossible for two mics this far apart.
     * [delaySamples] is mic 1 minus mic 0, the convention of [Doa].
     */
    fun thetaFromDelay(delaySamples: Float): Double? {
        val md = maxDelaySamples()
        if (abs(delaySamples) > md * JUNK_FACTOR) return null
        // Sound from beyond the top edge reaches the top mic first. If mic 1 is the top mic that is a negative delay.
        val towardsTop = if (mic1IsTop) -delaySamples else delaySamples
        return Math.toDegrees(acos((towardsTop / md).coerceIn(-1.0, 1.0)))
    }

    /** The delay (mic 1 − mic 0, samples) a knock at [thetaDeg] from the top would give: the inverse, for tests. */
    fun delayFromTheta(thetaDeg: Double): Float {
        val towardsTop = cos(Math.toRadians(thetaDeg)) * maxDelaySamples()
        return (if (mic1IsTop) -towardsTop else towardsTop).toFloat()
    }

    /**
     * One knock heard now. Returns true if it was usable. Audio thread. [ratio] is the onset's peak over the
     * background: knuckle knocks measure ×11–28, room noises ×2–6, so a knock outweighs a noise 3–5× here.
     */
    fun add(delaySamples: Float?, quality: Float?, ratio: Float, felt: Boolean, headingDeg: Float, nowMs: Long): Boolean {
        if (delaySamples == null || quality == null || quality < MIN_Q) return false
        val theta = thetaFromDelay(delaySamples) ?: return false
        val weight = quality.toDouble() * (ratio / 10.0).coerceIn(0.3, 3.0) * (if (felt) FELT_WEIGHT else 1.0)
        synchronized(lock) {
            knocks.addLast(Knock(nowMs, theta, headingDeg, weight, felt))
            while (knocks.isNotEmpty() && nowMs - knocks.first().tMs > ACTIVE_MS) knocks.removeFirst()
        }
        return true
    }

    fun reset() = synchronized(lock) { knocks.clear() }

    /** The arrow now, or null while fewer than [MIN_KNOCKS] usable knocks were heard in the last [ACTIVE_MS]. */
    fun estimate(nowMs: Long, headingNow: Float): Estimate? {
        val recent: List<Knock> = synchronized(lock) {
            while (knocks.isNotEmpty() && nowMs - knocks.first().tMs > ACTIVE_MS) knocks.removeFirst()
            knocks.toList()
        }
        if (recent.size < MIN_KNOCKS) return null
        val hist = DoubleArray(BINS)
        for (k in recent) {
            val w = k.weight * exp(-(nowMs - k.tMs) / TAU_MS)
            vote(hist, k.headingDeg + k.thetaDeg, w)   // knock on the right of the phone...
            vote(hist, k.headingDeg - k.thetaDeg, w)   // ...or on the left: the mirror
        }
        val best = peakBin(hist, exclude = -1)
        val bearing = refine(hist, best)
        val second = peakBin(hist, exclude = best)
        val secondBearing = refine(hist, second)
        val resolved = hist[second] * RESOLVE_RATIO <= hist[best]
        // Mass within ±2σ of the best peak as a share of everything: 0.5 while the mirrors are equal, → 1 when one wins.
        var near = 0.0; var all = 0.0
        for (b in 0 until BINS) { all += hist[b]; if (angDiff(bearing, b * 360.0 / BINS) <= 2 * SIGMA_DEG) near += hist[b] }
        val confidence = (near / all.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        // How far the phone turned across the knocks (largest heading difference from the first knock).
        var turned = 0.0
        for (k in recent) turned = maxOf(turned, angDiff(recent.first().headingDeg.toDouble(), k.headingDeg.toDouble()))
        fun screen(b: Double) = ((b - headingNow + 720.0) % 360.0).toFloat()
        return Estimate(
            screenDeg = screen(bearing),
            twinDeg = if (resolved) null else screen(secondBearing),
            bearingDeg = bearing.toFloat(),
            twinBearingDeg = if (resolved) null else secondBearing.toFloat(),
            confidence = confidence.toFloat(),
            knocks = recent.size,
            felt = recent.count { it.felt },
            resolved = resolved,
            turnedDeg = turned.toFloat()
        )
    }

    private fun vote(hist: DoubleArray, bearingDeg: Double, w: Double) {
        val centre = ((bearingDeg % 360.0) + 360.0) % 360.0
        for (b in 0 until BINS) {
            val d = angDiff(centre, b * 360.0 / BINS)
            if (d <= 3 * SIGMA_DEG) hist[b] += w * exp(-d * d / (2 * SIGMA_DEG * SIGMA_DEG))
        }
    }

    /** Highest bin, ignoring bins within 30° of [exclude] (so the second peak is a different direction). */
    private fun peakBin(hist: DoubleArray, exclude: Int): Int {
        var best = -1
        for (b in 0 until BINS) {
            if (exclude >= 0 && angDiff(exclude * 360.0 / BINS, b * 360.0 / BINS) < 30.0) continue
            if (best < 0 || hist[b] > hist[best]) best = b
        }
        return if (best < 0) 0 else best
    }

    /** Peak bin → degrees, with a parabola through the neighbours for a smooth value. */
    private fun refine(hist: DoubleArray, b: Int): Double {
        val y0 = hist[(b + BINS - 1) % BINS]; val y1 = hist[b]; val y2 = hist[(b + 1) % BINS]
        val denom = y0 - 2 * y1 + y2
        val frac = if (denom < 0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        return (((b + frac) * 360.0 / BINS) % 360.0 + 360.0) % 360.0
    }

    private fun angDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }
}
