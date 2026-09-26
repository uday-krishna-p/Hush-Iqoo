package com.hush

import com.hush.audio.KnockBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Laptop-only check of the two-mic knock arrow: the mirror is resolved by turning the phone. */
class KnockBearingTest {

    private fun angDiff(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 360f) % 360f); return if (d > 180f) 360f - d else d }

    /** A knock from world bearing [source] heard by a phone whose top points at [heading]: the delay its mics would measure. */
    private fun delayFor(source: Double, heading: Double): Float {
        var rel = ((source - heading) % 360.0 + 360.0) % 360.0   // clockwise from the phone's top
        if (rel > 180.0) rel = 360.0 - rel                       // the mics only know the angle from the axis
        return KnockBearing.delayFromTheta(rel)
    }

    @Test
    fun stillPhoneShowsBothCandidates() {
        KnockBearing.reset()
        var t = 1000L
        repeat(4) { KnockBearing.add(delayFor(40.0, 0.0), 0.9f, 15f, false, 0f, t); t += 700 }
        val e = KnockBearing.estimate(t, 0f)
        assertNotNull(e)
        assertFalse(e!!.resolved)
        assertNotNull(e.twinDeg)
        val a = e.screenDeg; val b = e.twinDeg!!
        assertTrue("candidates 40 and 320, got $a and $b", (angDiff(a, 40f) < 6f && angDiff(b, 320f) < 6f) || (angDiff(a, 320f) < 6f && angDiff(b, 40f) < 6f))
    }

    @Test
    fun turningResolvesTheMirror() {
        KnockBearing.reset()
        var t = 1000L
        repeat(4) { KnockBearing.add(delayFor(40.0, 0.0), 0.9f, 15f, false, 0f, t); t += 700 }
        repeat(4) { KnockBearing.add(delayFor(40.0, 90.0), 0.9f, 15f, false, 90f, t); t += 700 }
        val e = KnockBearing.estimate(t, 90f)
        assertNotNull(e)
        assertTrue("resolved after a 90° turn", e!!.resolved)
        assertNull(e.twinDeg)
        assertTrue("world bearing 40, got ${e.bearingDeg}", angDiff(e.bearingDeg, 40f) < 6f)
        assertTrue("on screen 310 (40 − 90), got ${e.screenDeg}", angDiff(e.screenDeg, 310f) < 6f)
        assertEquals(8, e.knocks)
        assertTrue(e.turnedDeg > 80f)
    }

    @Test
    fun knockStraightAheadNeedsNoTurn() {
        KnockBearing.reset()
        var t = 1000L
        repeat(3) { KnockBearing.add(delayFor(0.0, 0.0), 0.9f, 15f, false, 0f, t); t += 700 }
        val e = KnockBearing.estimate(t, 0f)
        assertNotNull(e)
        assertTrue(e!!.resolved)
        assertTrue(angDiff(e.screenDeg, 0f) < 6f)
    }

    @Test
    fun junkAndWeakDelaysAreIgnored() {
        KnockBearing.reset()
        assertFalse(KnockBearing.add(45f, 0.9f, 15f, false, 0f, 1000L))     // more than the mics allow
        assertFalse(KnockBearing.add(5f, 0.3f, 15f, false, 0f, 1000L))      // poor correlation
        assertFalse(KnockBearing.add(null, 0.9f, 15f, false, 0f, 1000L))
        assertNull(KnockBearing.estimate(2000L, 0f))
    }

    @Test
    fun oldKnocksExpire() {
        KnockBearing.reset()
        repeat(5) { KnockBearing.add(delayFor(90.0, 0.0), 0.9f, 15f, false, 0f, 1000L + it * 500) }
        assertNotNull(KnockBearing.estimate(5000L, 0f))
        assertNull(KnockBearing.estimate(1000L + KnockBearing.ACTIVE_MS + 5000L, 0f))
    }
}
