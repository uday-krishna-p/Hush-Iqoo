package com.hush.audio

/**
 * Persona C (docs/PLAN-personas-bc.md, step 1): which household sound was that?
 *
 * Fed once a second with what the pipeline already produces (loudness above the room's floor, YAMNet's class
 * scores, the sharp onsets of the tap detector), it folds them into a few categories, each with a colour, a word
 * and a vibration pattern the person learns to tell apart. Pure logic, no Android: the phone-side effects
 * (flash, buzz, notification, history) live in [com.hush.Alerting].
 *
 * Rules (start values, to be corrected from the `window` log lines of real doorbells, cookers and alarms):
 *  - a category's score is the SUM of its YAMNet classes for that second, like the rescue VOICE/MACHINE buckets;
 *  - every category also needs loudness ≥ [Category.loudFactor] × the noise floor, so a TV murmuring in the next
 *    room does not fire; ALARM is the exception at 1.5× because a distant smoke alarm matters;
 *  - KNOCK is not rescue tapping (a steady rhythm over 8 s): a door knock is 2–6 sharp onsets inside 3 s and then
 *    silence, so it has its own rule on the tap detector's onsets and replays their rhythm as the vibration;
 *  - one alert per category per [DEBOUNCE_MS]; while the sound keeps going the alert is extended, not re-posted;
 *  - nothing during the first [WARM_UP_MS] (the noise floor is still empty) and nothing while the phone is handled,
 *    except ALARM.
 */
class SoundAlerts {

    enum class Category(
        val word: String,
        val colour: Int,            // ARGB
        val classes: Set<String>,   // YAMNet display names, summed
        val threshold: Float,       // the sum must reach this…
        val secondsOf3: Int,        // …in this many of the last 3 seconds
        val loudFactor: Float,      // loudness ≥ this × the noise floor
        val priority: Int,          // higher wins the screen when two fire together
        val pattern: LongArray,     // off/on/off/on… ms (see Haptics.vibrate); KNOCK builds its own
        val repeats: Boolean = false,   // ALARM keeps buzzing until dismissed
        val defaultOn: Boolean = true
    ) {
        ALARM("ALARM", 0xFFD32F2F.toInt(),
            setOf("Smoke detector, smoke alarm", "Fire alarm", "Alarm", "Alarm clock", "Siren", "Civil defense siren", "Car alarm", "Buzzer"),
            0.35f, 2, 1.5f, 7, longArrayOf(0, 1000, 300), repeats = true),
        DISTRESS("SHOUTING", 0xFF7B1FA2.toInt(),
            setOf("Screaming", "Shout", "Yell", "Bellow", "Children shouting", "Crying, sobbing", "Baby cry, infant cry", "Whimper"),
            0.3f, 2, 2f, 6, longArrayOf(0, 600, 200, 600, 200, 600, 200)),
        CRASH("CRASH", 0xFFEF6C00.toInt(),
            setOf("Shatter", "Smash, crash", "Glass", "Explosion", "Breaking"),
            0.3f, 1, 2f, 5, longArrayOf(0, 800)),
        DOORBELL("DOORBELL", 0xFF1565C0.toInt(),
            setOf("Doorbell", "Ding-dong", "Ding", "Chime", "Bell", "Buzzer"),
            0.3f, 1, 2f, 4, longArrayOf(0, 150, 100, 150, 400, 150, 100, 150)),
        KNOCK("KNOCK", 0xFFF9A825.toInt(),
            setOf("Knock", "Door", "Wood", "Thump, thud"),
            0.15f, 1, 2f, 3, longArrayOf(0, 200)),
        TIMER("TIMER BEEPING", 0xFF2E7D32.toInt(),
            setOf("Beep, bleep", "Microwave oven", "Alarm clock"),
            0.3f, 2, 2f, 2, longArrayOf(0, 120, 120, 120, 120, 120, 120, 120)),
        PHONE("PHONE RINGING", 0xFF00838F.toInt(),
            setOf("Telephone bell ringing", "Ringtone", "Telephone"),
            0.3f, 2, 2f, 1, longArrayOf(0, 150, 150, 600)),
        DOG("DOG BARKING", 0xFF6D4C41.toInt(),
            setOf("Bark", "Dog", "Howl", "Growling"),
            0.3f, 2, 2f, 0, longArrayOf(0, 200), defaultOn = false),
        WATER("WATER RUNNING", 0xFF0277BD.toInt(),
            setOf("Drip", "Water tap, faucet", "Sink (filling or washing)", "Boiling", "Water"),
            0.3f, 3, 2f, 0, longArrayOf(0, 200), defaultOn = false),
        SPEECH("SOMEONE CALLING", 0xFF546E7A.toInt(),
            setOf("Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue"),
            0.5f, 3, 8f, 0, longArrayOf(0, 300, 200, 300), defaultOn = false);
    }

    /** One knock-like onset the tap detector found: when, and how far above the background it stood. */
    data class Knock(val atMs: Long, val ratio: Float)

    /** Everything one second offers the detector. */
    data class Second(
        val nowMs: Long,
        val rms: Float,
        val floor: Float,
        val moving: Boolean,
        val selfNoise: Boolean,             // our own beeps / buzz playing
        val scores: Map<String, Float>,     // YAMNet display name → score (classes < 0.01 may be absent)
        val topClass: String,
        val knocks: List<Knock>
    )

    data class Alert(
        val category: Category,
        val word: String,             // the word on the screen (ALARM says which alarm, KNOCK how many)
        val detail: String,           // what fired it, for the log and the history
        val score: Float,
        val pattern: LongArray,       // vibration
        val repeats: Boolean,
        val atMs: Long,
        val extended: Boolean         // the same alert is still going on (extend on screen, do not buzz again)
    )

    companion object {
        const val DEBOUNCE_MS = 20_000L
        const val WARM_UP_MS = 5_000L
        /** A door knock: this many onsets inside [KNOCK_WINDOW_MS], each at least [KNOCK_RATIO] × the background. */
        const val KNOCK_MIN = 2
        const val KNOCK_MAX = 6
        const val KNOCK_WINDOW_MS = 3_000L
        const val KNOCK_RATIO = 6f
        /** Without YAMNet's agreement every onset must be this loud. */
        const val KNOCK_RATIO_ALONE = 10f
        /** Words for ALARM by top class. */
        private val ALARM_WORDS = mapOf("Smoke detector, smoke alarm" to "SMOKE ALARM", "Fire alarm" to "FIRE ALARM", "Siren" to "SIREN",
            "Civil defense siren" to "SIREN", "Car alarm" to "CAR ALARM", "Alarm clock" to "ALARM CLOCK")
        private val DISTRESS_WORDS = mapOf("Baby cry, infant cry" to "BABY CRYING", "Crying, sobbing" to "CRYING", "Screaming" to "SCREAMING",
            "Children shouting" to "CHILDREN SHOUTING")
    }

    /** Which categories are on. */
    val enabled: MutableSet<Category> = Category.values().filter { it.defaultOn }.toMutableSet()
    /** 1.0 = normal; 0.7 = high sensitivity (thresholds scaled by it). */
    @Volatile var sensitivity = 1f

    private var startMs = -1L
    private val history = ArrayDeque<Second>()          // the last 3 seconds
    private val knocks = ArrayDeque<Knock>()             // onsets of the last KNOCK_WINDOW_MS
    private val lastFired = HashMap<Category, Long>()
    private var knocksReported = 0L                      // last time KNOCK fired: those onsets are spent

    fun reset() {
        startMs = -1L; history.clear(); knocks.clear(); lastFired.clear(); knocksReported = 0L
    }

    /** One second in; at most one alert out (the highest-priority category that fired). */
    fun onSecond(s: Second): Alert? {
        if (startMs < 0) startMs = s.nowMs
        history.addLast(s)
        while (history.size > 3) history.removeFirst()
        for (k in s.knocks) knocks.addLast(k)
        while (knocks.isNotEmpty() && s.nowMs - knocks.first().atMs > KNOCK_WINDOW_MS) knocks.removeFirst()
        if (s.nowMs - startMs < WARM_UP_MS || s.selfNoise) return null

        var best: Alert? = null
        for (c in Category.values()) {
            if (c !in enabled) continue
            val a = evaluate(c, s) ?: continue
            if (best == null || c.priority > best.category.priority) best = a
        }
        if (best != null) {
            val since = s.nowMs - (lastFired[best.category] ?: Long.MIN_VALUE / 2)
            // A continuing sound extends its alert; every knock burst is its own alert (the rhythm is the message).
            val extended = since < DEBOUNCE_MS && best.category != Category.KNOCK
            lastFired[best.category] = s.nowMs
            if (best.category == Category.KNOCK) knocksReported = s.nowMs
            return best.copy(extended = extended)
        }
        return null
    }

    private fun loudEnough(c: Category, s: Second): Boolean {
        if (s.floor <= 0f) return s.rms > 0f   // no floor yet (first quiet seconds): let the score decide
        return s.rms >= s.floor * c.loudFactor
    }

    private fun sum(c: Category, s: Second): Float { var t = 0f; for (n in c.classes) t += s.scores[n] ?: 0f; return t }

    private fun evaluate(c: Category, s: Second): Alert? {
        if (s.moving && c != Category.ALARM) return null
        if (c == Category.KNOCK) return evaluateKnock(s)
        val th = c.threshold * sensitivity
        // Count the recent seconds where the bucket score and the loudness both pass.
        var hits = 0
        var bestScore = 0f
        var bestTop = c.word
        for (h in history) {
            val v = sum(c, h)
            if (v >= th && loudEnough(c, h)) {
                hits++
                if (v > bestScore) {
                    bestScore = v
                    // The strongest class INSIDE the category names the alert (a louder doorbell elsewhere must not).
                    bestTop = c.classes.maxByOrNull { h.scores[it] ?: 0f } ?: c.word
                }
            }
        }
        // The current second must be one of them, otherwise a fading sound would re-fire every second.
        val nowV = sum(c, s)
        if (nowV < th || !loudEnough(c, s)) return null
        if (hits < c.secondsOf3) return null
        val word = when (c) {
            Category.ALARM -> ALARM_WORDS[bestTop] ?: c.word
            Category.DISTRESS -> DISTRESS_WORDS[bestTop] ?: c.word
            else -> c.word
        }
        val detail = "top=$bestTop score=%.2f loud=x%.1f".format(bestScore, if (s.floor > 0f) s.rms / s.floor else 0f)
        return Alert(c, word, detail, bestScore, c.pattern, c.repeats, s.nowMs, extended = false)
    }

    /**
     * A door knock: 2–6 sharp onsets inside 3 s, each ≥ 6× the background, with YAMNet hearing wood/knock in this
     * second, or every onset ≥ 10× on its own. The onsets of one reported knock are not reported again.
     */
    private fun evaluateKnock(s: Second): Alert? {
        if (s.knocks.isEmpty()) return null     // a knock is reported in the second its last onset arrives
        val fresh = knocks.filter { it.atMs > knocksReported && it.ratio >= KNOCK_RATIO }
        if (fresh.size < KNOCK_MIN || fresh.size > KNOCK_MAX) return null
        if (!loudEnough(Category.KNOCK, s)) return null
        val yamnet = sum(Category.KNOCK, s)
        val corroborated = yamnet >= Category.KNOCK.threshold * sensitivity || fresh.all { it.ratio >= KNOCK_RATIO_ALONE }
        if (!corroborated) return null
        // Replay the rhythm: a 120 ms buzz per knock, with the knocks' own gaps between them.
        val times = fresh.map { it.atMs }.sorted()
        val pattern = ArrayList<Long>()
        pattern.add(0L)
        for (i in times.indices) {
            pattern.add(120L)
            if (i < times.size - 1) pattern.add((times[i + 1] - times[i] - 120L).coerceIn(80L, 1500L))
        }
        val detail = "onsets=${fresh.size} ratios=" + fresh.joinToString(",") { "x%.0f".format(it.ratio) } + " yamnet=%.2f".format(yamnet)
        return Alert(Category.KNOCK, "KNOCK ×${fresh.size}", detail, yamnet.coerceAtLeast(0.15f), pattern.toLongArray(), false, s.nowMs, extended = false)
    }
}
