package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

class LoudnessLocatorTest {
    /** The team's room: phones in three corners of 2 m × 1.5 m. */
    private val room = mapOf("A" to (0.0 to 0.0), "B" to (2.0 to 0.0), "C" to (2.0 to 1.5))

    /** Six knocks at (sx, sy): peak = strength / r, each phone ± [noiseDb] (room echo), knock strength varies ×3. */
    private fun knocks(sx: Double, sy: Double, pos: Map<String, Pair<Double, Double>>, noiseDb: Double = 2.0, seed: Long = 1): List<Map<String, Float>> {
        val rnd = Random(seed)
        return (0 until 6).map {
            val strength = 0.01 * (1 + 2 * rnd.nextDouble())
            pos.mapValues { (_, q) ->
                val r = max(hypot(sx - q.first, sy - q.second), 0.15)
                (strength / r * 10.0.pow(rnd.nextGaussian() * noiseDb / 20)).toFloat()
            }
        }
    }

    private fun dist(p: LoudnessLocator.Point, x: Double, y: Double) = hypot(p.x - x, p.y - y)

    @Test fun knockNearAPhoneIsPlacedNextToIt() {
        val p = LoudnessLocator.locate(knocks(1.7, 0.3, room), room)!!
        assertTrue("off by ${dist(p, 1.7, 0.3)}", dist(p, 1.7, 0.3) < 0.4)
        assertEquals("B", p.nearest)
        assertEquals(6, p.knocks)
    }

    @Test fun knockInTheMiddleIsPlacedInTheMiddle() {
        val p = LoudnessLocator.locate(knocks(1.2, 0.7, room), room)!!
        assertTrue("off by ${dist(p, 1.2, 0.7)}", dist(p, 1.2, 0.7) < 0.35)
    }

    @Test fun oneEchoSpoiledPhoneDoesNotDragIt() {
        val k = knocks(0.3, 0.2, room).mapIndexed { i, m -> if (i < 2) m + ("C" to m.getValue("C") * 3.2f) else m }   // C +10 dB on 2 knocks
        val p = LoudnessLocator.locate(k, room)!!
        assertTrue("off by ${dist(p, 0.3, 0.2)}", dist(p, 0.3, 0.2) < 0.6)   // A's corner is far from both others: weakest spot
        assertEquals("A", p.nearest)
    }

    /** Two phones only give a circle of equally good spots: no point rather than a falsely precise one. */
    @Test fun twoPhonesGiveNoPoint() {
        val two = room.filterKeys { it != "C" }
        assertNull(LoudnessLocator.locate(knocks(1.0, 0.9, two), two))
        // Three placed phones but C missed every knock: the same.
        assertNull(LoudnessLocator.locate(knocks(1.0, 0.9, room).map { it - "C" }, room))
    }

    @Test fun nothingUsableWithoutPositionsOrWithOnePhone() {
        assertNull(LoudnessLocator.locate(knocks(1.0, 0.5, room), emptyMap()))
        assertNull(LoudnessLocator.locate(listOf(mapOf("A" to 0.05f)), room))
    }
}
