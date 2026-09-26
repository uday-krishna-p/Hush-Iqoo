package com.hush

import com.hush.audio.Chirp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Laptop-only check of the chirp matched filter: a 5 s stretch of noise with the chirp buried in it at a
 * known sample, arriving first weakly (the direct path) and then louder (a reflection), like the phones on a
 * table in the calibration room. The detector must find the direct arrival to within a sample, fast.
 */
class ChirpTest {
    private val fs = Chirp.SAMPLE_RATE
    private val rnd = java.util.Random(27_09_2026L)

    /** 5 s of noise at [noise] RMS (full scale 1.0) with copies of the chirp: (start sample, amplitude). */
    private fun audio(noise: Double, arrivals: List<Pair<Double, Double>>): ShortArray {
        val n = fs * 5
        val x = DoubleArray(n) { rnd.nextGaussian() * noise }
        val t = Chirp.template
        for ((start, amp) in arrivals) {
            // Fractional delay by linear interpolation of the template (good enough at 2–6 kHz for a test).
            val i0 = kotlin.math.floor(start).toInt(); val frac = start - i0
            for (k in -1 until t.size) {
                val a = if (k >= 0) t[k].toDouble() else 0.0
                val b = if (k + 1 < t.size) t[k + 1].toDouble() else 0.0
                val v = a * frac + b * (1 - frac)   // template value at (j − start) = k + 1 − frac
                val j = i0 + k + 1
                if (j in 0 until n) x[j] += amp * v
            }
        }
        return ShortArray(n) { (x[it] * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    @Test
    fun findsLoneChirpToTheSample() {
        val at = 123_456.0
        val a = audio(0.002, listOf(at to 0.05))
        val det = Chirp.detect(a, a.size)
        assertTrue("credible, ratio ${det.ratio}", det.ratio > 50f)
        assertEquals(at, det.fine, 1.0)
        assertEquals(0, det.firstArrivalShift)
    }

    @Test
    fun takesWeakDirectPathBeforeStrongReflection() {
        val direct = 200_000.4
        // Direct 0.5× the reflection, reflection 20 ms later (as replayed from the 26 Sep audio), plus a small echo.
        val a = audio(0.002, listOf(direct to 0.02, direct + 980 to 0.04, direct + 1500 to 0.015))
        val det = Chirp.detect(a, a.size)
        assertEquals(direct, det.fine, 1.0)
        assertTrue("shift ${det.firstArrivalShift}", det.firstArrivalShift in 975..985)
    }

    @Test
    fun ignoresNoiseBeforeAWeakChirp() {
        val at = 150_000.0
        val a = audio(0.004, listOf(at to 0.006))   // weak: ratio near the credibility threshold
        val det = Chirp.detect(a, a.size)
        assertEquals(at, det.fine, 2.0)
    }

    @Test
    fun twoMicDelayOfTheSameArrival() {
        val at = 180_000.0
        for (delay in listOf(-17.3, 0.0, 6.6, 13.4)) {
            // Direct path plus a stronger reflection 90 samples later on both mics. (With a reflection only 30
            // samples later the chosen carrier cycle wins by just 0.149: the 2–6 kHz chirp's envelope is as wide
            // as one 4 kHz cycle, which is why real two-mic delays are often ambiguous.)
            val m0 = audio(0.002, listOf(at to 0.03, at + 90 to 0.045))
            val m1 = audio(0.002, listOf(at + delay to 0.03, at + delay + 90 to 0.045))
            val det = Chirp.detect(m0, m0.size)
            val md = Chirp.micDelay(m0, m1, m0.size, det.offset)
            // The value is right, but even this clean copy wins over the neighbouring 4 kHz cycle by only ~0.1:
            // the 2–6 kHz chirp cannot say WHICH cycle on real, reverberant audio (Engine gates on margin 0.15).
            assertTrue("delay $delay: got $md", md != null && md.similarity > 0.95)
            assertEquals(delay, md!!.samples, 0.5)
        }
    }

    @Test
    fun fiveSecondSearchIsFast() {
        val a = audio(0.002, listOf(100_000.0 to 0.05))
        Chirp.detect(a, a.size)   // warm-up builds the FFT plan once
        val t0 = System.nanoTime()
        repeat(5) { Chirp.detect(a, a.size) }
        val ms = (System.nanoTime() - t0) / 5e6
        println("Chirp.detect over 5 s of audio: %.1f ms on this laptop".format(ms))
        assertTrue("took $ms ms", ms < 500)
    }
}
