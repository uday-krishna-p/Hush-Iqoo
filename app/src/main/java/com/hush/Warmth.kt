package com.hush

import kotlin.math.log10

/**
 * WARMER / COLDER for a phone that is being carried (27 Sep 06:45; team: "since the devices would also move", point
 * people towards the sound without positions or a compass). Like a metal detector.
 *
 * Per knock, each phone's level RELATIVE to the other phones (its peak over the median peak of the other STILL phones,
 * in dB; a carried phone in the reference would make the still phones read COLDER as it walks in):
 * how hard the person knocked cancels out, so only this phone's distance changes it. Carrying a phone towards the
 * knock raises its relative level (1/r: halving the distance = +6 dB), away lowers it. The trend is the mean of the
 * last [RECENT] knocks against the mean of up to [BEFORE] knocks before those; ±[STEP_DB] or more is WARMER / COLDER.
 * A phone lying still keeps a steady relative level (knock-to-knock echo varies it by a few dB, hence two knocks
 * against four and a 3 dB step). Needs at least one other phone hearing the same knock.
 */
class Warmth {
    companion object {
        const val RECENT = 2
        const val BEFORE = 4
        const val STEP_DB = 3f
        const val HISTORY_MS = 30_000L
    }

    enum class Trend { WARMER, COLDER, SAME }

    /** [deltaDb]: recent minus before; [relDb]: the latest knock's level against the other phones. */
    data class Status(val trend: Trend, val deltaDb: Float, val relDb: Float, val knocks: Int)

    /** The trend step ([STEP_DB]; HIGH sensitivity lowers it so smaller moves register, with more flicker on still phones). */
    @Volatile var stepDb = STEP_DB
    private val history = HashMap<String, ArrayDeque<Pair<Long, Float>>>()
    private val lock = Any()

    /** One judged knock: every phone's peak (0..1) at [tMs], moving phones included; [still] = the phones lying still. */
    fun add(tMs: Long, peaks: Map<String, Float>, still: Set<String> = peaks.keys) = synchronized(lock) {
        for ((l, p) in peaks) {
            if (p <= 0f) continue
            val stillOthers = peaks.filterKeys { it != l && it in still }.values.filter { it > 0f }
            val others = stillOthers.ifEmpty { peaks.filterKeys { it != l }.values.filter { it > 0f } }.sorted()
            if (others.isEmpty()) continue
            val ref = if (others.size % 2 == 1) others[others.size / 2] else (others[others.size / 2 - 1] + others[others.size / 2]) / 2
            val h = history.getOrPut(l) { ArrayDeque() }
            h.addLast(tMs to (20 * log10(p / ref)))
            while (h.isNotEmpty() && tMs - h.first().first > HISTORY_MS) h.removeFirst()
        }
    }

    /** The phone's trend now, or null until it has [RECENT] + 1 knocks in the last [HISTORY_MS]. */
    fun status(letter: String, nowMs: Long): Status? = synchronized(lock) {
        val h = history[letter] ?: return null
        while (h.isNotEmpty() && nowMs - h.first().first > HISTORY_MS) h.removeFirst()
        if (h.size < RECENT + 1) return null
        val rel = h.map { it.second }
        val recent = rel.takeLast(RECENT).average()
        val before = rel.dropLast(RECENT).takeLast(BEFORE).average()
        val d = (recent - before).toFloat()
        Status(if (d >= stepDb) Trend.WARMER else if (d <= -stepDb) Trend.COLDER else Trend.SAME, d, rel.last(), h.size)
    }

    fun letters(): Set<String> = synchronized(lock) { history.keys.toSet() }

    fun reset() = synchronized(lock) { history.clear() }
}
