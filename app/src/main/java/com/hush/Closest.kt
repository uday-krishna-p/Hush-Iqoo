package com.hush

import kotlin.math.log10

/**
 * WHICH PHONE IS CLOSEST to the knocking (commander only, 27 Sep 05:30, after the RCA): sound gets 6 dB quieter
 * every time the distance doubles, so for each knock the phone that hears it loudest is the nearest one.
 * No compass, no clock sync, no mirror, no positions.
 *
 * Every phone reports each knock's peak level and how long ago it happened ([add] gets the time on the
 * commander's own clock). Detections from different phones within [MATCH_MS] of each other are the same knock.
 * A knock is judged [CLOSE_AFTER_MS] after it happened (reports arrive once a second plus the relay delay):
 * the loudest phone wins it. [summary] counts the wins over the last [HISTORY_MS].
 */
class Closest {
    companion object {
        const val MATCH_MS = 350L          // relay delay measured 0.04–0.15 s, outliers 0.5 s; people knock ≥ 0.3 s apart
        const val CLOSE_AFTER_MS = 2200L
        const val HISTORY_MS = 20_000L     // follows a knocker who moves within ~20 s
        const val MIN_LOUD_RATIO = 8f      // at least one phone heard it ≥ ×8 over its background (room noises ×2–6)
        const val MIN_KNOCKS = 3
        /** A knock counts only when its loudest phone leads the next by this much (first run, 27 Sep 05:55: knocks
         *  between clear runs were won by 0–2 dB and hopped between phones; clear knocks led by 8–23 dB). */
        const val MIN_LEAD_DB = 3f
    }

    /** One knock as the commander judged it: every phone's peak (0..1), the winner and its lead over the runner-up. */
    data class Knock(val tMs: Long, val peaks: Map<String, Float>, val winner: String, val runnerUp: String?, val leadDb: Float?) {
        /** Clear enough to count: one phone alone heard it, or the loudest led by ≥ [MIN_LEAD_DB]. */
        val decisive: Boolean get() = leadDb == null || leadDb >= MIN_LEAD_DB
    }

    data class Summary(
        val leader: String, val wins: Int, val knocks: Int,
        val leadDb: Float?,               // median lead over the runner-up on the knocks it won with ≥ 2 phones hearing
        val runnerUp: String?,            // the phone that came second most often on the leader's wins
        val winsBy: Map<String, Int>,     // every phone's wins, most first
        val lastWinner: String
    )

    private class Group(val tMs: Long) { val peaks = LinkedHashMap<String, Float>(); var maxRatio = 0f }
    private val open = ArrayList<Group>()
    private val done = ArrayDeque<Knock>()
    private val lock = Any()

    /** One detection: [letter] heard a knock at [tMs] (commander's clock) with [peak] amplitude, [ratio] × its background. */
    fun add(letter: String, tMs: Long, peak: Float, ratio: Float) = synchronized(lock) {
        var g = open.minByOrNull { kotlin.math.abs(it.tMs - tMs) }?.takeIf { kotlin.math.abs(it.tMs - tMs) <= MATCH_MS }
        if (g == null) { g = Group(tMs); open.add(g) }
        // The same phone twice in one knock (an echo, a double onset): keep its loudest.
        g.peaks[letter] = maxOf(g.peaks[letter] ?: 0f, peak)
        g.maxRatio = maxOf(g.maxRatio, ratio)
    }

    /** Judges the knocks old enough to have every phone's report. Returns the newly judged ones (for the log). */
    fun tick(nowMs: Long): List<Knock> = synchronized(lock) {
        val out = ArrayList<Knock>()
        val it = open.iterator()
        while (it.hasNext()) {
            val g = it.next()
            if (nowMs - g.tMs < CLOSE_AFTER_MS) continue
            it.remove()
            if (g.maxRatio < MIN_LOUD_RATIO) continue
            val sorted = g.peaks.entries.sortedByDescending { it.value }
            val first = sorted[0]; val second = sorted.getOrNull(1)
            val lead = second?.let { if (it.value > 0f) (20 * log10(first.value / it.value)).toFloat() else null }
            val k = Knock(g.tMs, LinkedHashMap(g.peaks), first.key, second?.key, lead)
            done.addLast(k); out.add(k)
        }
        while (done.isNotEmpty() && nowMs - done.first().tMs > HISTORY_MS) done.removeFirst()
        out.sortBy { it.tMs }
        out
    }

    /** The phone that won most of the recent clear knocks, or null while fewer than [MIN_KNOCKS] clear ones were judged. */
    fun summary(nowMs: Long): Summary? = synchronized(lock) {
        while (done.isNotEmpty() && nowMs - done.first().tMs > HISTORY_MS) done.removeFirst()
        val done = done.filter { it.decisive }
        if (done.size < MIN_KNOCKS) return null
        val wins = HashMap<String, Int>()
        for (k in done) wins[k.winner] = (wins[k.winner] ?: 0) + 1
        val lastWinner = done.last().winner
        // Most wins; a tie goes to the most recent winner among the tied (the knocker may have moved).
        val top = wins.values.max()
        val tied = wins.filterValues { it == top }.keys
        val leader = done.reversed().first { it.winner in tied }.winner
        val leads = done.filter { it.winner == leader }.mapNotNull { it.leadDb }.sorted()
        val runners = done.filter { it.winner == leader }.mapNotNull { it.runnerUp }.groupingBy { it }.eachCount()
        Summary(leader, top, done.size, leads.getOrNull(leads.size / 2), runners.maxByOrNull { it.value }?.key,
            wins.entries.sortedByDescending { it.value }.associate { it.key to it.value }, lastWinner)
    }

    fun reset() = synchronized(lock) { open.clear(); done.clear() }
}
