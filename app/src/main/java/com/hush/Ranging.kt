package com.hush

import kotlin.math.sqrt

/**
 * Two-way acoustic ranging maths ("BeepBeep"). Every phone hears every chirp on its own sample clock.
 * For phones i and j:  D = c/2 · [ (t_i(j) − t_i(i)) − (t_j(j) − t_j(i)) ] / fs + speakerMicOffset,
 * where t_x(y) is the sample at which phone x heard phone y's chirp. Clock offsets and playback latency cancel.
 */
object Ranging {

    /** heard[hearer][from] = sample index. */
    fun pairDistance(heard: Map<String, Map<String, Long>>, i: String, j: String): Double? {
        val tii = heard[i]?.get(i) ?: return null
        val tij = heard[i]?.get(j) ?: return null
        val tjj = heard[j]?.get(j) ?: return null
        val tji = heard[j]?.get(i) ?: return null
        val samples = ((tij - tii) - (tjj - tji)) / 2.0
        val d = samples / com.hush.audio.Chirp.SAMPLE_RATE * com.hush.audio.Chirp.SPEED_OF_SOUND + SPEAKER_MIC_OFFSET_M
        return if (d.isNaN() || d < 0.0 || d > 60.0) null else d
    }

    /** Roughly the mean speaker-to-mic distance inside a phone; both phones' own chirps are heard that far from the speaker. */
    const val SPEAKER_MIC_OFFSET_M = 0.12

    /**
     * Places three phones from their three pairwise distances: A at origin, B on the +x axis, C above.
     * Returns letter → (x, y) in metres, or null if the triangle is impossible.
     */
    fun triangle(a: String, b: String, c: String, dAB: Double, dAC: Double, dBC: Double): Map<String, Pair<Double, Double>>? {
        if (dAB <= 0.05) return null
        val x = (dAC * dAC - dBC * dBC + dAB * dAB) / (2 * dAB)
        val y2 = dAC * dAC - x * x
        val y = if (y2 > 0) sqrt(y2) else 0.0
        return mapOf(a to (0.0 to 0.0), b to (dAB to 0.0), c to (x to y))
    }

    /** Fits metre positions into the unit square with a margin, preserving shape. */
    fun toUnitSquare(pos: Map<String, Pair<Double, Double>>, margin: Double = 0.15): Map<String, Pair<Float, Float>> {
        val xs = pos.values.map { it.first }; val ys = pos.values.map { it.second }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY, 0.5)
        val scale = (1 - 2 * margin) / span
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        return pos.mapValues { (_, p) ->
            ((0.5 + (p.first - cx) * scale).toFloat()) to ((0.5 - (p.second - cy) * scale).toFloat())   // y up on the map
        }
    }
}
