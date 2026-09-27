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
                       moving: Boolean = false, top: String = scores.maxByOrNull { it.value }?.key ?: "Silence", tonal: Float = 0f) =
        SoundAlerts.Second(t, floor * loud, floor, moving, false, scores, top, knocks, tonal, if (tonal > 0f) 1200f else 0f)

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
        // A doorbell on the TV in the next room: the model hears it, but it does not rise above the room at all.
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.6f), loud = 1.05f)))
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
    fun speechAtThreeTimesTheRoomCalls() {
        val d = SoundAlerts().also { it.enabled.add(Category.SPEECH) }   // SPEECH is off by default
        var t = warm(d)
        // Too soft (x2): never.
        repeat(3) { assertNull(d.onSecond(second(t, mapOf("Speech" to 0.9f), loud = 2f))); t += 1000 }
        // x3 for three seconds in a row: the third one calls.
        assertNull(d.onSecond(second(t, mapOf("Speech" to 0.9f), loud = 3f))); t += 1000
        assertNull(d.onSecond(second(t, mapOf("Speech" to 0.9f), loud = 3f))); t += 1000
        val a = d.onSecond(second(t, mapOf("Speech" to 0.9f), loud = 3f))
        assertNotNull(a)
        assertEquals(Category.SPEECH, a!!.category)
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
    fun handledPhonePausesAllButAlarmAndDoorbell() {
        val d = SoundAlerts()
        var t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Telephone bell ringing" to 0.6f), moving = true))); t += 1000
        assertNull(d.onSecond(second(t, mapOf("Telephone bell ringing" to 0.6f), moving = true))); t += 1000
        // In a crowd the phone is always being carried: the doorbell still rings through.
        val bell = d.onSecond(second(t, mapOf("Doorbell" to 0.6f), moving = true))
        assertNotNull(bell); assertEquals(Category.DOORBELL, bell!!.category)
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
        assertNull(d.onSecond(second(t, mapOf("Doorbell" to 0.12f))))
        d.sensitivity = 0.7f
        val a = d.onSecond(second(t + 1000, mapOf("Doorbell" to 0.12f)))
        assertNotNull(a); assertEquals(Category.DOORBELL, a!!.category)
    }

    @Test
    fun faintDoorbellOverACrowdFires() {
        val d = SoundAlerts()
        val t = warm(d)
        // Chatter everywhere, the chime barely raises the level (x1.3) and the model gives it only 0.1 + 0.07.
        val a = d.onSecond(second(t, mapOf("Speech" to 0.7f, "Crowd" to 0.3f, "Ding-dong" to 0.1f, "Chime" to 0.07f), loud = 1.3f))
        assertNotNull(a); assertEquals(Category.DOORBELL, a!!.category)
    }

    @Test
    fun anyKindOfBellCounts() {
        // Melody, glockenspiel-like and plain electronic-tone doorbells: the model names the instrument, not "Doorbell".
        for (cls in listOf("Glockenspiel", "Tubular bells", "Vibraphone", "Jingle bell", "Chirp tone", "Buzzer")) {
            val d = SoundAlerts()
            val t = warm(d)
            val a = d.onSecond(second(t, mapOf(cls to 0.2f)))
            assertNotNull(cls, a); assertEquals(cls, Category.DOORBELL, a!!.category)
        }
    }

    @Test
    fun crowdSmearDoesNotRingTheBell() {
        val d = SoundAlerts()
        val t = warm(d)
        // A noisy room spreads 0.01-0.019 over many classes: summed they would pass 0.15, one by one none counts.
        val smear = listOf("Bell", "Chime", "Ding", "Glockenspiel", "Vibraphone", "Tubular bells", "Jingle bell", "Gong",
            "Sine wave", "Buzzer", "Cowbell", "Tuning fork").associateWith { 0.019f } + ("Speech" to 0.6f)
        assertNull(d.onSecond(second(t, smear, loud = 1.5f)))
    }

    @Test
    fun clearToneHalvesWhatTheBellNeeds() {
        val d = SoundAlerts()
        val t = warm(d)
        assertNull(d.onSecond(second(t, mapOf("Bell" to 0.09f), tonal = 3f)))
        val a = d.onSecond(second(t + 1000, mapOf("Bell" to 0.09f), tonal = 14f))
        assertNotNull(a); assertEquals(Category.DOORBELL, a!!.category)
        assertTrue(a.detail.contains("tonal=14.0"))
    }
}
