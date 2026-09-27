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
