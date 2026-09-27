package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.max

class WarmthTest {
    /** Phone B is carried from 2.0 m to 0.5 m from the knock, A and C stay at 1.5 m and 2.5 m; knock strength varies ×3. */
    private fun knock(w: Warmth, t: Long, bDist: Double, strength: Float) {
        w.add(t, mapOf("A" to strength / 1.5f, "B" to strength / max(bDist, 0.15).toFloat(), "C" to strength / 2.5f), still = setOf("A", "C"))
    }

    @Test fun carriedTowardsTheKnockIsWarmer() {
        val w = Warmth()
        val strengths = listOf(0.01f, 0.03f, 0.015f, 0.02f, 0.01f, 0.03f)
        for (i in 0 until 4) knock(w, 1000L + i * 1000, 2.0, strengths[i])          // standing still, 2 m away
        assertEquals(Warmth.Trend.SAME, w.status("B", 4000)!!.trend)                // knock strength alone changes nothing
        knock(w, 5000, 1.0, strengths[4]); knock(w, 6000, 1.0, strengths[5])        // walked to 1 m: +6 dB
        val s = w.status("B", 6000)!!
        assertEquals(Warmth.Trend.WARMER, s.trend)
        assertEquals(6.0f, s.deltaDb, 0.5f)
        assertEquals(Warmth.Trend.SAME, w.status("A", 6000)!!.trend)                // the still phones do not change
    }

    @Test fun carriedAwayIsColderWithinTwoKnocks() {
        val w = Warmth()
        for (i in 0 until 4) knock(w, 1000L + i * 1000, 0.6, 0.02f)
        knock(w, 5000, 1.5, 0.02f)
        knock(w, 6000, 2.4, 0.02f)
        assertEquals(Warmth.Trend.COLDER, w.status("B", 6000)!!.trend)
    }

    @Test fun needsAnotherPhoneAndAFewKnocks() {
        val w = Warmth()
        w.add(1000, mapOf("B" to 0.05f)); w.add(2000, mapOf("B" to 0.05f)); w.add(3000, mapOf("B" to 0.05f))
        assertNull(w.status("B", 3000))                                             // nothing to compare with
        knock(w, 4000, 1.0, 0.02f); knock(w, 5000, 1.0, 0.02f)
        assertNull(w.status("B", 5000))                                             // two knocks: no trend yet
    }

    @Test fun oldKnocksAreForgotten() {
        val w = Warmth()
        for (i in 0 until 4) knock(w, 1000L + i * 1000, 1.0, 0.02f)
        assertNull(w.status("B", 60_000))
    }
}
