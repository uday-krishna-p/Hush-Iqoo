package com.hush

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Positions from GPS (compass plan step 3, docs/PLAN-compass.md): outdoors, with sky, every phone's GPS fix is
 * already in its once-a-second event. On a real site the phones are 10–30 m apart and a ±3–5 m fix places them
 * well enough for an arrow (a 4 m error at 20 m is ±11°). Indoors GPS gives nothing and the hand layout stays.
 *
 * Rules: every phone needs [MIN_SAMPLES] fixes in the last [MAX_AGE_MS] with a median accuracy ≤ [MAX_ACC_M];
 * the median fix per phone is projected to metres east/north of the commander ("A"); accepted only when the
 * closest pair of phones is at least [SEPARATION_FACTOR] × the worst accuracy apart, so a 3 m error cannot
 * flip a 4 m triangle. Pure maths, no Android: tested on the laptop.
 */
object GpsLayout {
    data class Sample(val lat: Double, val lon: Double, val accM: Float, val atMs: Long)
    data class Result(val positions: LinkedHashMap<String, Pair<Double, Double>>, val worstAccM: Double, val minPairM: Double)

    const val MAX_AGE_MS = 60_000L
    const val MIN_SAMPLES = 3
    const val MAX_ACC_M = 20.0
    const val SEPARATION_FACTOR = 2.0
    private const val M_PER_DEG_LAT = 110_574.0
    private const val M_PER_DEG_LON_EQUATOR = 111_320.0

    /**
     * [samples] letter → its recent fixes; [letters] the phones that must all be placed (A first).
     * Returns the positions in metres east/north of A, or null and a one-line reason.
     */
    fun solve(samples: Map<String, List<Sample>>, letters: Collection<String>, nowMs: Long): Pair<Result?, String> {
        val med = LinkedHashMap<String, Triple<Double, Double, Double>>()   // letter → (lat, lon, acc)
        for (l in letters) {
            val fresh = samples[l]?.filter { nowMs - it.atMs <= MAX_AGE_MS } ?: emptyList()
            if (fresh.size < MIN_SAMPLES) return null to "$l has ${fresh.size} GPS fixes in the last minute (need $MIN_SAMPLES)"
            val acc = median(fresh.map { it.accM.toDouble() })
            if (acc > MAX_ACC_M) return null to "$l GPS accuracy ±%.0f m (need ≤ %.0f m)".format(acc, MAX_ACC_M)
            med[l] = Triple(median(fresh.map { it.lat }), median(fresh.map { it.lon }), acc)
        }
        val a = med["A"] ?: return null to "the commander has no GPS fix"
        if (med.size < 2) return null to "only the commander has a GPS fix"
        val cosLat = cos(Math.toRadians(a.first))
        val pos = LinkedHashMap<String, Pair<Double, Double>>()
        for ((l, m) in med) pos[l] = ((m.second - a.second) * cosLat * M_PER_DEG_LON_EQUATOR) to ((m.first - a.first) * M_PER_DEG_LAT)
        val worst = med.values.maxOf { it.third }
        var minPair = Double.MAX_VALUE
        val ps = pos.values.toList()
        for (i in ps.indices) for (j in i + 1 until ps.size) minPair = minOf(minPair, hypot(ps[i].first - ps[j].first, ps[i].second - ps[j].second))
        if (minPair < SEPARATION_FACTOR * worst)
            return null to "phones too close for GPS: nearest pair %.0f m, need ≥ %.0f m (%.0f× the worst accuracy ±%.0f m)".format(minPair, SEPARATION_FACTOR * worst, SEPARATION_FACTOR, worst)
        return Result(pos, worst, minPair) to "ok"
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return s[s.size / 2] }
}
