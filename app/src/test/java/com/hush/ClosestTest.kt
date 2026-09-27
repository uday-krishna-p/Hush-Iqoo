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
        assertEquals(Closest.WINDOW_KNOCKS, s.wins)   // all of the last 4 clear knocks
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

    /** HIGH sensitivity: knocks at ×5 count (NORMAL needs one phone at ×8). */
    @Test fun highSensitivityCountsQuieterKnocks() {
        for ((min, counted) in listOf(Closest.MIN_LOUD_RATIO to false, 4f to true)) {
            val c = Closest().apply { minLoudRatio = min }
            for (i in 0 until 3) { c.add("A", 1000L + i * 1000, 0.01f, 5f); c.add("B", 1000L + i * 1000, 0.003f, 3f) }
            c.tick(10_000)
            assertEquals(counted, c.summary(10_000) != null)
        }
    }

    /** Voice (Engine.closestVoice): phones' 1 s windows are not aligned (here 400 ms apart), so the match is 600 ms.
     *  Only a phone that hears speech gives a ratio; the others' levels still take part. */
    @Test fun voiceSecondsFromUnalignedPhonesMatch() {
        val c = Closest(matchMs = 600L, readyMarginMs = 600L).apply { minLoudRatio = 3f }
        for (i in 0 until 4) {
            val t = 1000L + i * 1000
            c.add("A", t, 0.0020f, 0f)          // A's classifier did not call it speech: level only
            c.add("B", t + 400, 0.0080f, 6f)    // B hears the shout, 12 dB louder
            c.add("C", t - 300, 0.0015f, 0f)
        }
        val judged = c.tick(10_000, readyUntilMs = 10_000)
        assertEquals(4, judged.size)
        assertEquals("B", c.summary(10_000)!!.leader)
        // A second where no phone heard speech (all ratios 0) never counts.
        val quiet = Closest(matchMs = 600L, readyMarginMs = 600L).apply { minLoudRatio = 3f }
        for (i in 0 until 4) { quiet.add("A", 1000L + i * 1000, 0.01f, 0f); quiet.add("B", 1200L + i * 1000, 0.002f, 0f) }
        quiet.tick(10_000, readyUntilMs = 10_000)
        assertNull(quiet.summary(10_000))
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
     *  the panel took 10 s to say A; with the last 6 clear knocks A led after 3 knocks. 09:35: last 4, A leads after 2. */
    @Test fun switchesAfterTwoKnocksAfterALongRun() {
        val c = Closest()
        for (i in 0 until 12) { c.add("C", 1000L + i * 1000, 0.08f, 30f); c.add("A", 1000L + i * 1000, 0.01f, 9f) }
        fun knockAtA(i: Int): Closest.Summary {
            val t = 13_000L + i * 1000
            c.add("A", t, 0.08f, 30f); c.add("C", t, 0.01f, 9f)
            c.tick(t + Closest.CLOSE_AFTER_MS)
            return c.summary(t + Closest.CLOSE_AFTER_MS)!!
        }
        assertEquals("C", knockAtA(0).leader)
        val second = knockAtA(1)
        assertEquals("A", second.leader)         // 2–2: the tie goes to the most recent winner (LEANING on the screen)
        assertEquals(2, second.wins)
        val third = knockAtA(2)
        assertEquals("A", third.leader)
        assertEquals(3, third.wins)              // 3 of 4: CLOSEST on the screen
        assertEquals(4, third.knocks)
    }

    /** 27 Sep 06:05: the fixed 2.2 s wait was most of the lag. A knock is judged once every phone has reported past it. */
    @Test fun judgedAsSoonAsEveryPhoneHasReported() {
        val c = Closest()
        c.add("A", 1000, 0.01f, 9f); c.add("C", 1030, 0.08f, 30f)
        assertTrue(c.tick(1400, readyUntilMs = 1200).isEmpty())      // B has not covered 1000 + margin yet
        val k = c.tick(1600, readyUntilMs = 1300).single()             // everyone past 1250: judged 0.6 s after the knock
        assertEquals("C", k.winner)
    }

    @Test fun lateReportIsDroppedNotAFalseWin() {
        val c = Closest()
        c.add("A", 1000, 0.01f, 9f); c.add("C", 1000, 0.08f, 30f)
        c.tick(1400, readyUntilMs = 1300)
        assertTrue(!c.add("B", 1100, 0.2f, 40f))                        // B's report for the same knock, too late
        assertTrue(c.tick(5000, readyUntilMs = 5000).isEmpty())         // no second, one-phone "B" knock was made from it
        assertTrue(c.add("B", 2500, 0.2f, 40f))                       // the next knock is accepted as usual
    }

    @Test fun oneOddKnockDoesNotFlipIt() {
        val c = Closest()
        for (i in 0 until 6) { c.add("B", 1000L + i * 1000, 0.08f, 30f); c.add("A", 1000L + i * 1000, 0.01f, 9f) }
        c.add("A", 7000, 0.08f, 30f); c.add("B", 7000, 0.01f, 9f)
        c.tick(10_000)
        val s = c.summary(10_000)!!
        assertEquals("B", s.leader)
        assertEquals(Closest.WINDOW_KNOCKS - 1, s.wins)   // 3 of the last 4
    }

    /** Two clear knocks are enough to name a phone (09:35, team); one is not. */
    @Test fun twoKnocksNameAPhone() {
        val c = Closest()
        c.add("B", 1000, 0.08f, 30f); c.add("A", 1000, 0.01f, 9f)
        c.tick(5000)
        assertEquals(null, c.summary(5000))
        c.add("B", 2000, 0.08f, 30f); c.add("A", 2000, 0.01f, 9f)
        c.tick(6000)
        assertEquals("B", c.summary(6000)!!.leader)
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
