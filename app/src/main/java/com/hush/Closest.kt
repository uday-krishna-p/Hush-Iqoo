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
 * the loudest phone wins it. [summary] counts the wins among the last [WINDOW_KNOCKS] clear knocks of the last [HISTORY_MS].
 */
class Closest {
    companion object {
        const val MATCH_MS = 350L          // relay delay measured 0.04–0.15 s, outliers 0.5 s; people knock ≥ 0.3 s apart
        /** Longest wait before a knock is judged (a phone that went quiet); normally it is judged as soon as every
         *  talking phone has reported past it plus [READY_MARGIN_MS] (27 Sep 06:05: the fixed 2.2 s wait was most of the
         *  panel's lag). */
        const val CLOSE_AFTER_MS = 2200L
        /** Relay delays differ between phones by up to a few hundred ms (median 0.04–0.15 s): wait this far past the knock. */
        const val READY_MARGIN_MS = 250L
        const val HISTORY_MS = 20_000L     // knocks older than this are forgotten: the panel clears when the knocking stops
        /** The panel decides from this many most recent clear knocks (27 Sep 06:00: counting 20 s of wins took 7–10 s to follow
         *  the knocker to the next phone; with 6 the new phone leads after 3 knocks and is CLOSEST after 4). */
        const val WINDOW_KNOCKS = 6
        const val MIN_LOUD_RATIO = 8f      // at least one phone heard it ≥ ×8 over its background (room noises ×2–6)
        const val MIN_KNOCKS = 3
        /** A knock counts only when its loudest phone leads the next by this much (first run, 27 Sep 05:55: knocks
         *  between clear runs were won by 0–2 dB and hopped between phones; clear knocks led by 8–23 dB). */
        const val MIN_LEAD_DB = 3f
    }

    /** One knock as the commander judged it: the still phones' peaks (0..1), the winner among them and its lead over the
     *  runner-up; [all] also has the phones that were being moved (carried: WARMER/COLDER uses them, the vote does not). */
    data class Knock(val tMs: Long, val peaks: Map<String, Float>, val winner: String, val runnerUp: String?, val leadDb: Float?,
                     val all: Map<String, Float> = peaks) {
        /** Clear enough to count: one still phone alone heard it, or the loudest still phone led by ≥ [MIN_LEAD_DB]. */
        val decisive: Boolean get() = winner.isNotEmpty() && (leadDb == null || leadDb >= MIN_LEAD_DB)
    }

    data class Summary(
        val leader: String, val wins: Int, val knocks: Int,
        val leadDb: Float?,               // median lead over the runner-up on the knocks it won with ≥ 2 phones hearing
        val runnerUp: String?,            // the phone that came second most often on the leader's wins
        val winsBy: Map<String, Int>,     // every phone's wins, most first
        val lastWinner: String
    )

    private class Group(val tMs: Long) { val peaks = LinkedHashMap<String, Float>(); val moving = HashSet<String>(); var maxRatio = 0f }
    private val open = ArrayList<Group>()
    private val done = ArrayDeque<Knock>()
    /** Times of knocks already judged (including quiet ones), so a report arriving late cannot start a false one-phone knock. */
    private val judgedTimes = ArrayDeque<Long>()
    private val lock = Any()

    /** One detection: [letter] heard a knock at [tMs] (commander's clock) with [peak] amplitude, [ratio] × its background.
     *  [moving]: the phone was being handled or carried (its level still feeds WARMER/COLDER, not the vote).
     *  False when that knock was already judged (the report came too late): it is dropped. */
    fun add(letter: String, tMs: Long, peak: Float, ratio: Float, moving: Boolean = false): Boolean = synchronized(lock) {
        if (judgedTimes.any { kotlin.math.abs(it - tMs) <= MATCH_MS } && open.none { kotlin.math.abs(it.tMs - tMs) <= MATCH_MS }) return false
        var g = open.minByOrNull { kotlin.math.abs(it.tMs - tMs) }?.takeIf { kotlin.math.abs(it.tMs - tMs) <= MATCH_MS }
        if (g == null) { g = Group(tMs); open.add(g) }
        // The same phone twice in one knock (an echo, a double onset): keep its loudest.
        g.peaks[letter] = maxOf(g.peaks[letter] ?: 0f, peak)
        g.maxRatio = maxOf(g.maxRatio, ratio)
        if (moving) g.moving.add(letter)
        true
    }

    /** Judges the knocks every phone has reported: those before [readyUntilMs] − [READY_MARGIN_MS] (every talking phone
     *  has reported up to [readyUntilMs]; null = unknown), or older than [CLOSE_AFTER_MS]. Returns the newly judged ones. */
    fun tick(nowMs: Long, readyUntilMs: Long? = null): List<Knock> = synchronized(lock) {
        val out = ArrayList<Knock>()
        val it = open.iterator()
        while (it.hasNext()) {
            val g = it.next()
            val ready = readyUntilMs != null && g.tMs + READY_MARGIN_MS <= readyUntilMs
            if (!ready && nowMs - g.tMs < CLOSE_AFTER_MS) continue
            it.remove()
            judgedTimes.addLast(g.tMs)
            if (g.maxRatio < MIN_LOUD_RATIO) continue
            val all = LinkedHashMap(g.peaks)
            val still = g.peaks.filterKeys { it !in g.moving }
            if (still.isEmpty()) { out.add(Knock(g.tMs, emptyMap(), "", null, null, all)); continue }   // only moving phones heard it: no vote
            val sorted = still.entries.sortedByDescending { it.value }
            val first = sorted[0]; val second = sorted.getOrNull(1)
            val lead = second?.let { if (it.value > 0f) (20 * log10(first.value / it.value)).toFloat() else null }
            val k = Knock(g.tMs, LinkedHashMap(still), first.key, second?.key, lead, all)
            done.addLast(k); out.add(k)
        }
        while (done.isNotEmpty() && nowMs - done.first().tMs > HISTORY_MS) done.removeFirst()
        while (judgedTimes.isNotEmpty() && nowMs - judgedTimes.first() > 10_000L) judgedTimes.removeFirst()
        out.sortBy { it.tMs }
        out
    }

    /** The phone that won most of the recent clear knocks, or null while fewer than [MIN_KNOCKS] clear ones were judged. */
    fun summary(nowMs: Long): Summary? = synchronized(lock) {
        while (done.isNotEmpty() && nowMs - done.first().tMs > HISTORY_MS) done.removeFirst()
        val done = done.filter { it.decisive }.takeLast(WINDOW_KNOCKS)
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

    /** The clear knocks [summary] decides from (the last [WINDOW_KNOCKS] of the last [HISTORY_MS]), oldest first. */
    fun recent(nowMs: Long): List<Knock> = synchronized(lock) {
        done.filter { nowMs - it.tMs <= HISTORY_MS && it.decisive }.takeLast(WINDOW_KNOCKS)
    }

    fun reset() = synchronized(lock) { open.clear(); done.clear(); judgedTimes.clear() }
}
