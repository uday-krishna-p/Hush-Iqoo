package com.hush

import com.hush.audio.KnockBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnockDirectionTest {
    /** A knock at world bearing [bearing] as a phone lying with its top at [heading] hears it: the two-mic delay. */
    private fun dir(heading: Float, bearing: Double, q: Float = 0.8f): Closest.Dir {
        val rel = ((bearing - heading) % 360.0 + 360.0) % 360.0
        val theta = if (rel <= 180.0) rel else 360.0 - rel      // angle from the top edge, 0..180 (the mirror is lost)
        return Closest.Dir(KnockBearing.delayFromTheta(theta), q, heading, false)
    }

    private fun knock(t: Long, headings: Map<String, Float>, bearing: Double, winner: String = "A", lead: Float = 3f,
                      override: Map<String, Closest.Dir> = emptyMap()) = Closest.Knock(
        t, headings.mapValues { 0.05f }, winner, null, lead, dirs = headings.mapValues { (_, h) -> dir(h, bearing) } + override)

    @Test fun phonesAtDifferentAnglesAgreeOnOneDirection() {
        val kd = KnockDirection()
        val star = mapOf("A" to 0f, "B" to 120f, "C" to 240f)     // tops pointing three different ways
        for (i in 0 until 3) kd.add(knock(1000L + i * 1000, star, 70.0))
        val r = kd.result(4000)!!
        assertTrue("bearing ${r.bearingDeg}", KnockBearing.angDiff(r.bearingDeg.toDouble(), 70.0) <= 5.0)
        assertTrue(r.resolved)
        assertTrue("confidence ${r.confidence}", r.confidence > 0.5f)
        assertEquals(listOf("A", "B", "C"), r.phones)
    }

    /** 27 Sep 04:32 on the phones: parallel phones share their mirror, the confidence stayed at 0.44–0.48. */
    @Test fun parallelPhonesCannotSettleLeftFromRight() {
        val kd = KnockDirection()
        val parallel = mapOf("A" to 30f, "B" to 30f, "C" to 30f)
        for (i in 0 until 3) kd.add(knock(1000L + i * 1000, parallel, 100.0))
        val r = kd.result(4000)!!
        assertFalse(r.resolved)
        assertTrue(KnockBearing.angDiff(r.bearingDeg.toDouble(), 100.0) <= 5.0 || KnockBearing.angDiff(r.twinDeg!!.toDouble(), 100.0) <= 5.0)
    }

    /** 06:05 run: the phone right next to the knock scattered its delay; it wins the knock by ≥ 6 dB and counts 0.3. */
    @Test fun thePhoneNextToTheKnockCannotDragIt() {
        val kd = KnockDirection()
        val star = mapOf("A" to 0f, "B" to 120f, "C" to 240f)
        val wrongA = mapOf("A" to dir(0f, 250.0))                  // A (the winner, right beside the knock) reads nonsense
        for (i in 0 until 4) kd.add(knock(1000L + i * 1000, star, 70.0, winner = "A", lead = 15f, override = wrongA))
        val r = kd.result(5000)!!
        assertTrue("bearing ${r.bearingDeg}", KnockBearing.angDiff(r.bearingDeg.toDouble(), 70.0) <= 10.0)
    }

    @Test fun movingPhonesAndPoorReadingsDoNotVote() {
        val kd = KnockDirection()
        val k = Closest.Knock(1000, mapOf("A" to 0.05f), "A", null, null, all = mapOf("A" to 0.05f, "B" to 0.05f),
            dirs = mapOf("A" to dir(0f, 70.0, q = 0.2f), "B" to dir(90f, 70.0)))   // A: quality too low; B: moving (not in peaks)
        assertTrue(kd.add(k).isEmpty())
        assertNull(kd.result(1000))
    }

    @Test fun forgetsOldKnocks() {
        val kd = KnockDirection()
        val star = mapOf("A" to 0f, "B" to 120f, "C" to 240f)
        for (i in 0 until 3) kd.add(knock(1000L + i * 1000, star, 70.0))
        assertNull(kd.result(60_000))
    }
}
