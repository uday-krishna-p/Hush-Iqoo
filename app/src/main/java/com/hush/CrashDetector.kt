package com.hush

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Decides, from raw accelerometer and gyroscope samples, whether the phone's OWNER has fallen, been struck or
 * been buried (docs/PLAN-crash.md, build A). Pure maths, no Android: the unit test feeds it synthetic traces.
 *
 * Five features, judged when the 5 s after an impact are over:
 *  1. free fall: |a| < 3 m/s² for ≥ 100 ms (gravity "disappears");
 *  2. impact: a peak ≥ 30 m/s² within 800 ms after the free fall, or ≥ 60 m/s² with no free fall (struck),
 *     or the sensor pinned at its maximum range for ≥ 10 ms (saturated: hit harder than it can measure);
 *  3. stillness: in the 5 s after the impact, ≥ 3 seconds with high-passed RMS < 0.25 m/s² (nobody got up);
 *  4. carried before: ≥ 3 of the 10 s before the free fall / impact were "moving" (the phone was on a person,
 *     not lying on the floor as a sensor). A resting phone only passes on the saturation rule;
 *  5. tumble / posture: peak turning rate from 1 s before the free fall to the impact, and the angle between
 *     the gravity direction 2 s before and 2–5 s after. Neither rejects on its own; together they decide a
 *     borderline impact (30–40 m/s²).
 *
 * Every candidate is reported, passed or not, with the reason, so the log tells us how to tune.
 */
class CrashDetector(private val onCandidate: (Candidate) -> Unit) {

    data class Candidate(
        val kind: String,            // FALL (free fall then impact) or IMPACT (struck / saturated, no free fall)
        val tMs: Long,               // when the impact peaked
        val peakMs2: Float,
        val freeFallMs: Int,
        val stillSeconds: Int,       // of the 5 s after the impact
        val carriedBefore: Boolean,
        val saturated: Boolean,
        val peakTurnDps: Float,
        val postureChangeDeg: Float,
        val passed: Boolean,
        val reason: String
    ) {
        override fun toString() = "kind=$kind fall=${freeFallMs}ms peak=%.1f still=$stillSeconds/5 carried=$carriedBefore sat=$saturated turn=%.0f°/s posture=%.0f° → %s%s".format(
            peakMs2, peakTurnDps, postureChangeDeg, if (passed) "DETECTED" else "rejected", if (reason.isEmpty()) "" else ": $reason")
    }

    companion object {
        const val FREE_FALL_MS2 = 3f
        const val FREE_FALL_MIN_MS = 100
        const val FALL_IMPACT_MS2 = 30f
        const val STRUCK_IMPACT_MS2 = 60f
        const val IMPACT_AFTER_FALL_MS = 800
        const val SATURATED_MIN_MS = 10
        const val MOVING_RMS = 0.25f      // same as AccelChannel.MOVING_RMS
        const val STILL_WINDOW_S = 5
        const val STILL_MIN_S = 3
        const val CARRIED_WINDOW_S = 10
        const val CARRIED_MIN_S = 3
        const val BORDERLINE_MS2 = 40f
        const val TURN_MIN_DPS = 150f
        const val POSTURE_MIN_DEG = 60f
        const val DEBOUNCE_MS = 30_000L
        private const val LOWPASS_ALPHA = 0.02f      // ≈ 0.25 s at 200 Hz, as in AccelChannel
        private const val HISTORY_MS = 3_000L        // raw samples kept for the "posture before" mean
        private const val POSTURE_AFTER_FROM_MS = 2_000L
        private const val POSTURE_AFTER_TO_MS = 5_000L
    }

    /** Full-scale of the accelerometer in m/s² (Android's configured range); 0 = unknown, saturation rule off. */
    @Volatile var maxRangeMs2 = 0f

    private class Sample(val t: Long, val x: Float, val y: Float, val z: Float)

    private val recent = ArrayDeque<Sample>()           // last HISTORY_MS of raw samples
    private val gyro = ArrayDeque<Pair<Long, Float>>()  // (t, °/s magnitude), last 1.5 s
    private var lowpass = 0f
    private var initialised = false

    // Per-second high-passed RMS, for "moving" seconds (stillness after, carried before).
    private var secondKey = Long.MIN_VALUE
    private var sumSq = 0.0
    private var count = 0
    private val secondRms = ArrayDeque<Pair<Long, Float>>()   // (secondKey, rms), last ~20 s

    // Free fall
    private var fallStart = -1L
    private var fallEnd = -1L       // set when |a| came back above the threshold
    private var lastFallLen = 0

    // Impact / pending decision
    private var saturatedRun = 0
    private var pending: Pending? = null
    private var lastDecisionMs = -1L

    private class Pending(val kind: String, var tMs: Long, var peak: Float, val freeFallMs: Int, var saturated: Boolean,
                          val carried: Boolean, val peakTurn: Float, val postureBefore: FloatArray?) {
        var afterX = 0.0; var afterY = 0.0; var afterZ = 0.0; var afterN = 0
    }

    fun gyro(tMs: Long, x: Float, y: Float, z: Float) {
        val dps = Math.toDegrees(sqrt(x * x + y * y + z * z).toDouble()).toFloat()
        synchronized(this) {
            gyro.addLast(tMs to dps)
            while (gyro.isNotEmpty() && tMs - gyro.first().first > 1500) gyro.removeFirst()
        }
    }

    fun accel(tMs: Long, x: Float, y: Float, z: Float) {
        val mag = sqrt(x * x + y * y + z * z)
        synchronized(this) {
            if (!initialised) { lowpass = mag; initialised = true }
            lowpass += LOWPASS_ALPHA * (mag - lowpass)
            val hp = abs(mag - lowpass)
            closeSecond(tMs / 1000)
            sumSq += hp * hp; count++

            recent.addLast(Sample(tMs, x, y, z))
            while (recent.isNotEmpty() && tMs - recent.first().t > HISTORY_MS) recent.removeFirst()

            // 1. free fall
            if (mag < FREE_FALL_MS2) {
                if (fallStart < 0) fallStart = tMs
            } else if (fallStart >= 0) {
                val len = (tMs - fallStart).toInt()
                if (len >= FREE_FALL_MIN_MS) { fallEnd = tMs; lastFallLen = len }
                fallStart = -1
            }

            // saturation: pinned at ≥ 97 % of full scale
            val sat = maxRangeMs2 > 0f && mag >= 0.97f * maxRangeMs2
            saturatedRun = if (sat) saturatedRun + 1 else 0
            val saturatedNow = sat && saturatedRun * 5 >= SATURATED_MIN_MS   // 5 ms per sample at 200 Hz

            val p = pending
            if (p == null) {
                val afterFall = fallEnd >= 0 && tMs - fallEnd <= IMPACT_AFTER_FALL_MS
                val impact = when {
                    saturatedNow -> "IMPACT"
                    afterFall && mag >= FALL_IMPACT_MS2 -> "FALL"
                    mag >= STRUCK_IMPACT_MS2 -> "IMPACT"
                    else -> null
                }
                if (impact != null && (lastDecisionMs < 0 || tMs - lastDecisionMs >= DEBOUNCE_MS)) {
                    val refT = if (afterFall) fallEnd - lastFallLen else tMs
                    pending = Pending(impact, tMs, mag, if (afterFall) lastFallLen else 0, saturatedNow,
                        carriedBefore(refT / 1000), peakTurnSince(refT - 1000), postureBefore(refT - 2000, refT))
                    fallEnd = -1
                }
            } else {
                // Impact window: the peak may still be growing for a few hundred ms.
                if (tMs - p.tMs <= 300 && mag > p.peak) { p.peak = mag; p.tMs = tMs }
                if (saturatedNow) p.saturated = true
                val dt = tMs - p.tMs
                if (dt in POSTURE_AFTER_FROM_MS..POSTURE_AFTER_TO_MS) { p.afterX += x; p.afterY += y; p.afterZ += z; p.afterN++ }
                // One extra second so that all STILL_WINDOW_S seconds after the impact are closed.
                if (dt > STILL_WINDOW_S * 1000L + 1000) decide(p, tMs)
            }
        }
    }

    private fun closeSecond(key: Long) {
        if (key == secondKey) return
        if (secondKey != Long.MIN_VALUE && count > 0) {
            secondRms.addLast(secondKey to sqrt(sumSq / count).toFloat())
            while (secondRms.size > 25) secondRms.removeFirst()
        }
        secondKey = key; sumSq = 0.0; count = 0
    }

    private fun carriedBefore(refSecond: Long): Boolean =
        secondRms.count { (k, r) -> k < refSecond && k >= refSecond - CARRIED_WINDOW_S && r > MOVING_RMS } >= CARRIED_MIN_S

    private fun peakTurnSince(fromMs: Long): Float = gyro.filter { it.first >= fromMs }.maxOfOrNull { it.second } ?: 0f

    private fun postureBefore(fromMs: Long, toMs: Long): FloatArray? {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (s in recent) if (s.t in fromMs until toMs) { sx += s.x; sy += s.y; sz += s.z; n++ }
        return if (n == 0) null else floatArrayOf((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())
    }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-3f || nb < 1e-3f) return 0f
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    private fun decide(p: Pending, nowMs: Long) {
        closeSecond(nowMs / 1000)
        val impactSecond = p.tMs / 1000
        val still = secondRms.count { (k, r) -> k > impactSecond && k <= impactSecond + STILL_WINDOW_S && r < MOVING_RMS }
        val posture = if (p.postureBefore != null && p.afterN > 0)
            angleDeg(p.postureBefore, floatArrayOf((p.afterX / p.afterN).toFloat(), (p.afterY / p.afterN).toFloat(), (p.afterZ / p.afterN).toFloat())) else 0f
        val tumble = p.peakTurn >= TURN_MIN_DPS || posture >= POSTURE_MIN_DEG
        val reason = when {
            still < STILL_MIN_S -> "moving again within ${STILL_WINDOW_S} s (${still} still seconds)"
            p.saturated -> ""                                    // hit harder than the sensor can measure: buried or crushed, carried or not
            !p.carried -> "not carried: the phone was lying still before"
            p.kind == "FALL" && p.peak < BORDERLINE_MS2 && !tumble -> "borderline impact (%.0f m/s²) with no tumble or posture change".format(p.peak)
            else -> ""
        }
        val c = Candidate(p.kind, p.tMs, p.peak, p.freeFallMs, still, p.carried, p.saturated, p.peakTurn, posture, reason.isEmpty(), reason)
        pending = null
        lastDecisionMs = nowMs
        onCandidate(c)
    }

    /** Forget everything (role change). */
    fun reset() = synchronized(this) {
        recent.clear(); gyro.clear(); secondRms.clear(); initialised = false
        secondKey = Long.MIN_VALUE; sumSq = 0.0; count = 0
        fallStart = -1; fallEnd = -1; lastFallLen = 0; saturatedRun = 0; pending = null; lastDecisionMs = -1L
    }
}
