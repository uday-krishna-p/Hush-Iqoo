package com.hush

import com.hush.audio.AccelChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** "Moving" judged on the median shake of the second (200 samples at 200 Hz), not the RMS. */
class AccelMovingTest {
    private val rnd = java.util.Random(1)
    private fun rest() = FloatArray(200) { abs(rnd.nextGaussian().toFloat()) * 0.007f }
    private fun rms(a: FloatArray) = sqrt(a.map { it * it }.average()).toFloat()
    private fun moving(a: FloatArray) = AccelChannel.medianOf(a, a.size) > AccelChannel.MOVING_MEDIAN

    /** 27 Sep 08:32, Sensor B on the knocked table: three knocks, jolts of ~4-6 m/s² ringing ~40 ms each. RMS said moving. */
    @Test fun threeTableKnocksAreNotMoving() {
        val a = rest()
        for (k in listOf(20, 90, 160)) for (i in 0 until 8) a[k + i] = 5f * (1f - i / 8f)
        assertTrue(rms(a) > AccelChannel.MOVING_RMS)   // the old rule threw these knocks away
        assertFalse(moving(a))
    }

    /** Carried: a 2 Hz gait swing of ±1.5 m/s² all second long. */
    @Test fun carriedPhoneIsMoving() {
        val a = FloatArray(200) { i -> abs(1.5f * sin(2 * PI * 2.0 * i / 200).toFloat()) + abs(rnd.nextGaussian().toFloat()) * 0.05f }
        assertTrue(moving(a))
    }

    @Test fun phoneAtRestIsNotMoving() = assertFalse(moving(rest()))
}
