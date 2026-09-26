package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/** Laptop-only check of the GPS layout rules (compass plan step 3). */
class GpsLayoutTest {

    private val lat0 = 17.4; private val lon0 = 78.5   // Hyderabad
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

    /** Three fixes for a phone [east]/[north] metres from the commander, with a little jitter. */
    private fun fixes(east: Double, north: Double, acc: Float, now: Long, ageMs: Long = 0L) = listOf(-1.0, 0.0, 1.0).map { j ->
        GpsLayout.Sample(lat0 + (north + j * 0.5) / 110_574.0, lon0 + (east + j * 0.5) / mPerDegLon, acc, now - ageMs - 1000L * (j.toLong() + 1))
    }

    @Test
    fun wideTriangleIsPlacedInMetresEastNorthOfA() {
        val now = 100_000L
        val samples = mapOf("A" to fixes(0.0, 0.0, 4f, now), "B" to fixes(20.0, 0.0, 4f, now), "C" to fixes(0.0, 15.0, 5f, now))
        val (r, why) = GpsLayout.solve(samples, listOf("A", "B", "C"), now)
        assertNotNull(why, r)
        val b = r!!.positions["B"]!!; val c = r.positions["C"]!!
        assertTrue("B at $b", abs(b.first - 20.0) < 0.6 && abs(b.second) < 0.6)
        assertTrue("C at $c", abs(c.first) < 0.6 && abs(c.second - 15.0) < 0.6)
        assertEquals(0.0, r.positions["A"]!!.first, 1e-9)
        assertEquals(5.0, r.worstAccM, 1e-9)
        assertTrue(r.minPairM > 14.0)
    }

    @Test
    fun aTableTriangleIsRefused() {
        val now = 100_000L
        val samples = mapOf("A" to fixes(0.0, 0.0, 4f, now), "B" to fixes(2.0, 0.0, 4f, now), "C" to fixes(0.0, 1.5, 4f, now))
        val (r, why) = GpsLayout.solve(samples, listOf("A", "B", "C"), now)
        assertNull(why, r)
        assertTrue(why, why.contains("too close"))
    }

    @Test
    fun aPhoneWithoutFreshFixesBlocksTheLayoutAndIsNamed() {
        val now = 100_000L
        val samples = mapOf("A" to fixes(0.0, 0.0, 4f, now), "B" to fixes(20.0, 0.0, 4f, now, ageMs = 90_000L))
        val (r, why) = GpsLayout.solve(samples, listOf("A", "B"), now)
        assertNull(r)
        assertTrue(why, why.startsWith("B has 0 GPS fixes"))
    }

    @Test
    fun poorAccuracyIsRefused() {
        val now = 100_000L
        val samples = mapOf("A" to fixes(0.0, 0.0, 4f, now), "B" to fixes(20.0, 0.0, 35f, now))
        val (r, why) = GpsLayout.solve(samples, listOf("A", "B"), now)
        assertNull(r)
        assertTrue(why, why.contains("accuracy"))
    }
}
