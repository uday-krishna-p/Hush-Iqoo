package com.hush

import com.hush.audio.Doa
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Laptop-only check of the two-mic delay on a continuous hum (HOME → FIND, HUM mode) and on a click. */
class DoaTest {

    private val fs = 48_000
    private val n = 48_000

    /** A hum: 100 Hz with harmonics and some noise, mic 1 hearing it [delay] samples after mic 0. */
    private fun hum(delay: Int): Pair<ShortArray, ShortArray> {
        val rnd = java.util.Random(7)
        fun s(i: Int): Double {
            var v = 0.0
            for (h in 1..6) v += sin(2.0 * PI * 100.0 * h * i / fs) / h
            return v
        }
        val x0 = ShortArray(n) { ((s(it) * 3000) + rnd.nextGaussian() * 300).toInt().toShort() }
        val x1 = ShortArray(n) { ((s(it - delay) * 3000) + rnd.nextGaussian() * 300).toInt().toShort() }
        return x0 to x1
    }

    @Test
    fun humDelayIsFoundInTheLowBand() {
        val (x0, x1) = hum(10)
        val d = Doa.delayBandPassed(x0, x1, n, fs, 60f, 1500f)
        assertNotNull(d)
        assertTrue("delay ${d!!.delay}", abs(d.delay - 10f) < 1.0f)
        assertTrue("quality ${d.quality}", d.quality > 0.8f)
        val (y0, y1) = hum(-7)
        val e = Doa.delayBandPassed(y0, y1, n, fs, 60f, 1500f)!!
        assertTrue("delay ${e.delay}", abs(e.delay + 7f) < 1.0f)
    }

    @Test
    fun theVoiceBandDropsTheHum() {
        // 100 Hz and its first harmonics sit below 300 Hz: the voice band keeps little of it, so the match is poorer.
        val (x0, x1) = hum(10)
        val low = Doa.delayBandPassed(x0, x1, n, fs, 60f, 1500f)!!
        val voice = Doa.delayBandPassed(x0, x1, n, fs, 300f, 3000f)
        assertTrue(voice == null || voice.quality < low.quality)
    }

    @Test
    fun aClickIsTimedToTheSample() {
        val x0 = ShortArray(4000); val x1 = ShortArray(4000)
        for (i in 0 until 40) { x0[1000 + i] = (8000.0 * sin(2.0 * PI * i / 40)).toInt().toShort(); x1[1000 + 5 + i] = x0[1000 + i] }
        val d = Doa.delay(x0, x1, 4000)!!
        assertTrue("delay ${d.delay}", abs(d.delay - 5f) < 0.3f)
    }
}
