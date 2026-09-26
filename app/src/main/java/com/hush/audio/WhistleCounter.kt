package com.hush.audio

/**
 * Persona B (docs/PLAN-personas-bc.md, step 2): counts pressure-cooker whistles and says when the target is reached.
 *
 * A cooker whistle is 1–4 s of loud, TONAL steam. Per second three things are looked at: what YAMNet calls it
 * (Whistle / Steam whistle / Whistling / Hiss / Steam / Boiling, summed), how loud it is against the room's noise
 * floor, and how tonal the spectrum is ([Tonality]: strongest line ÷ median of the 400–5000 Hz band). A second is
 * a whistle second when the model agrees AND it is tonal AND loud, or when it is very tonal and very loud on its
 * own (a kettle counts too; the screen says so). The first whistle second starts a whistle; anything within
 * [REFRACTORY_MS] is the same whistle (a double whistle counts once; real ones are ≥ 20 s apart). Pure logic; the
 * beeps, buzz and screen live in Engine / HomeScreen.
 */
class WhistleCounter {

    data class Second(val nowMs: Long, val whistleScore: Float, val loudFactor: Float, val tonalRatio: Float, val peakHz: Float, val selfNoise: Boolean)

    enum class Kind { WHISTLE, DONE, STALE }
    data class Event(val kind: Kind, val count: Int, val target: Int, val atMs: Long, val detail: String)

    companion object {
        val CLASSES = setOf("Whistle", "Steam whistle", "Whistling", "Hiss", "Steam", "Boiling")
        /** Model score ≥ this, together with tonality and loudness… */
        const val SCORE_MIN = 0.3f
        const val TONAL_MIN = 6f
        const val LOUD_MIN = 4f
        /** …or, without the model: a strong tone that is very loud, in the band a cooker whistles in. */
        const val TONAL_ALONE = 15f
        const val LOUD_ALONE = 6f
        const val HZ_LOW = 600f
        const val HZ_HIGH = 4500f
        /** Whistle seconds this close together are one whistle. */
        const val REFRACTORY_MS = 8_000L
        /** After the first whistle, no whistle for this long = "check the cooker" (once). */
        const val STALE_MS = 15 * 60_000L
        const val DEFAULT_TARGET = 3
    }

    @Volatile var target = DEFAULT_TARGET
    val whistleTimesMs = ArrayList<Long>()
    val count: Int get() = whistleTimesMs.size
    private var lastWhistleMs = Long.MIN_VALUE / 2
    private var doneReported = false
    private var staleReported = false
    /** Last second's verdict, for the screen. */
    @Volatile var lastReason = ""
        private set

    fun reset() {
        whistleTimesMs.clear(); lastWhistleMs = Long.MIN_VALUE / 2; doneReported = false; staleReported = false; lastReason = ""
    }

    fun isWhistleSecond(s: Second): Boolean {
        if (s.selfNoise) return false
        val inBand = s.peakHz in HZ_LOW..HZ_HIGH
        val withModel = s.whistleScore >= SCORE_MIN && s.tonalRatio >= TONAL_MIN && s.loudFactor >= LOUD_MIN
        val alone = s.tonalRatio >= TONAL_ALONE && s.loudFactor >= LOUD_ALONE && inBand
        return withModel || alone
    }

    fun onSecond(s: Second): Event? {
        val whistle = isWhistleSecond(s)
        lastReason = "score=%.2f tonal=%.0fx loud=x%.1f peak=%.0f Hz → %s".format(s.whistleScore, s.tonalRatio, s.loudFactor, s.peakHz, if (whistle) "WHISTLE" else "no")
        if (whistle) {
            if (s.nowMs - lastWhistleMs < REFRACTORY_MS) { lastWhistleMs = s.nowMs; return null }   // still the same whistle
            lastWhistleMs = s.nowMs
            whistleTimesMs.add(s.nowMs)
            staleReported = false
            val detail = lastReason
            if (count >= target && !doneReported) {
                doneReported = true
                return Event(Kind.DONE, count, target, s.nowMs, detail)
            }
            return Event(Kind.WHISTLE, count, target, s.nowMs, detail)
        }
        if (count > 0 && !doneReported && !staleReported && s.nowMs - lastWhistleMs > STALE_MS) {
            staleReported = true
            return Event(Kind.STALE, count, target, s.nowMs, "no whistle for ${(s.nowMs - lastWhistleMs) / 60_000} min")
        }
        return null
    }
}
