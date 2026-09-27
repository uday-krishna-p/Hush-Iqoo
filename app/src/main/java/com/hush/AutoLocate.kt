package com.hush

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone positions from the inaudible chirps, every round (27 Sep; team: "have it auto locate every few seconds with the
 * chirps").
 *
 * Chirp ranging on these phones is wrong by a FIXED amount per phone: for the team's 1.2 m triangle three rounds read
 * AB 2.03/2.02/2.08, AC 2.02/2.11/2.03, BC 1.46/1.46/1.45 m, i.e. ef39 +0.70/+0.74/+0.73 m, 6a46 +0.14/+0.09/+0.15,
 * 991e +0.13/+0.18/+0.10 on every distance it is part of (its own chirp's arrival is off). So:
 *  1. [bias]: with the phones on the taped layout, each round gives every phone's error (chirp − tape = b_i + b_j).
 *  2. [place]: later rounds subtract the errors, rebuild the triangle from its three sides, and turn (or mirror) it onto
 *     the previous positions, because distances say nothing about which way the triangle faces. The orientation (and
 *     with it the arrows) stays right as long as the phones do not ALL move a lot between two rounds.
 * Three phones only (the solver for more is not built): the others keep their previous positions.
 */
object AutoLocate {
    /** Two calibration rounds must agree this well per phone. */
    const val BIAS_AGREE_M = 0.10
    /** Positions move only when some phone moved more than this (chirp rounds repeat within ±5 cm). */
    const val MOVE_M = 0.08

    /**
     * Each phone's range error measured on the team's phones (27 Sep 06:49–06:52, three rounds on a 1.2 m triangle),
     * used when there is no tape to measure it in the session (team: "stop caring about where the phones are set ...
     * the device needs to keep figuring out each others locations"). Unknown phones get [DEFAULT_OTHER_M]. Whether
     * these hold after an app restart is not known yet: every round logs raw and corrected distances.
     */
    val DEFAULT_BIAS_M = mapOf("ef39" to 0.72, "6a46" to 0.13, "991e" to 0.14)
    const val DEFAULT_OTHER_M = 0.13

    fun defaultBias(name: String): Double = DEFAULT_BIAS_M[name] ?: DEFAULT_OTHER_M

    /** Shortest side a corrected distance may have (phones cannot be closer; the pick jitter is ±5 cm). */
    const val MIN_SIDE_M = 0.15
    /** How much of the stored range errors to try, in order: all of it first, none of it last. */
    val BIAS_SCALES = listOf(1.0, 0.75, 0.5, 0.25, 0.0)

    /**
     * The per-phone errors did not hold on 27 Sep 08:48 (phones 0.6–1.3 m apart): ef39's 0.72 m turned raw
     * 0.61 / 0.78 m into nothing and every round was refused ("first placement failed") for minutes. So: subtract the
     * errors scaled by the first of [BIAS_SCALES] whose distances are all ≥ [MIN_SIDE_M] and pass [ok] (a triangle for
     * the first placement, a fit onto the previous map later). Returns (scale, corrected distances) or null when even
     * the raw distances fail.
     */
    fun fitBias(raw: Map<Pair<String, String>, Double>, bias: Map<String, Double>,
                ok: (Map<Pair<String, String>, Double>) -> Boolean): Pair<Double, Map<Pair<String, String>, Double>>? {
        for (k in BIAS_SCALES) {
            val c = raw.mapValues { (p, d) -> d - k * ((bias[p.first] ?: 0.0) + (bias[p.second] ?: 0.0)) }
            if (c.values.all { it >= MIN_SIDE_M } && ok(c)) return k to c
        }
        return null
    }

    /** First placement of three phones from their distances: [a] at 0,0, [b] on +x, [c] on the +y side. Null if no triangle. */
    fun first(a: String, b: String, c: String, dist: Map<Pair<String, String>, Double>): Map<String, Pair<Double, Double>>? {
        fun d(x: String, y: String) = dist[x to y] ?: dist[y to x]
        val ab = d(a, b) ?: return null; val ac = d(a, c) ?: return null; val bc = d(b, c) ?: return null
        if (ab <= 0 || ac <= 0 || bc <= 0 || ab + ac < bc || ab + bc < ac || ac + bc < ab) return null
        val cx = (ab * ab + ac * ac - bc * bc) / (2 * ab)
        return mapOf(a to (0.0 to 0.0), b to (ab to 0.0), c to (cx to sqrt((ac * ac - cx * cx).coerceAtLeast(0.0))))
    }

    /**
     * Every phone's range error (metres) from one round on a known layout: chirp − tape = b_i + b_j for each pair.
     * Least squares (exact for three phones). Null when the pairs do not connect every phone.
     */
    fun bias(chirp: Map<Pair<String, String>, Double>, tape: Map<Pair<String, String>, Double>): Map<String, Double>? {
        val pairs = chirp.keys.filter { tape.containsKey(it) }
        val names = pairs.flatMap { listOf(it.first, it.second) }.distinct().sorted()
        if (names.size < 3 || pairs.size < names.size) return null
        // Normal equations for e_p = b_i + b_j: (AᵀA) b = Aᵀe, solved by Gauss–Jordan (n is 3 or 4).
        val n = names.size
        val m = Array(n) { DoubleArray(n + 1) }
        for (p in pairs) {
            val e = chirp.getValue(p) - tape.getValue(p)
            val i = names.indexOf(p.first); val j = names.indexOf(p.second)
            for ((r, other) in listOf(i to j, j to i)) { m[r][r] += 1.0; m[r][other] += 1.0; m[r][n] += e }
        }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[piv][c]) < 1e-9) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in 0 until n) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c..n) m[r][k] -= f * m[c][k] }
        }
        return names.withIndex().associate { (i, name) -> name to m[i][n] / m[i][i] }
    }

    /**
     * New positions of three phones from their corrected pair distances, turned onto [prev] (same names). Null when a
     * distance is missing or the three sides cannot form a triangle.
     */
    fun place(dist: Map<Pair<String, String>, Double>, prev: Map<String, Pair<Double, Double>>): Map<String, Pair<Double, Double>>? {
        val names = prev.keys.sorted()
        if (names.size != 3) return null
        fun d(a: String, b: String) = dist[a to b] ?: dist[b to a]
        val (p, q, r) = names
        val dpq = d(p, q) ?: return null; val dpr = d(p, r) ?: return null; val dqr = d(q, r) ?: return null
        if (dpq <= 0 || dpr <= 0 || dqr <= 0) return null
        if (dpq + dpr < dqr || dpq + dqr < dpr || dpr + dqr < dpq) return null
        // p at the origin, q on +x, r above or below (the two mirror images).
        val rx = (dpq * dpq + dpr * dpr - dqr * dqr) / (2 * dpq)
        val ry = sqrt((dpr * dpr - rx * rx).coerceAtLeast(0.0))
        var best: Map<String, Pair<Double, Double>>? = null; var bestErr = Double.MAX_VALUE
        for (s in listOf(1.0, -1.0)) {
            val local = mapOf(p to (0.0 to 0.0), q to (dpq to 0.0), r to (rx to s * ry))
            val (fit, err) = fitOnto(local, prev)
            if (err < bestErr) { bestErr = err; best = fit }
        }
        return best
    }

    /** Rotation + translation (no scaling) of [pts] that best matches [target] (2-D Kabsch). Returns the moved points and the RMS misfit. */
    fun fitOnto(pts: Map<String, Pair<Double, Double>>, target: Map<String, Pair<Double, Double>>): Pair<Map<String, Pair<Double, Double>>, Double> {
        val ks = pts.keys.filter { target.containsKey(it) }
        val cx = ks.map { pts.getValue(it).first }.average(); val cy = ks.map { pts.getValue(it).second }.average()
        val tx = ks.map { target.getValue(it).first }.average(); val ty = ks.map { target.getValue(it).second }.average()
        var sxx = 0.0; var sxy = 0.0
        for (k in ks) {
            val (ax, ay) = pts.getValue(k).let { (it.first - cx) to (it.second - cy) }
            val (bx, by) = target.getValue(k).let { (it.first - tx) to (it.second - ty) }
            sxx += ax * bx + ay * by; sxy += ax * by - ay * bx
        }
        val th = atan2(sxy, sxx); val c = cos(th); val s = sin(th)
        val moved = pts.mapValues { (_, v) -> val x = v.first - cx; val y = v.second - cy; (tx + c * x - s * y) to (ty + s * x + c * y) }
        val err = sqrt(ks.sumOf { val a = moved.getValue(it); val b = target.getValue(it); val e = hypot(a.first - b.first, a.second - b.second); e * e } / ks.size)
        return moved to err
    }
}
