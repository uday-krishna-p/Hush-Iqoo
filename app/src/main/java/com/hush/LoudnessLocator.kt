package com.hush

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * WHERE the knocking is, from how loud each phone heard it (27 Sep 06:30, after the closest-phone panel worked).
 * Sound gets quieter as 1/distance (6 dB per doubling), so for one knock  peak_i × r_i  is the same at every phone
 * (the knock's unknown strength). A candidate spot fits a knock when ln(peak_i) + ln(r_i) is equal across the phones
 * that heard it; the spread from equal is the misfit. Summed over the recent clear knocks, the best-fitting spot on a
 * grid is the answer, and the spots that fit almost as well are the "likely region" (its size is the ± shown).
 *
 * Needs the phones' positions (metres, hand layout) and knocks heard by ≥ 3 of them. Two phones only give a circle
 * of equally good spots, and inside the narrow search strip around two phones that circle looked like a precise spot
 * (test, 27 Sep): with two phones there is no point, the screen keeps to "closest phone". No clocks, compass or chirps.
 * Measured on the phones before this was written (06:00–06:05 runs, phones 1.5–2.5 m apart): a knock ~0.3 m from a
 * phone led the next phone by 10–33 dB; the 1/r law predicts 16–18 dB. Room echo makes it rough, hence σ = 6 dB.
 */
object LoudnessLocator {
    const val CELL_M = 0.05
    /** Search only this far beyond the phones. Two loudness ratios are two circles, which cross at TWO points; the
     *  second lies outside the phones, and with a 1 m margin 2 dB of noise put a knock 0.3 m from B 0.9–1.2 m away
     *  (simulated). So: put the phones AROUND the area to search. Simulated in the team's 2 × 1.5 m corners: 2 dB noise
     *  0.05–0.35 m off (up to 0.6 m near A, the corner far from both others), 5 dB noise mostly 0.2–0.5 m, a few 0.8–1.5 m. */
    const val MARGIN_M = 0.3
    /** Misfit scale: 6 dB (a factor of 2 in level) is one unit. */
    private val SIGMA = ln(2.0)
    /** Closer than this the 1/r law breaks down (the phone is not a point, knocks are on the floor next to it). */
    const val R_MIN_M = 0.15
    /** Cells whose total misfit is within this of the best form the likely region. */
    const val REGION_NATS = 2.0
    const val MIN_PHONES = 3

    data class Point(
        val x: Double, val y: Double,     // metres, same frame as the positions
        val radiusM: Double,              // RMS distance of the likely region from the point
        val knocks: Int,                  // knocks used (heard by ≥ 3 placed phones)
        val nearest: String,              // the phone closest to the point
        val nearestM: Double
    )

    /** [knocks]: per knock, each phone's peak (0..1). [pos]: phone → (x, y) metres. Null when nothing usable. */
    fun locate(knocks: List<Map<String, Float>>, pos: Map<String, Pair<Double, Double>>): Point? {
        val usable = knocks.map { k -> k.filter { (l, p) -> p > 0f && pos.containsKey(l) } }.filter { it.size >= MIN_PHONES }
        if (usable.isEmpty() || pos.size < MIN_PHONES) return null
        val xs = pos.values.map { it.first }; val ys = pos.values.map { it.second }
        val x0 = xs.min() - MARGIN_M; val y0 = ys.min() - MARGIN_M
        val nx = ((xs.max() + MARGIN_M - x0) / CELL_M).toInt() + 1
        val ny = ((ys.max() + MARGIN_M - y0) / CELL_M).toInt() + 1
        val cost = DoubleArray(nx * ny)
        var best = 0
        for (j in 0 until ny) for (i in 0 until nx) {
            val x = x0 + i * CELL_M; val y = y0 + j * CELL_M
            var c = 0.0
            for (k in usable) {
                val e = k.map { (l, p) -> val q = pos.getValue(l); ln(p.toDouble()) + ln(max(hypot(x - q.first, y - q.second), R_MIN_M)) }
                val mean = e.average()
                // Heavy-tailed (Cauchy) misfit: one echo-spoiled phone cannot drag the spot across the room.
                for (v in e) { val z = (v - mean) / SIGMA; c += ln(1 + z * z) }
            }
            cost[j * nx + i] = c
            if (c < cost[best]) best = j * nx + i
        }
        val bx = x0 + (best % nx) * CELL_M; val by = y0 + (best / nx) * CELL_M
        var n = 0; var s2 = 0.0
        for (idx in cost.indices) if (cost[idx] - cost[best] <= REGION_NATS) {
            val dx = x0 + (idx % nx) * CELL_M - bx; val dy = y0 + (idx / nx) * CELL_M - by
            s2 += dx * dx + dy * dy; n++
        }
        val near = pos.minBy { (_, q) -> hypot(bx - q.first, by - q.second) }
        return Point(bx, by, max(sqrt(s2 / n), CELL_M), usable.size, near.key, hypot(bx - near.value.first, by - near.value.second))
    }
}
