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
        val score: Float,           // 0..1 "this window contains deliberate tapping"
        val onsetsAbsMs: List<Long>, // absolute onset times (windowStartMs + offset) for the rhythm tracker
        val rejectedSustained: Int = 0 // sharp onsets that did not ring down (cough, syllable, chair)
    )

    companion object {
        // Tuned 26 Sep on two iQOO I2501 recordings (knocks / 3-2 pattern / speech / quiet):
        // 5 / 2 / lookback 3 gave 0 false taps in quiet and speech, and caught 20 of 23 knock seconds.
        const val THRESHOLD = 3f        // frame must be 3x (9.5 dB) above the window median (5 → 4 → 3 on 26 Sep, for knocks at a distance)
        const val ATTACK = 2f           // and 2x louder than the loudest of the previous LOOKBACK frames
        const val LOOKBACK = 3
        const val REFRACTORY_FRAMES = 8 // 80 ms: a knock's ring-down is not a second knock
        const val MAX_TAPS_PER_SEC = 8  // faster than this is not a person knocking
        // A knock rings down fast: on the recordings its energy 100 ms later was 3–27 % of the onset frame.
        // A cough, a syllable or a dragged chair stays loud. Onsets that stay loud are not taps.
        const val DECAY_FRAMES = 10
        const val DECAY_MAX = 0.60f     // distant knocks carry room echo
    }

    private val frame = sampleRate / 100
    private var lastTapAbsMs: Long = -1

    /** [windowStartMs] is any monotonic clock so intervals can span two windows. */
    fun analyse(pcm: ShortArray, n: Int, windowStartMs: Long): Result {
        val frames = n / frame
        if (frames < 10) return Result(0, 0f, emptyList(), 0f, emptyList(), 0)
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
        var rejected = 0
        var lastOnset = -REFRACTORY_FRAMES
        val onsetsMs = ArrayList<Int>()
        for (f in 1 until frames) {
            val ratio = energy[f] / median
            if (ratio > peakRatio) peakRatio = ratio
            var prev = 1e-6f
            for (k in 1..LOOKBACK) if (f - k >= 0 && energy[f - k] > prev) prev = energy[f - k]
            val sharp = energy[f] >= prev * ATTACK
            if (ratio >= THRESHOLD && sharp && f - lastOnset >= REFRACTORY_FRAMES) {
                // Ring-down check; if the onset is too close to the window edge to check, accept it.
                val later = f + DECAY_FRAMES
                val rangDown = later >= frames || energy[later] <= energy[f] * DECAY_MAX
                if (rangDown) {
                    onsetsMs.add(f * 10)
                    lastOnset = f
                } else {
                    rejected++
                }
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
        return Result(taps, peakRatio, intervals, score, onsetsMs.map { windowStartMs + it }, rejected)
    }
}
