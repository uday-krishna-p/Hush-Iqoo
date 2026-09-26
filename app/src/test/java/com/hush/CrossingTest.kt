package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Laptop-only check of the bearing-line crossing (compass plan step 2). */
class CrossingTest {

    private val a = 0.0 to 0.0
    private val b = 4.0 to 0.0
    private val c = 2.0 to 3.0
    private val src = 6.0 to 5.0

    private fun line(letter: String, p: Pair<Double, Double>, vararg extra: Double) =
        Crossing.Line(letter, p.first, p.second, listOf(Crossing.bearing(p.first, p.second, src.first, src.second)) + extra.toList())

    @Test
    fun threeResolvedLinesMeetAtTheSource() {
        val r = Crossing.solve(listOf(line("A", a), line("B", b), line("C", c)), maxRange = 30.0)
        assertNotNull(r)
        assertTrue("got (${r!!.x}, ${r.y})", hypot(r.x - src.first, r.y - src.second) < 0.05)
        assertEquals(3, r.chosen.size)
        assertTrue(r.radius >= 0.3)
    }

    @Test
    fun aMirroredPhoneIsResolvedByTheOthers() {
        // B is unresolved: it also offers a wrong candidate (due north) whose line crosses A's and C's in front of
        // them, at different points. The true combination fits all three exactly and wins on residual.
        val r = Crossing.solve(listOf(line("A", a), line("B", b, 0.0), line("C", c)), maxRange = 30.0)
        assertNotNull(r)
        assertTrue(hypot(r!!.x - src.first, r.y - src.second) < 0.05)
        assertEquals(Crossing.bearing(b.first, b.second, src.first, src.second), r.chosen["B"]!!, 1e-6)
    }

    @Test
    fun twoLinesWithAMirrorStayAmbiguous() {
        // Two phones, one mirrored: two exact crossings, both in front. Must not pick one.
        val wrong = 360.0 - Crossing.bearing(b.first, b.second, src.first, src.second)   // mirror about B's north-south axis
        val r = Crossing.solve(listOf(line("A", a), line("B", b, wrong)), maxRange = 30.0)
        assertNull(r)
    }

    @Test
    fun nearlyParallelLinesGiveNoPoint() {
        val far = 0.0 to 500.0
        val la = Crossing.Line("A", a.first, a.second, listOf(Crossing.bearing(a.first, a.second, far.first, far.second)))
        val lb = Crossing.Line("B", b.first, b.second, listOf(Crossing.bearing(b.first, b.second, far.first, far.second)))
        assertNull(Crossing.solve(listOf(la, lb), maxRange = 30.0))
    }

    @Test
    fun aSourceBehindAPhoneIsRejected() {
        // A points away from the source: its line still "crosses" B's behind A. Rejected.
        val la = Crossing.Line("A", a.first, a.second, listOf(Crossing.bearing(a.first, a.second, src.first, src.second) + 180.0))
        assertNull(Crossing.solve(listOf(la, line("B", b)), maxRange = 30.0))
    }

    @Test
    fun beyondRangeIsRejected() {
        assertNull(Crossing.solve(listOf(line("A", a), line("B", b)), maxRange = 3.0))
    }
}
