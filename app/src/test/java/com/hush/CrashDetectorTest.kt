package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Synthetic 200 Hz traces through [CrashDetector] (docs/PLAN-crash.md, build A). Real thresholds come from
 * recorded drops and staged falls; these tests pin the rules, not the numbers.
 */
class CrashDetectorTest {

    private val g = 9.81f
    private val rnd = java.util.Random(7)

    /** Builds a trace sample by sample. Time starts at 100 s so the "carried before" window is well inside history. */
    private inner class Trace {
        val out = ArrayList<CrashDetector.Candidate>()
        val det = CrashDetector { out.add(it) }.also { it.maxRangeMs2 = 78.4f }
        var t = 100_000L
        /** [seconds] of the phone lying still (gravity + sensor noise). */
        fun still(seconds: Float, gx: Float = 0f, gy: Float = 0f, gz: Float = g) = run(seconds) { det.accel(t, gx + n(0.01f), gy + n(0.01f), gz + n(0.01f)); det.gyro(t, n(0.01f), n(0.01f), n(0.01f)) }
        /** [seconds] of being carried: 0.5–1 m/s² of jitter, slow turning. */
        fun carried(seconds: Float) = run(seconds) { det.accel(t, n(0.8f), n(0.8f), g + n(0.8f)); det.gyro(t, n(0.3f), n(0.3f), n(0.3f)) }
        /** Free fall: nearly zero, optionally tumbling at [dps] degrees per second. */
        fun freeFall(ms: Int, dps: Float = 0f) = run(ms / 1000f) { det.accel(t, n(0.2f), n(0.2f), n(0.2f)); det.gyro(t, Math.toRadians(dps.toDouble()).toFloat(), 0f, 0f) }
        /** One sharp impact of [peak] m/s² lasting [ms]. */
        fun impact(peak: Float, ms: Int = 20) = run(ms / 1000f) { det.accel(t, 0f, 0f, peak); det.gyro(t, 0f, 0f, 0f) }
        private fun n(sd: Float) = (rnd.nextGaussian() * sd).toFloat()
        private fun run(seconds: Float, sample: () -> Unit) { val n = (seconds * 200).toInt(); repeat(n) { sample(); t += 5 } }
    }

    @Test
    fun carriedPhoneDroppedOneMetrePasses() {
        val tr = Trace()
        tr.carried(12f); tr.freeFall(320); tr.impact(45f); tr.still(7f)
        val c = tr.out.singleOrNull(); assertNotNull(c); c!!
        assertEquals("FALL", c.kind)
        assertTrue(c.toString(), c.passed)
        assertTrue(c.freeFallMs >= 300)
        assertTrue(c.carriedBefore)
        assertTrue(c.stillSeconds >= 3)
    }

    @Test
    fun tableSlapBesideARestingPhoneIsRejectedAsNotCarried() {
        val tr = Trace()
        tr.still(12f); tr.impact(65f); tr.still(7f)
        val c = tr.out.singleOrNull(); assertNotNull(c); c!!
        assertFalse(c.toString(), c.passed)
        assertFalse(c.carriedBefore)
        assertTrue(c.reason.contains("not carried"))
    }

    @Test
    fun sittingDownHardIsNoCandidateAtAll() {
        val tr = Trace()
        tr.carried(12f); tr.impact(25f); tr.carried(2f); tr.still(7f)
        assertNull(tr.out.firstOrNull()?.toString(), tr.out.firstOrNull())
    }

    @Test
    fun saturatedHitOnARestingPhonePasses() {
        val tr = Trace()
        tr.still(12f); tr.impact(78.4f, ms = 15); tr.still(7f)
        val c = tr.out.singleOrNull(); assertNotNull(c); c!!
        assertTrue(c.saturated)
        assertTrue(c.toString(), c.passed)
        assertFalse(c.carriedBefore)
    }

    @Test
    fun gettingUpAgainWithinFiveSecondsIsRejected() {
        val tr = Trace()
        tr.carried(12f); tr.freeFall(320); tr.impact(45f); tr.carried(7f)
        val c = tr.out.singleOrNull(); assertNotNull(c); c!!
        assertFalse(c.toString(), c.passed)
        assertTrue(c.reason.contains("moving again"))
    }

    @Test
    fun borderlineImpactNeedsATumbleOrAPostureChange() {
        val flat = Trace()
        flat.carried(12f); flat.freeFall(250); flat.impact(35f); flat.still(7f)
        val a = flat.out.singleOrNull(); assertNotNull(a); a!!
        assertFalse(a.toString(), a.passed)
        assertTrue(a.reason.contains("borderline"))

        val tumbling = Trace()
        tumbling.carried(12f); tumbling.freeFall(250, dps = 300f); tumbling.impact(35f); tumbling.still(7f)
        val b = tumbling.out.singleOrNull(); assertNotNull(b); b!!
        assertTrue(b.toString(), b.passed)
        assertTrue(b.peakTurnDps >= 250f)

        val turnedOver = Trace()
        turnedOver.carried(12f); turnedOver.freeFall(250); turnedOver.impact(35f); turnedOver.still(7f, gx = g, gz = 0f)
        val c = turnedOver.out.singleOrNull(); assertNotNull(c); c!!
        assertTrue(c.toString(), c.passed)
        assertTrue(c.postureChangeDeg >= 60f)
    }

    @Test
    fun secondImpactWithinTheDebounceIsIgnored() {
        val tr = Trace()
        tr.carried(12f); tr.freeFall(320); tr.impact(45f); tr.still(7f)
        tr.carried(3f); tr.freeFall(320); tr.impact(45f); tr.still(7f)
        assertEquals(1, tr.out.size)
    }

    @Test
    fun magnitudeHelperSanity() {
        assertEquals(5f, sqrt(3f * 3f + 4f * 4f), 1e-6f)
    }
}
