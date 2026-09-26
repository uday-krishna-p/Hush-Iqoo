package com.hush

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the bearing lines from several phones cross (compass plan step 2, docs/PLAN-compass.md).
 *
 * Every phone that hears the knocking says which way it is (its own two-mic arrow, [com.hush.audio.KnockBearing]).
 * A bearing from a known position is a half-line; two or more half-lines meet at the source. This is the plain
 * least-squares point closest to all the lines (2×2 normal equations), accepted only when the lines meet at a
 * usable angle, the point is IN FRONT of every phone and within [maxRange]. A phone whose left/right mirror is not
 * resolved yet contributes both candidates; every combination is tried and the one with the smallest residual
 * wins, provided it wins clearly (with two lines every combination fits exactly, so those stay ambiguous).
 *
 * Frame: positions in metres, bearings in degrees clockwise from +y (map-up or north). Approximation is the goal:
 * the radius is the larger of the fit residual and what a ±[SIGMA_DEG] bearing error means at that range.
 */
object Crossing {
    /** One phone: where it is and its candidate bearings (one when resolved, two while mirrored). */
    data class Line(val letter: String, val x: Double, val y: Double, val bearingsDeg: List<Double>)

    data class Result(
        val x: Double, val y: Double,
        val radius: Double,                 // metres, rough uncertainty
        val chosen: Map<String, Double>,    // letter → the bearing used
        val spreadDeg: Double,              // largest angle between two of the lines (conditioning)
        val residual: Double                // rms perpendicular distance of the point to the lines
    )

    /** When no two lines meet at this angle or more, they are all nearly parallel: the source is far away in that direction, no point. */
    const val MIN_ANGLE_DEG = 20.0
    /** Residuals below this are "exact" (two lines always meet exactly), so they cannot decide between combinations. */
    const val RESIDUAL_FLOOR = 0.05
    /** Bearing error assumed per phone, for the radius. */
    const val SIGMA_DEG = 12.0
    /** With mirrored candidates, the best combination must beat the runner-up by this factor in residual. */
    const val CLEAR_WIN = 0.5
    private const val MAX_COMBOS = 32

    fun solve(lines: List<Line>, maxRange: Double): Result? {
        if (lines.size < 2) return null
        var total = 1
        for (l in lines) total *= l.bearingsDeg.size.coerceAtLeast(1)
        if (total > MAX_COMBOS) return null
        val valid = ArrayList<Result>()
        for (combo in 0 until total) {
            var idx = combo
            val chosen = lines.map { l -> val n = l.bearingsDeg.size; val i = idx % n; idx /= n; l.bearingsDeg[i] }
            fit(lines, chosen, maxRange)?.let { valid.add(it) }
        }
        if (valid.isEmpty()) return null
        valid.sortBy { it.residual }
        val best = valid[0]
        if (valid.size > 1 && total > 1) {
            // Another combination fits about as well and lands somewhere else: ambiguous, wait for a turn or a third phone.
            val second = valid[1]
            val apart = sqrt((best.x - second.x) * (best.x - second.x) + (best.y - second.y) * (best.y - second.y))
            val r1 = maxOf(best.residual, RESIDUAL_FLOOR); val r2 = maxOf(second.residual, RESIDUAL_FLOOR)
            if (apart > best.radius && r1 > CLEAR_WIN * r2) return null
        }
        return best
    }

    private fun fit(lines: List<Line>, chosen: List<Double>, maxRange: Double): Result? {
        var spread = 0.0
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            var d = abs(((chosen[i] - chosen[j]) % 180.0 + 180.0) % 180.0)
            if (d > 90.0) d = 180.0 - d
            spread = maxOf(spread, d)
        }
        if (spread < MIN_ANGLE_DEG) return null
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        for (i in lines.indices) {
            val th = Math.toRadians(chosen[i]); val ux = sin(th); val uy = cos(th)
            val p11 = 1 - ux * ux; val p12 = -ux * uy; val p22 = 1 - uy * uy   // projector onto the line's normal
            a11 += p11; a12 += p12; a22 += p22
            b1 += p11 * lines[i].x + p12 * lines[i].y
            b2 += p12 * lines[i].x + p22 * lines[i].y
        }
        val det = a11 * a22 - a12 * a12
        if (det < 1e-9) return null
        val x = (a22 * b1 - a12 * b2) / det
        val y = (a11 * b2 - a12 * b1) / det
        var sumSq = 0.0; var sumRange = 0.0
        for (i in lines.indices) {
            val th = Math.toRadians(chosen[i]); val ux = sin(th); val uy = cos(th)
            val dx = x - lines[i].x; val dy = y - lines[i].y
            val along = dx * ux + dy * uy
            if (along <= 0.0 || along > maxRange) return null      // behind this phone, or too far to trust
            val perp = -dx * uy + dy * ux
            sumSq += perp * perp; sumRange += along
        }
        val residual = sqrt(sumSq / lines.size)
        val meanRange = sumRange / lines.size
        val radius = maxOf(residual, meanRange * tan(Math.toRadians(SIGMA_DEG)), 0.3)
        return Result(x, y, radius, lines.indices.associate { lines[it].letter to chosen[it] }, spread, residual)
    }

    /** Bearing from (x1, y1) to (x2, y2), degrees clockwise from +y. */
    fun bearing(x1: Double, y1: Double, x2: Double, y2: Double): Double =
        ((Math.toDegrees(atan2(x2 - x1, y2 - y1)) % 360.0) + 360.0) % 360.0
}
