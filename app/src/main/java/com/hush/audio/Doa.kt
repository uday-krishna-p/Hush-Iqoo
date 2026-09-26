package com.hush.audio

import kotlin.math.sqrt

/**
 * Direction of arrival from the phone's two microphones. Sound reaches the two mics a fraction of a
 * millisecond apart depending on the direction it comes from; the delay is found by sliding mic 1's
 * signal against mic 0's and looking for the best match (cross-correlation), then refined to a fraction
 * of a sample with a parabola through the three best points. Same convention as the chirp code:
 * delay = samples mic 1 hears the sound AFTER mic 0 (negative = mic 1 heard it first).
 */
object Doa {
    /** Largest delay searched. 48 samples at 48 kHz = 0.34 m of mic spacing: more than any phone has. */
    const val MAX_LAG = 48

    data class Result(val delay: Float, val quality: Float)

    /**
     * [x0] and [x1] are the same stretch of time from mic 0 and mic 1. Returns null when the best match
     * sits at the edge of the search range (not a real peak) or the signal is empty.
     * [quality] is the normalised correlation at the peak: 1 = identical waveforms, < 0.4 = do not trust.
     */
    fun delay(x0: ShortArray, x1: ShortArray, n: Int): Result? {
        if (n <= 2 * MAX_LAG + 16) return null
        var e0 = 0.0; var e1 = 0.0
        for (i in 0 until n) { e0 += x0[i].toDouble() * x0[i]; e1 += x1[i].toDouble() * x1[i] }
        if (e0 <= 0.0 || e1 <= 0.0) return null
        val norm = sqrt(e0 * e1)
        val c = DoubleArray(2 * MAX_LAG + 1)
        var best = 0; var bestVal = -2.0
        for (lag in -MAX_LAG..MAX_LAG) {
            var s = 0.0
            val from = maxOf(0, -lag); val to = minOf(n, n - lag)
            var i = from
            while (i < to) { s += x0[i].toDouble() * x1[i + lag]; i++ }
            val v = s / norm
            c[lag + MAX_LAG] = v
            if (v > bestVal) { bestVal = v; best = lag + MAX_LAG }
        }
        if (best == 0 || best == c.size - 1) return null
        val y0 = c[best - 1]; val y1 = c[best]; val y2 = c[best + 1]
        val denom = y0 - 2 * y1 + y2
        val fine = if (denom < 0) best + 0.5 * (y0 - y2) / denom else best.toDouble()
        return Result((fine - MAX_LAG).toFloat(), bestVal.toFloat())
    }

    /** Same, on band-passed copies (for voice: keeps the speech band, drops rumble that smears the peak). */
    fun delayBandPassed(x0: ShortArray, x1: ShortArray, n: Int, sampleRate: Int): Result? {
        val f0 = FloatArray(n); val f1 = FloatArray(n)
        BandPass(sampleRate, 300f, 3000f).process(x0, n, f0)
        BandPass(sampleRate, 300f, 3000f).process(x1, n, f1)
        val s0 = ShortArray(n) { (f0[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val s1 = ShortArray(n) { (f1[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        return delay(s0, s1, n)
    }
}
