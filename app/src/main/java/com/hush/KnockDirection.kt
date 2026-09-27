package com.hush

import com.hush.audio.KnockBearing

/**
 * ONE arrow for every phone, fused from CONFIRMED knocks only (27 Sep 07:05; team: "now that the knock recognition is
 * so good, try bringing back the compass ... enough phones would form a complete 360 view").
 *
 * Each knock the commander has matched across phones ([Closest]) carries every phone's two-mic delay and heading.
 * A delay gives the angle from that phone's top edge; left and right are a mirror the two mics cannot tell, so each
 * phone votes for BOTH world bearings (heading ± angle). Phones lying at different angles have different mirrors: the
 * true bearing collects votes from all of them, each mirror only from one, so it wins. Phones lying parallel share
 * their mirror and cannot settle it (03:35–04:32 today: confidence stuck at 0.44–0.48): lay them at clearly different
 * angles. Headings come from the gyroscope after SYNC COMPASS (the magnetic compass swung 175° in 25 s).
 *
 * Measured before this was written (06:05 run, knocks at each phone in turn): a phone ≥ 1 m from the knock repeats its
 * delay within ±1 sample knock after knock; the phone next to the knock (~0.3 m) scatters (near field, and the knock
 * shakes it), so the knock's clear winner votes at [NEAR_WEIGHT]. Room noises never vote: only judged knocks do, over
 * the same last [WINDOW_KNOCKS] as the closest-phone panel. Right for a knock farther away than the phones are apart
 * (parallax): keep the phones within ~0.5 m of each other, tops pointing different ways.
 */
class KnockDirection {
    companion object {
        const val WINDOW_KNOCKS = 6
        const val HISTORY_MS = 20_000L
        const val MIN_Q = 0.4f
        /** The phone that won the knock by ≥ [NEAR_LEAD_DB] is right next to it: its delay is unreliable. */
        const val NEAR_WEIGHT = 0.3
        const val NEAR_LEAD_DB = 6f
        const val FELT_WEIGHT = 0.3
        const val RESOLVE_RATIO = 1.5
        const val CONF_DEG = 24.0
    }

    /** One phone's vote on one knock: angle from its top edge (0..180), its heading, the weight. */
    data class Vote(val letter: String, val thetaDeg: Double, val headingDeg: Float, val weight: Double)

    data class Result(
        val bearingDeg: Float,          // world (gyro-synced) bearing of the knocking
        val twinDeg: Float?,            // the runner-up direction while unresolved
        val resolved: Boolean,
        val confidence: Float,          // share of the vote mass within ±[CONF_DEG] of the bearing
        val phones: List<String>,       // phones that voted
        val knocks: Int
    )

    private val recent = ArrayDeque<Pair<Long, List<Vote>>>()
    private val lock = Any()

    /** One judged knock. Returns the votes it produced (for the log); moving phones do not vote. */
    fun add(k: Closest.Knock): List<Vote> = synchronized(lock) {
        val votes = ArrayList<Vote>()
        for ((l, d) in k.dirs) {
            if (l !in k.peaks) continue                               // moving: its heading is changing under the knock
            val delay = d.delay ?: continue; val q = d.q ?: continue; val hd = d.heading ?: continue
            if (q < MIN_Q) continue
            val theta = KnockBearing.thetaFromDelay(delay) ?: continue
            var w = q.toDouble()
            if (d.felt) w *= FELT_WEIGHT
            if (l == k.winner && (k.leadDb ?: 99f) >= NEAR_LEAD_DB) w *= NEAR_WEIGHT
            votes.add(Vote(l, theta, hd, w))
        }
        if (votes.isNotEmpty()) {
            recent.addLast(k.tMs to votes)
            while (recent.size > WINDOW_KNOCKS) recent.removeFirst()
        }
        votes
    }

    fun result(nowMs: Long): Result? = synchronized(lock) {
        while (recent.isNotEmpty() && nowMs - recent.first().first > HISTORY_MS) recent.removeFirst()
        val votes = recent.flatMap { it.second }
        if (votes.size < 2) return null
        val hist = DoubleArray(KnockBearing.BINS)
        for (v in votes) {
            KnockBearing.vote(hist, v.headingDeg + v.thetaDeg, v.weight)   // right of the phone...
            KnockBearing.vote(hist, v.headingDeg - v.thetaDeg, v.weight)   // ...or its mirror on the left
        }
        val best = KnockBearing.peakBin(hist, exclude = -1)
        val second = KnockBearing.peakBin(hist, exclude = best)
        val bearing = KnockBearing.refine(hist, best)
        val resolved = hist[second] * RESOLVE_RATIO <= hist[best]
        var near = 0.0; var all = 0.0
        for (b in hist.indices) { all += hist[b]; if (KnockBearing.angDiff(bearing, b * 360.0 / KnockBearing.BINS) <= CONF_DEG) near += hist[b] }
        Result(bearing.toFloat(), if (resolved) null else KnockBearing.refine(hist, second).toFloat(), resolved,
            (near / all.coerceAtLeast(1e-9)).toFloat(), votes.map { it.letter }.distinct().sorted(), recent.size)
    }

    fun reset() = synchronized(lock) { recent.clear() }
}
