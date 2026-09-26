package com.hush.audio

import kotlin.math.sqrt

/**
 * Finds knocks / taps: short, sharp energy spikes far above the window's typical level.
 * Works on the raw 48 kHz window in 10 ms frames, independent of YAMNet.
 */
class TapDetector(sampleRate: Int) {

    data class Result(
        val taps: Int,              // onsets found in this window
        val peakRatio: Float,       // loudest frame / median frame energy
        val intervalsMs: List<Int>, // gaps between consecutive taps, including across the window edge
        val score: Float            // 0..1 "this window contains deliberate tapping"
    )

    companion object {
        const val THRESHOLD = 8f        // frame must be 8x (18 dB) above the window median
        const val ATTACK = 3f           // and 3x louder than the previous frame (sharp onset)
        const val REFRACTORY_FRAMES = 8 // 80 ms: a knock's ring-down is not a second knock
        const val MAX_TAPS_PER_SEC = 8  // faster than this is not a person knocking
    }

    private val frame = sampleRate / 100
    private var lastTapAbsMs: Long = -1

    /** [windowStartMs] is any monotonic clock so intervals can span two windows. */
    fun analyse(pcm: ShortArray, n: Int, windowStartMs: Long): Result {
        val frames = n / frame
        if (frames < 10) return Result(0, 0f, emptyList(), 0f)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0.0
            val base = f * frame
            for (i in 0 until frame) {
                val v = pcm[base + i] / 32768f
                s += v * v
            }
            energy[f] = sqrt(s / frame).toFloat()
        }
        val sorted = energy.copyOf().also { it.sort() }
        val median = sorted[frames / 2].coerceAtLeast(1e-5f)

        var peakRatio = 0f
        var lastOnset = -REFRACTORY_FRAMES
        val onsetsMs = ArrayList<Int>()
        for (f in 1 until frames) {
            val ratio = energy[f] / median
            if (ratio > peakRatio) peakRatio = ratio
            val sharp = energy[f] >= energy[f - 1] * ATTACK
            if (ratio >= THRESHOLD && sharp && f - lastOnset >= REFRACTORY_FRAMES) {
                onsetsMs.add(f * 10)
                lastOnset = f
            }
        }

        val intervals = ArrayList<Int>()
        var prevAbs = lastTapAbsMs
        for (t in onsetsMs) {
            val abs = windowStartMs + t
            if (prevAbs >= 0) {
                val gap = (abs - prevAbs).toInt()
                if (gap in 100..3000) intervals.add(gap)
            }
            prevAbs = abs
        }
        if (onsetsMs.isNotEmpty()) lastTapAbsMs = prevAbs

        val taps = onsetsMs.size
        val score = when {
            taps == 0 || taps > MAX_TAPS_PER_SEC -> 0f
            else -> (0.4f + 0.2f * taps).coerceAtMost(1f)
        }
        return Result(taps, peakRatio, intervals, score)
    }
}
