package com.hush

import com.hush.audio.KnockBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.log10

/** Laptop-only check of walk-to-triangulate: marks from standing still, the crossing, the mirror, loudness as tie-breaker. */
class WalkLocatorTest {

    private val sx = 4.0; private val sy = 6.0   // the noise, metres east/north of the start

    /** The own arrow a phone at (x, y) would show for the noise: true bearing, plus a mirror twin unless [resolved]. */
    private fun arrow(x: Double, y: Double, resolved: Boolean, knocks: Int = 5): KnockBearing.Estimate {
        val b = Crossing.bearing(x, y, sx, sy)
        // The mirror of a phone lying north-up is the bearing reflected in its axis: 360 − b.
        val twin = if (resolved) null else ((360.0 - b) % 360.0)
        return KnockBearing.Estimate(b.toFloat(), twin?.toFloat(), b.toFloat(), twin?.toFloat(), if (resolved) 0.9f else 0.5f, knocks, 0, resolved, if (resolved) 90f else 0f)
    }
    private fun level(x: Double, y: Double): Float = (40.0 - 20.0 * log10(hypot(sx - x, sy - y).coerceAtLeast(0.3))).toFloat()

    /** Stand at (x, y) for [seconds] with the arrow showing. */
    private fun stand(w: WalkLocator, t0: Long, x: Double, y: Double, resolved: Boolean, seconds: Int = 5): Pair<WalkLocator.State, Long> {
        var t = t0; var s: WalkLocator.State? = null
        repeat(seconds) { s = w.onSecond(t, x, y, false, arrow(x, y, resolved), level(x, y)); t += 1000 }
        return s!! to t
    }
    private fun walk(w: WalkLocator, t0: Long, x: Double, y: Double, seconds: Int = 4): Long {
        var t = t0
        repeat(seconds) { w.onSecond(t, x, y, true, null, null); t += 1000 }
        return t
    }

    @Test
    fun threeResolvedMarksFindTheNoise() {
        var resets = 0
        val w = WalkLocator { resets++ }
        var (s, t) = stand(w, 0, 0.0, 0.0, resolved = true)
        assertEquals(1, s.marks.size)
        assertEquals(WalkLocator.Prompt.WALK_SIDEWAYS, s.prompt)
        t = walk(w, t, 3.0, 0.0)
        val r = stand(w, t, 3.0, 0.0, resolved = true); s = r.first; t = r.second
        assertEquals(2, s.marks.size)
        assertNotNull("two resolved lines cross", s.fix)
        t = walk(w, t, 6.0, 1.0)
        s = stand(w, t, 6.0, 1.0, resolved = true).first
        assertEquals(3, s.marks.size)
        assertEquals(WalkLocator.Prompt.FIX, s.prompt)
        val f = s.fix!!
        assertTrue("fix (${f.x}, ${f.y})", hypot(f.x - sx, f.y - sy) < 0.3)
        assertTrue(s.distanceToFixM!! > 0)
        assertTrue("one reset per spot", resets >= 3)
    }

    @Test
    fun mirroredMarksNeedAThirdOrLoudness() {
        val w = WalkLocator()
        var (s, t) = stand(w, 0, 0.0, 0.0, resolved = false)
        t = walk(w, t, 3.0, 0.0)
        val r = stand(w, t, 3.0, 0.0, resolved = false); s = r.first; t = r.second
        // Two mirrored marks: four combinations; loudness (both marks about equally far from the noise here) may or may not decide.
        assertEquals(2, s.marks.size)
        t = walk(w, t, 1.5, 3.0)
        s = stand(w, t, 1.5, 3.0, resolved = false).first
        assertNotNull("third mark settles the mirror", s.fix)
        assertTrue(hypot(s.fix!!.x - sx, s.fix!!.y - sy) < 1.0)
    }

    @Test
    fun movingNeverMarksAndSameSpotRefreshes() {
        val w = WalkLocator()
        var t = 0L
        repeat(10) { assertEquals(0, w.onSecond(t, 0.0, 0.0, true, arrow(0.0, 0.0, true), 20f).marks.size); t += 1000 }
        // Standing still but only two seconds: HOLD_ON, no mark yet.
        var s = w.onSecond(t, 0.0, 0.0, false, arrow(0.0, 0.0, false), 20f); t += 1000
        s = w.onSecond(t, 0.0, 0.0, false, arrow(0.0, 0.0, false), 20f); t += 1000
        assertEquals(0, s.marks.size); assertEquals(WalkLocator.Prompt.HOLD_ON, s.prompt)
        s = w.onSecond(t, 0.0, 0.0, false, arrow(0.0, 0.0, false), 20f); t += 1000
        s = w.onSecond(t, 0.0, 0.0, false, arrow(0.0, 0.0, false), 20f); t += 1000
        assertEquals(1, s.marks.size)
        assertNotNull(s.marks[0].twinDeg)
        // Turning the phone at the same spot resolves the twin: the mark is refreshed, not duplicated.
        s = w.onSecond(t, 0.2, 0.1, false, arrow(0.2, 0.1, true), 20f)
        assertEquals(1, s.marks.size)
        assertNull(s.marks[0].twinDeg)
    }

    @Test
    fun warmerColderFollowsLoudness() {
        val w = WalkLocator()
        var (s, t) = stand(w, 0, 0.0, 0.0, resolved = true)
        assertNotNull(s.warmerDb); assertTrue(kotlin.math.abs(s.warmerDb!!) < 0.1f)
        t = walk(w, t, 2.0, 3.0)
        val closer = w.onSecond(t, 2.0, 3.0, false, null, level(2.0, 3.0))
        assertTrue("closer is warmer: ${closer.warmerDb}", closer.warmerDb!! > 3f)
    }

    @Test
    fun resetForgetsEverything() {
        val w = WalkLocator()
        val (s, _) = stand(w, 0, 0.0, 0.0, resolved = true)
        assertEquals(1, s.marks.size)
        w.reset()
        assertEquals(0, w.state.marks.size); assertNull(w.state.fix); assertEquals(WalkLocator.Prompt.LISTENING, w.state.prompt)
    }
}
