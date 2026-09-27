package com.hush

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How THIS phone's body relates to the map, from "point & tap" (27 Sep; team agreed to a one-time "point this phone at
 * another phone and tap"). Distances (chirps) give the phones' triangle but not which way it faces in the room nor
 * whether it is a mirror image; the magnetic compass, the two-mic readings and the chirp directions all failed to tell.
 *
 * Each tap stores the map bearing from this phone to the phone it points at and this phone's gyroscope heading then.
 * One tap: rotation = heading − map bearing (assumed not mirrored). Two taps at different phones: the turn between
 * them in the room (headings) against the turn on the map (bearings) says whether the map is mirrored. Afterwards the
 * gyroscope follows the phone's turning; the chirp map keeps its frame between rounds (AutoLocate turns each new
 * triangle onto the previous one), so the taps stay valid while phones move a little at a time.
 */
class Aim {
    data class Ref(val target: String, val mapBearingDeg: Double, val headingDeg: Double)

    /** [mirrored]: the map is the room's mirror image (null = unknown, one tap). [spreadDeg]: how well two taps agree. */
    data class Frame(val rotationDeg: Double, val mirrored: Boolean?, val spreadDeg: Double?, val refs: Int)

    private val refs = ArrayList<Ref>()

    fun tap(target: String, mapBearingDeg: Double, headingDeg: Double) {
        refs.removeAll { it.target == target }
        refs.add(Ref(target, mapBearingDeg, headingDeg))
        while (refs.size > 2) refs.removeAt(0)
    }

    fun reset() = refs.clear()
    fun targets(): List<String> = refs.map { it.target }

    fun frame(): Frame? {
        if (refs.isEmpty()) return null
        if (refs.size == 1) { val r = refs[0]; return Frame(norm(r.headingDeg - r.mapBearingDeg), null, null, 1) }
        fun fit(mirror: Boolean): Pair<Double, Double> {
            val rots = refs.map { norm(it.headingDeg - (if (mirror) -it.mapBearingDeg else it.mapBearingDeg)) }
            val mean = circMean(rots)
            return mean to rots.maxOf { angDiff(it, mean) }
        }
        val (rot, spread) = fit(false); val (rotM, spreadM) = fit(true)
        return if (spreadM < spread) Frame(rotM, true, spreadM, 2) else Frame(rot, false, spread, 2)
    }

    /** Screen angle (clockwise from the phone's top) of a map bearing, with the phone now at [headingNowDeg]. */
    fun screenDeg(mapBearingDeg: Double, headingNowDeg: Double): Double? {
        val f = frame() ?: return null
        val world = (if (f.mirrored == true) -mapBearingDeg else mapBearingDeg) + f.rotationDeg
        return norm(world - headingNowDeg)
    }

    companion object {
        fun norm(a: Double) = ((a % 360.0) + 360.0) % 360.0
        fun angDiff(a: Double, b: Double): Double { val d = abs(norm(a - b)); return if (d > 180.0) 360.0 - d else d }
        fun circMean(xs: List<Double>): Double {
            val s = xs.sumOf { sin(Math.toRadians(it)) }; val c = xs.sumOf { cos(Math.toRadians(it)) }
            return norm(Math.toDegrees(atan2(s, c)))
        }
        /** Map bearing (clockwise from map +y) from [from] to [to], metres. */
        fun bearing(from: Pair<Double, Double>, to: Pair<Double, Double>) =
            norm(Math.toDegrees(atan2(to.first - from.first, to.second - from.second)))
    }
}
