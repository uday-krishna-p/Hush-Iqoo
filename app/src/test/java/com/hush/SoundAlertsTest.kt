package com.hush

import com.hush.audio.SoundAlerts
import com.hush.audio.SoundAlerts.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laptop-only check of the household sound categories (persona C): thresholds, loudness gate, knock rhythm, debounce. */
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
    fun threeKnocksReplayTheirRhythm() {
        val d = SoundAlerts().also { it.enabled.add(Category.KNOCK) }   // KNOCK is off by default in this version
        val t = warm(d)
        val knocks = listOf(SoundAlerts.Knock(t - 900, 14f), SoundAlerts.Knock(t - 500, 12f), SoundAlerts.Knock(t - 100, 15f))
        val a = d.onSecond(second(t, mapOf("Knock" to 0.2f, "Wood" to 0.1f), knocks = knocks))
        assertNotNull(a)
        assertEquals(Category.KNOCK, a!!.category)
        assertEquals("KNOCK ×3", a.word)
        // 0, buzz, gap, buzz, gap, buzz: three buzzes of 120 ms with the knocks' 400 ms spacing between them.
        assertEquals(listOf(0L, 120L, 280L, 120L, 280L, 120L), a.pattern.toList())
        // The same onsets are not reported twice.
        assertNull(d.onSecond(second(t + 1000, mapOf("Knock" to 0.2f), knocks = listOf(SoundAlerts.Knock(t + 900, 12f)))))
    }

    @Test
    fun oneOnsetOrSoftOnsetsAreNotAKnock() {
        val d = SoundAlerts().also { it.enabled.add(Category.KNOCK) }   // KNOCK is off by default in this version
        val t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Knock" to 0.3f), knocks = listOf(SoundAlerts.Knock(t - 100, 14f)))))
        // Room clatter: several onsets but soft (×3), and the model hears no knock.
        val soft = listOf(SoundAlerts.Knock(t + 200, 3f), SoundAlerts.Knock(t + 600, 4f), SoundAlerts.Knock(t + 900, 3f))
        assertNull(d.onSecond(second(t + 1000, mapOf("Speech" to 0.4f), knocks = soft)))
    }

    @Test
    fun loudKnocksNeedNoModel() {
        val d = SoundAlerts().also { it.enabled.add(Category.KNOCK) }   // KNOCK is off by default in this version
        val t = warm(d)
        val knocks = listOf(SoundAlerts.Knock(t - 700, 22f), SoundAlerts.Knock(t - 200, 18f))
        val a = d.onSecond(second(t, mapOf("Dishes, pots, and pans" to 0.3f), knocks = knocks))
        assertNotNull(a)
        assertEquals("KNOCK ×2", a!!.word)
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
