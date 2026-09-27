package com.hush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laptop-only check of TEACH: three loud seconds make a fingerprint; the same sound matches, others do not; file round trip. */
class SoundLibraryTest {

    private val myBell = mapOf("Doorbell" to 0.30f, "Ding-dong" to 0.25f, "Chime" to 0.20f, "Music" to 0.10f, "Inside, small room" to 0.05f)
    private val microwave = mapOf("Beep, bleep" to 0.55f, "Microwave oven" to 0.20f, "Alarm clock" to 0.10f)
    private val speech = mapOf("Speech" to 0.7f, "Narration, monologue" to 0.2f, "Inside, small room" to 0.1f)

    private fun jitter(m: Map<String, Float>, k: Float) = m.mapValues { (it.value * k).coerceIn(0f, 1f) } + mapOf("Silence" to 0.02f * k)

    @Test
    fun threeLoudSecondsTeachASound() {
        val lib = SoundLibrary()
        var t = 0L
        lib.startTeaching("front door", t)
        // Quiet seconds and a second where the model heard nothing do not count.
        assertNull(lib.feed(t, myBell, 1.2f)); t += 1000
        assertNull(lib.feed(t, mapOf("Silence" to 0.05f), 3f)); t += 1000
        assertNull(lib.feed(t, jitter(myBell, 0.9f), 3f)); t += 1000
        assertNull(lib.feed(t, jitter(myBell, 1.1f), 4f)); t += 1000
        val taught = lib.feed(t, myBell, 3f)
        assertNotNull(taught)
        assertEquals("front door", taught!!.name)
        assertNull(lib.session)
        assertEquals(1, lib.sounds.size)
    }

    @Test
    fun matchesTheSameSoundNotOthers() {
        val lib = SoundLibrary()
        lib.startTeaching("front door", 0)
        repeat(3) { lib.feed(it * 1000L, jitter(myBell, 1f + 0.1f * it), 3f) }
        val m = lib.match(jitter(myBell, 0.8f))
        assertNotNull(m); assertEquals("front door", m!!.first.name); assertTrue(m.second >= SoundLibrary.MIN_SIMILARITY)
        assertNull(lib.match(microwave))
        assertNull(lib.match(speech))
        // A second sound is told apart from the first.
        lib.startTeaching("microwave", 10_000)
        repeat(3) { lib.feed(10_000L + it * 1000L, microwave, 3f) }
        assertEquals("microwave", lib.match(jitter(microwave, 1.2f))!!.first.name)
        assertEquals("front door", lib.match(myBell)!!.first.name)
    }

    @Test
    fun sessionTimesOut() {
        val lib = SoundLibrary()
        lib.startTeaching("x", 0)
        assertNull(lib.feed(1000, myBell, 3f))
        assertNull(lib.feed(SoundLibrary.SESSION_MS + 1000, myBell, 3f))
        assertNull(lib.session)
        assertTrue(lib.timedOut)
        assertEquals(0, lib.sounds.size)
    }

    @Test
    fun linesRoundTripAndForget() {
        val lib = SoundLibrary()
        lib.startTeaching("front door", 0); repeat(3) { lib.feed(it * 1000L, myBell, 3f) }
        lib.startTeaching("micro; wave=1", 5000); repeat(3) { lib.feed(5000L + it * 1000L, microwave, 3f) }
        val lines = lib.toLines()
        val lib2 = SoundLibrary()
        lib2.fromLines(lines)
        assertEquals(2, lib2.sounds.size)
        assertEquals("front door", lib2.match(myBell)!!.first.name)
        assertEquals("micro; wave=1", lib2.match(microwave)!!.first.name)
        assertTrue(lib2.forget("front door"))
        assertNull(lib2.match(myBell))
    }
}
