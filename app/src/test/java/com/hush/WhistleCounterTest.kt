package com.hush

import com.hush.audio.Tonality
import com.hush.audio.WhistleCounter
import com.hush.audio.WhistleCounter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Laptop-only check of the pressure-cooker whistle counter and the tonality measure it relies on. */
class WhistleCounterTest {

    private fun whistle(t: Long, score: Float = 0.5f, loud: Float = 9f, tonal: Float = 20f, hz: Float = 2000f) =
        WhistleCounter.Second(t, score, loud, tonal, hz, false)
    private fun frying(t: Long) = WhistleCounter.Second(t, 0.4f /* Hiss/Sizzle */, 5f, 2f, 3100f, false)
    private fun quiet(t: Long) = WhistleCounter.Second(t, 0f, 1f, 1.5f, 900f, false)

    @Test
    fun threeWhistlesReachTheTarget() {
        val c = WhistleCounter()
        c.target = 3
        var t = 0L
        val e1 = c.onSecond(whistle(t)); t += 1000
        assertNotNull(e1); assertEquals(Kind.WHISTLE, e1!!.kind); assertEquals(1, e1.count)
        // The whistle lasts three seconds: same whistle.
        assertNull(c.onSecond(whistle(t))); t += 1000
        assertNull(c.onSecond(whistle(t))); t += 1000
        // 40 s of cooking noise.
        repeat(40) { assertNull(c.onSecond(frying(t))); t += 1000 }
        val e2 = c.onSecond(whistle(t)); t += 1000
        assertEquals(2, e2!!.count); assertEquals(Kind.WHISTLE, e2.kind)
        repeat(40) { assertNull(c.onSecond(frying(t))); t += 1000 }
        val e3 = c.onSecond(whistle(t))
        assertEquals(Kind.DONE, e3!!.kind); assertEquals(3, e3.count)
        // A fourth whistle is still counted, but DONE is said once.
        t += 40_000
        val e4 = c.onSecond(whistle(t))
        assertEquals(Kind.WHISTLE, e4!!.kind); assertEquals(4, e4.count)
    }

    @Test
    fun doubleWhistleCountsOnce() {
        val c = WhistleCounter()
        var t = 0L
        assertNotNull(c.onSecond(whistle(t))); t += 1000
        assertNull(c.onSecond(quiet(t))); t += 1000
        assertNull(c.onSecond(quiet(t))); t += 1000
        assertNull(c.onSecond(whistle(t)))    // 3 s after the first: the same whistle
        assertEquals(1, c.count)
    }

    @Test
    fun fryingWaterAndTalkingDoNotCount() {
        val c = WhistleCounter()
        var t = 0L
        repeat(30) { assertNull(c.onSecond(frying(t))); t += 1000 }
        // Loud but not tonal (speech), and tonal but quiet (a distant TV jingle).
        assertNull(c.onSecond(WhistleCounter.Second(t, 0.1f, 8f, 3f, 1200f, false))); t += 1000
        assertNull(c.onSecond(WhistleCounter.Second(t, 0.1f, 1.5f, 30f, 1200f, false))); t += 1000
        // The model hears a whistle but nothing is loud or tonal: no.
        assertNull(c.onSecond(WhistleCounter.Second(t, 0.6f, 2f, 3f, 1200f, false)))
        assertEquals(0, c.count)
    }

    @Test
    fun strongToneCountsWithoutTheModel() {
        val c = WhistleCounter()
        val e = c.onSecond(WhistleCounter.Second(0, 0.05f, 9f, 25f, 1800f, false))
        assertNotNull(e); assertEquals(1, e!!.count)
        // The same tone far below the cooker band (a 100 Hz hum) does not.
        val c2 = WhistleCounter()
        assertNull(c2.onSecond(WhistleCounter.Second(0, 0.05f, 9f, 25f, 100f, false)))
    }

    @Test
    fun staleWarningAfterFifteenQuietMinutes() {
        val c = WhistleCounter()
        var t = 0L
        assertNotNull(c.onSecond(whistle(t))); t += 1000
        var stale: WhistleCounter.Event? = null
        repeat(16 * 60) { c.onSecond(quiet(t))?.let { stale = it }; t += 1000 }
        assertNotNull(stale); assertEquals(Kind.STALE, stale!!.kind)
        // Said once.
        repeat(120) { assertNull(c.onSecond(quiet(t))); t += 1000 }
    }

    @Test
    fun tonalityTellsAToneFromNoise() {
        val n = 16_000
        val tone = FloatArray(n) { (0.3 * sin(2.0 * PI * 2000.0 * it / 16_000)).toFloat() }
        val rnd = java.util.Random(1)
        val noise = FloatArray(n) { (0.3 * rnd.nextGaussian()).toFloat() }
        val t = Tonality.measure(tone, n)!!
        val z = Tonality.measure(noise, n)!!
        assertTrue("tone peak ${t.peakHz}", kotlin.math.abs(t.peakHz - 2000f) < 20f)
        assertTrue("tone ratio ${t.peakToMedian}", t.peakToMedian > 30f)
        assertTrue("noise ratio ${z.peakToMedian}", z.peakToMedian < 5f)
    }
}
