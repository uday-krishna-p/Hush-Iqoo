package com.hush

import com.hush.model.Onset
import com.hush.model.OnsetReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Laptop-only check of the source locator with synthetic phones: three phones with different audio
 * clocks and mic orientations, a chirp round to learn the clocks and axes, then knocks from a known
 * spot. The solver must find that spot. No Android involved.
 */
class LocatorTest {

    private val fs = Locator.FS
    private val c = Locator.C
    private val spacing = 0.10
    private val maxDelay = spacing * fs / c

    private val pos = mapOf("A" to (0.8 to 0.5), "B" to (-1.0 to -0.6), "C" to (1.2 to -0.7))
    private val offset = mapOf("A" to 0.0, "B" to 123456.3, "C" to -98765.7)   // samples, own clock minus A's clock
    private val axisDeg = mapOf("A" to 30.0, "B" to 200.0, "C" to 95.0)         // map bearing of each phone's mic line

    private fun d(i: String, j: String): Double {
        if (i == j) return Ranging.SPEAKER_MIC_OFFSET_M
        val (x1, y1) = pos[i]!!; val (x2, y2) = pos[j]!!
        return hypot(x1 - x2, y1 - y2)
    }
    private fun bearing(x1: Double, y1: Double, x2: Double, y2: Double) = (Math.toDegrees(atan2(x2 - x1, y2 - y1)) + 360.0) % 360.0
    private fun micDelayFor(letter: String, sx: Double, sy: Double): Float {
        val (x, y) = pos[letter]!!
        val b = bearing(x, y, sx, sy)
        return (maxDelay * cos(Math.toRadians(b - axisDeg[letter]!!))).toFloat()
    }

    private fun build(): Locator {
        val loc = Locator()
        loc.micSpacing = spacing
        val letters = listOf("A", "B", "C")
        // Chirp round: A emits at A-sample 1,000,000, then B, then C 1.8 s apart.
        val emit = mapOf("A" to 1_000_000.0, "B" to 1_086_400.0, "C" to 1_172_800.0)
        val heard = HashMap<String, HashMap<String, Long>>()
        for (i in letters) for (j in letters) {
            heard.getOrPut(i) { HashMap() }[j] = Math.round(emit[j]!! + d(i, j) / c * fs + offset[i]!!)
        }
        val dist = HashMap<String, Double>()
        for (a in letters.indices) for (b in a + 1 until letters.size) dist["${letters[a]}${letters[b]}"] = d(letters[a], letters[b])
        loc.updateClocks(heard, dist, letters)
        loc.setPositions(pos.mapValues { it.value.first to it.value.second })
        val doa = HashMap<String, HashMap<String, Pair<Float, Float>>>()
        for (i in letters) for (j in letters) if (i != j) {
            val (x, y) = pos[j]!!
            doa.getOrPut(i) { HashMap() }[j] = micDelayFor(i, x, y) to 0f
        }
        loc.calibrateAxes(doa, dist)
        return loc
    }

    private fun knock(loc: Locator, sx: Double, sy: Double, aTime: Double, noise: Double) {
        for ((letter, p) in pos) {
            val r = hypot(sx - p.first, sy - p.second)
            val arrival = aTime + r / c * fs + offset[letter]!! + (Math.random() - 0.5) * 2 * noise
            val onset = Onset(Math.round(arrival), (0.5 / r).coerceAtMost(1.0).toFloat(), 40f,
                micDelayFor(letter, sx, sy) + (Math.random() - 0.5).toFloat(), 0.8f, false)
            loc.addReport(OnsetReport(letter, 0f, false, listOf(onset)))
        }
    }

    @Test
    fun clockOffsetsAreRecoveredFromTheChirps() {
        val loc = build()
        assertEquals(2_000_000.0, loc.toA("B", Math.round(2_000_000.0 + offset["B"]!!))!!, 1.0)
        assertEquals(3_000_000.0, loc.toA("C", Math.round(3_000_000.0 + offset["C"]!!))!!, 1.0)
    }

    @Test
    fun knocksNearTheArrayAreLocatedWithinHalfAMetre() {
        val loc = build()
        val sx = 2.5; val sy = 3.0
        var t = 2_000_000.0
        repeat(5) { knock(loc, sx, sy, t, 10.0); t += fs }
        val changed = loc.process((t + 5 * fs).toLong(), 60_000L, false, { 1f }, useKnocks = true, useVoice = false)
        assertTrue(changed)
        val fix = loc.fix
        assertNotNull(fix)
        val err = hypot(fix!!.x - sx, fix.y - sy)
        println("fix (${fix.x}, ${fix.y}) vs true ($sx, $sy): error %.2f m, radius %.2f, knocks %d".format(err, fix.radius, fix.knocks))
        println("detail: " + fix.detail)
        assertTrue("error $err m", err <= 0.5)
        assertEquals(5, fix.knocks)
    }

    @Test
    fun aFarKnockGetsTheRightDirectionFromTheCommander() {
        val loc = build()
        val sx = -6.0; val sy = 9.0
        var t = 2_000_000.0
        repeat(4) { knock(loc, sx, sy, t, 10.0); t += fs }
        loc.process((t + 5 * fs).toLong(), 60_000L, false, { 1f }, useKnocks = true, useVoice = false)
        val fix = loc.fix!!
        val (ax, ay) = pos["A"]!!
        val trueBearing = bearing(ax, ay, sx, sy)
        val gotBearing = bearing(ax, ay, fix.x, fix.y)
        var diff = Math.abs(trueBearing - gotBearing); if (diff > 180) diff = 360 - diff
        println("far fix (${fix.x}, ${fix.y}) bearing %.0f vs true %.0f, spread ±%.0f, edge=%b".format(gotBearing, trueBearing, fix.bearingSpreadDeg, fix.edge))
        assertTrue("bearing off by $diff°", diff <= 10.0)
    }

    @Test
    fun theSourceIsNotSimplyTheNearestSensor() {
        // Source sits 0.3 m from B but the fix must land on the source, not on B's dot.
        val loc = build()
        val sx = -1.3; val sy = -0.9
        var t = 2_000_000.0
        repeat(4) { knock(loc, sx, sy, t, 5.0); t += fs }
        loc.process((t + 5 * fs).toLong(), 60_000L, false, { 1f }, useKnocks = true, useVoice = false)
        val fix = loc.fix!!
        val err = hypot(fix.x - sx, fix.y - sy)
        println("near-B fix (${fix.x}, ${fix.y}) error %.2f m".format(err))
        assertTrue("error $err m", err <= 0.45)
    }
}
