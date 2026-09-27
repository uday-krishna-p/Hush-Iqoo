package com.hush

import com.hush.audio.SoundAlerts
import com.hush.audio.SoundAlerts.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laptop-only check of the household sound categories (persona C): thresholds, loudness gate, no knock alerts, debounce. */
class SoundAlertsTest {

    private val floor = 0.001f

    private fun second(t: Long, scores: Map<String, Float> = emptyMap(), loud: Float = 4f, knocks: List<SoundAlerts.Knock> = emptyList(),
                       moving: Boolean = false, top: String = scores.maxByOrNull { it.value }?.key ?: "Silence") =
        SoundAlerts.Second(t, floor * loud, floor, moving, false, scores, top, knocks)

    /** Runs quiet seconds through the warm-up so the detector is live at the returned time. */
    private fun warm(d: SoundAlerts): Long {
        var t = 1000L
        repeat(7) { assertNull(d.onSecond(second(t, loud = 1f))); t += 1000 }
        return t
    }

    @Test
    fun doorbellFiresOnOneLoudSecond() {
        val d = SoundAlerts()
        val t = warm(d)
        val a = d.onSecond(second(t, mapOf("Doorbell" to 0.45f, "Speech" to 0.1f)))
        assertNotNull(a)
        assertEquals(Category.DOORBELL, a!!.category)
        assertEquals("DOORBELL", a.word)
        assertFalse(a.extended)
    }

    @Test
    fun quietTvDoesNotFire() {
        val d = SoundAlerts()
        val t = warm(d)
        // A doorbell on the TV in the next room: the model hears it, but it is not loud against the room.
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.6f), loud = 1.2f)))
    }

    @Test
    fun nothingDuringWarmUp() {
        val d = SoundAlerts()
        assertNull(d.onSecond(second(1000, mapOf("Doorbell" to 0.9f))))
        assertNull(d.onSecond(second(3000, mapOf("Doorbell" to 0.9f))))
    }

    @Test
    fun alarmNeedsTwoOfThreeSecondsAndThenRepeats() {
        val d = SoundAlerts()
        var t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Smoke detector, smoke alarm" to 0.5f)))); t += 1000
        val a = d.onSecond(second(t, mapOf("Smoke detector, smoke alarm" to 0.5f)))
        assertNotNull(a)
        assertEquals(Category.ALARM, a!!.category)
        assertEquals("SMOKE ALARM", a.word)
        assertTrue(a.repeats)
    }

    @Test
    fun continuingSoundExtendsInsteadOfRefiring() {
        val d = SoundAlerts()
        var t = warm(d)
        val first = d.onSecond(second(t, mapOf("Doorbell" to 0.5f)))
        assertNotNull(first); assertFalse(first!!.extended)
        t += 1000
        val again = d.onSecond(second(t, mapOf("Doorbell" to 0.5f)))
        assertNotNull(again); assertTrue(again!!.extended)
        // 25 s later it is a new ring.
        t += 25_000
        val later = d.onSecond(second(t, mapOf("Doorbell" to 0.5f)))
        assertNotNull(later); assertFalse(later!!.extended)
    }

    @Test
    fun knocksNeverFireAnAlert() {
        // No knock classifier in the alerts: loud onsets and knock-like model scores stay silent, even with KNOCK switched on.
        val d = SoundAlerts().also { it.enabled.add(Category.KNOCK) }
        val t = warm(d)
        val knocks = listOf(SoundAlerts.Knock(t - 700, 40f), SoundAlerts.Knock(t - 300, 35f), SoundAlerts.Knock(t - 100, 50f))
        assertNull(d.onSecond(second(t, mapOf("Knock" to 0.6f, "Wood" to 0.3f, "Door" to 0.2f), loud = 10f, knocks = knocks)))
        assertFalse(Category.KNOCK.listens)
    }

    @Test
    fun handledPhonePausesAllButAlarm() {
        val d = SoundAlerts()
        var t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.6f), moving = true)))
        t += 1000
        assertNull(d.onSecond(second(t, mapOf("Fire alarm" to 0.6f), moving = true))); t += 1000
        val a = d.onSecond(second(t, mapOf("Fire alarm" to 0.6f), moving = true))
        assertNotNull(a); assertEquals(Category.ALARM, a!!.category); assertEquals("FIRE ALARM", a.word)
    }

    @Test
    fun switchedOffCategoryStaysQuietAndHighestPriorityWins() {
        val d = SoundAlerts()
        var t = warm(d)
        d.enabled.remove(Category.DOORBELL)
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.7f))))
        d.enabled.add(Category.DOORBELL)
        t += 1000
        // A doorbell and a scream in the same seconds: the scream (DISTRESS) outranks the doorbell.
        assertNotNull(d.onSecond(second(t, mapOf("Doorbell" to 0.5f, "Screaming" to 0.4f))))   // doorbell (distress needs 2 s)
        t += 1000
        val a = d.onSecond(second(t, mapOf("Doorbell" to 0.5f, "Screaming" to 0.4f)))
        assertNotNull(a); assertEquals(Category.DISTRESS, a!!.category); assertEquals("SCREAMING", a.word)
    }

    @Test
    fun highSensitivityLowersTheBar() {
        val d = SoundAlerts()
        val t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.25f))))
        d.sensitivity = 0.7f
        val a = d.onSecond(second(t + 1000, mapOf("Doorbell" to 0.25f)))
        assertNotNull(a); assertEquals(Category.DOORBELL, a!!.category)
    }
}
