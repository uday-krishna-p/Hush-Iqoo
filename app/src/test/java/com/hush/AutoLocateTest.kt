package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AutoLocateTest {
    private val tri = mapOf("6a46" to (0.0 to 0.0), "ef39" to (1.2 to 0.0), "991e" to (0.6 to -1.04))
    private fun dist(pos: Map<String, Pair<Double, Double>>) = mapOf(
        ("6a46" to "ef39") to hypot(pos.getValue("6a46").first - pos.getValue("ef39").first, pos.getValue("6a46").second - pos.getValue("ef39").second),
        ("6a46" to "991e") to hypot(pos.getValue("6a46").first - pos.getValue("991e").first, pos.getValue("6a46").second - pos.getValue("991e").second),
        ("ef39" to "991e") to hypot(pos.getValue("ef39").first - pos.getValue("991e").first, pos.getValue("ef39").second - pos.getValue("991e").second))

    /** 27 Sep 06:49 on the phones: AB 2.03, AC 2.02, BC 1.46 m for the 1.2 m triangle (A = ef39, B = 6a46, C = 991e). */
    @Test fun biasFromTheMeasuredRound() {
        val chirp = mapOf(("6a46" to "ef39") to 2.03, ("ef39" to "991e") to 2.02, ("6a46" to "991e") to 1.46)
        val b = AutoLocate.bias(chirp, dist(tri))!!
        assertEquals(0.70, b.getValue("ef39"), 0.03)
        assertEquals(0.13, b.getValue("6a46"), 0.03)
        assertEquals(0.13, b.getValue("991e"), 0.03)
    }

    @Test fun samePlacesGiveTheSamePositions() {
        val p = AutoLocate.place(dist(tri), tri)!!
        for ((k, v) in tri) assertTrue("$k", hypot(p.getValue(k).first - v.first, p.getValue(k).second - v.second) < 1e-6)
    }

    /** 991e carried 0.8 m further down: the other two stay, the triangle keeps its orientation. */
    @Test fun oneMovedPhoneIsFollowedAndTheOthersStay() {
        val moved = tri + ("991e" to (0.6 to -1.84))
        val p = AutoLocate.place(dist(moved), tri)!!
        // Two phones anchor it: the fit spreads the change a little, but the moved phone ends up near its new place.
        assertTrue(hypot(p.getValue("991e").first - 0.6, p.getValue("991e").second + 1.84) < 0.35)
        assertTrue(p.getValue("991e").second < -1.5)
        assertTrue(p.getValue("6a46").second > -0.3 && p.getValue("ef39").second > -0.3)   // not flipped upside down
    }

    @Test fun mirrorImageIsNotChosen() {
        val p = AutoLocate.place(dist(tri), tri)!!
        assertTrue(p.getValue("991e").second < 0)
    }

    /** No tape: the 06:49 round minus the built-in errors gives back the 1.2 m triangle. */
    @Test fun firstPlacementFromCorrectedChirps() {
        val raw = mapOf(("ef39" to "6a46") to 2.03, ("ef39" to "991e") to 2.02, ("6a46" to "991e") to 1.46)
        val corr = raw.mapValues { (p, d) -> d - AutoLocate.defaultBias(p.first) - AutoLocate.defaultBias(p.second) }
        val p = AutoLocate.first("ef39", "6a46", "991e", corr)!!
        assertEquals(0.0, p.getValue("ef39").first, 1e-9)
        assertEquals(1.18, p.getValue("6a46").first, 0.05)
        val side = { a: String, b: String -> hypot(p.getValue(a).first - p.getValue(b).first, p.getValue(a).second - p.getValue(b).second) }
        assertEquals(1.2, side("ef39", "991e"), 0.06); assertEquals(1.2, side("6a46", "991e"), 0.06)
        assertNull(AutoLocate.first("ef39", "6a46", "991e", mapOf(("ef39" to "6a46") to 1.0)))
    }

    @Test fun impossibleTriangleOrMissingPairGivesNothing() {
        assertNull(AutoLocate.place(mapOf(("6a46" to "ef39") to 1.0, ("6a46" to "991e") to 0.2, ("ef39" to "991e") to 0.2), tri))
        assertNull(AutoLocate.place(mapOf(("6a46" to "ef39") to 1.2), tri))
    }
}
