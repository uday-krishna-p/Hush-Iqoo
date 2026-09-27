package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosestTest {
    /** A knock 0.3 m from B, phones 2 m apart: B hears it ~16 dB louder; reports arrive in a scrambled order and up to 0.3 s off. */
    @Test fun nearestPhoneWins() {
        val c = Closest()
        for (i in 0 until 5) {
            val t = 1000L + i * 1000L
            c.add("A", t + 120, 0.012f, 9f)
            c.add("B", t - 80, 0.080f, 40f)
            c.add("C", t + 300, 0.010f, 8f)
        }
        c.tick(20_000)
        val s = c.summary(20_000)!!
        assertEquals("B", s.leader)
        assertEquals(5, s.wins)
        assertTrue(s.leadDb!! > 15f)
        assertEquals("A", s.runnerUp)
    }

    @Test fun onePhoneHearingItAloneStillCounts() {
        val c = Closest()
        for (i in 0 until 3) c.add("C", 1000L + i * 900L, 0.05f, 20f)
        c.tick(10_000)
        val s = c.summary(10_000)!!
        assertEquals("C", s.leader)
        assertNull(s.leadDb)
    }

    @Test fun quietOnsetsAreNotKnocks() {
        val c = Closest()
        for (i in 0 until 6) { c.add("A", 1000L + i * 700L, 0.002f, 4f); c.add("B", 1010L + i * 700L, 0.003f, 5f) }
        c.tick(20_000)
        assertNull(c.summary(20_000))
    }

    @Test fun notJudgedBeforeLateReportsCanArrive() {
        val c = Closest()
        c.add("A", 1000, 0.05f, 20f)
        assertTrue(c.tick(1500).isEmpty())
        c.add("B", 1100, 0.2f, 60f)   // arrives late but belongs to the same knock
        val k = c.tick(4000).single()
        assertEquals("B", k.winner)
    }

    /** 27 Sep 05:55 on the phones: clear knocks near C led by 8–20 dB, then knocks won by B by 0.7–2.2 dB. */
    @Test fun closeCallsDoNotCount() {
        val c = Closest()
        for (i in 0 until 4) { c.add("C", 1000L + i * 1000, 0.08f, 30f); c.add("A", 1000L + i * 1000, 0.02f, 9f) }
        for ((i, pair) in listOf(0.0422f to 0.0390f, 0.0125f to 0.0114f, 0.0066f to 0.0051f).withIndex()) {
            c.add("B", 6000L + i * 1000, pair.first, 12f); c.add("C", 6000L + i * 1000, pair.second, 11f)
        }
        val judged = c.tick(20_000)
        assertEquals(3, judged.count { !it.decisive })
        val s = c.summary(20_000)!!
        assertEquals("C", s.leader)
        assertEquals(4, s.wins)
        assertEquals(4, s.knocks)
    }

    @Test fun onlyTiesIsNoAnswer() {
        val c = Closest()
        for (i in 0 until 5) { c.add("A", 1000L + i * 1000, 0.050f, 20f); c.add("B", 1000L + i * 1000, 0.047f, 19f) }
        c.tick(20_000)
        assertNull(c.summary(20_000))
    }

    /** 27 Sep 06:00:38–06:01:01 on the phones: 12 clear knocks at C, then the knocker moved to A. With 20 s of wins
     *  the panel took 10 s to say A; with the last 6 clear knocks A leads after 3 knocks and is CLOSEST after 4. */
    @Test fun switchesWithinFourKnocksAfterALongRun() {
        val c = Closest()
        for (i in 0 until 12) { c.add("C", 1000L + i * 1000, 0.08f, 30f); c.add("A", 1000L + i * 1000, 0.01f, 9f) }
        fun knockAtA(i: Int): Closest.Summary {
            val t = 13_000L + i * 1000
            c.add("A", t, 0.08f, 30f); c.add("C", t, 0.01f, 9f)
            c.tick(t + Closest.CLOSE_AFTER_MS)
            return c.summary(t + Closest.CLOSE_AFTER_MS)!!
        }
        assertEquals("C", knockAtA(0).leader)
        assertEquals("C", knockAtA(1).leader)
        val third = knockAtA(2)
        assertEquals("A", third.leader)          // 3–3: the tie goes to the most recent winner (LEANING on the screen)
        assertEquals(3, third.wins)
        val fourth = knockAtA(3)
        assertEquals("A", fourth.leader)
        assertEquals(4, fourth.wins)             // 4 of 6: CLOSEST on the screen
        assertEquals(6, fourth.knocks)
    }

    @Test fun oneOddKnockDoesNotFlipIt() {
        val c = Closest()
        for (i in 0 until 6) { c.add("B", 1000L + i * 1000, 0.08f, 30f); c.add("A", 1000L + i * 1000, 0.01f, 9f) }
        c.add("A", 7000, 0.08f, 30f); c.add("B", 7000, 0.01f, 9f)
        c.tick(10_000)
        val s = c.summary(10_000)!!
        assertEquals("B", s.leader)
        assertEquals(5, s.wins)
    }

    @Test fun followsTheKnockerWhenOldKnocksExpire() {
        val c = Closest()
        for (i in 0 until 5) { c.add("A", 1000L + i * 1000, 0.08f, 30f); c.add("B", 1000L + i * 1000, 0.01f, 9f) }
        c.tick(8000)
        assertEquals("A", c.summary(8000)!!.leader)
        for (i in 0 until 4) { c.add("A", 30_000L + i * 1000, 0.01f, 9f); c.add("B", 30_000L + i * 1000, 0.08f, 30f) }
        c.tick(36_000)
        assertEquals("B", c.summary(36_000)!!.leader)
    }
}
