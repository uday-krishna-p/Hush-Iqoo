package com.hush.audio

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Simple 200–3000 Hz band-pass: a one-pole high-pass followed by a one-pole low-pass.
 * Keeps its own filter state, so use one instance per audio stream.
 */
class BandPass(sampleRate: Int, lowHz: Float = 200f, highHz: Float = 3000f) {
    private val aHp: Float
    private val aLp: Float
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lpPrev = 0f

    init {
        val dt = 1f / sampleRate
        val rcHp = 1f / (2f * PI.toFloat() * lowHz)
        aHp = rcHp / (rcHp + dt)
        val rcLp = 1f / (2f * PI.toFloat() * highHz)
        aLp = dt / (rcLp + dt)
    }

    /** Filters [n] 16-bit samples from [input] into [out] as floats in -1..1. */
    fun process(input: ShortArray, n: Int, out: FloatArray) {
        for (i in 0 until n) {
            val x = input[i] / 32768f
            val hp = aHp * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = hp
            lpPrev += aLp * (hp - lpPrev)
            out[i] = lpPrev
        }
    }
}

object Dsp {
    /** Root-mean-square of the first [n] samples, 0..1. */
    fun rms(x: FloatArray, n: Int): Float {
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) sum += x[i] * x[i]
        return sqrt(sum / n).toFloat()
    }

    /**
     * 48 kHz → 16 kHz by averaging every 3 samples (a crude low-pass plus decimation, good enough for YAMNet).
     * Writes floats in -1..1 into [out] and returns how many were written.
     */
    fun downsample3(input: ShortArray, n: Int, out: FloatArray): Int {
        val m = minOf(n / 3, out.size)
        for (i in 0 until m) {
            val j = 3 * i
            out[i] = (input[j] + input[j + 1] + input[j + 2]) / (3f * 32768f)
        }
        return m
    }
}
