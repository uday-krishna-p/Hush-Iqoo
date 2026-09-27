package com.hush.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How tonal is this second? A pressure-cooker whistle (or a kettle, a beep) is one strong line in the spectrum;
 * frying, running water, speech and a TV spread their energy. Averages the magnitude spectrum of the last
 * [FRAMES] frames of [N] samples (16 kHz audio, so 15.6 Hz per bin) and compares the strongest bin between
 * [LOW_HZ] and [HIGH_HZ] with the median bin of that band. Cheap: four 1024-point FFTs.
 */
object Tonality {
    const val N = 1024
    const val FRAMES = 4
    const val SAMPLE_RATE = 16_000
    const val LOW_HZ = 400
    const val HIGH_HZ = 5000

    data class Result(
        val peakHz: Float,        // frequency of the strongest bin in the band
        val peakToMedian: Float,  // strongest bin / median bin of the band (1 = flat noise, ≥ 8 = a clear tone)
        val peakLevel: Float      // magnitude of that bin (relative units)
    )

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val cosT = DoubleArray(N / 2) { cos(-2.0 * PI * it / N) }
    private val sinT = DoubleArray(N / 2) { sin(-2.0 * PI * it / N) }
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val mag = FloatArray(N / 2)

    /** Audio thread only (shared scratch buffers). */
    @Synchronized fun measure(pcm16k: FloatArray, n: Int): Result? {
        if (n < N) return null
        java.util.Arrays.fill(mag, 0f)
        val frames = minOf(FRAMES, n / N)
        for (f in 0 until frames) {
            val start = n - (f + 1) * N
            for (i in 0 until N) { re[i] = (pcm16k[start + i] * window[i]).toDouble(); im[i] = 0.0 }
            fft(re, im)
            for (k in 0 until N / 2) mag[k] += sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
        }
        val lo = LOW_HZ * N / SAMPLE_RATE
        val hi = HIGH_HZ * N / SAMPLE_RATE
        var peakK = lo
        for (k in lo..hi) if (mag[k] > mag[peakK]) peakK = k
        val band = FloatArray(hi - lo + 1) { mag[lo + it] }
        band.sort()
        val median = band[band.size / 2]
        val ratio = if (median > 1e-9f) mag[peakK] / median else 0f
        return Result(peakK.toFloat() * SAMPLE_RATE / N, ratio, mag[peakK] / frames)
    }

    /**
     * The most tonal quarter-second anywhere in the second (not just the last one, as [measure]): a doorbell's ding may
     * be over by the end of the second, and in a crowd one clear line in one frame is what stands out. [LOW_HZ]..[HIGH_HZ]
     * covers chimes, buzzers and electronic melodies.
     */
    @Synchronized fun measureBest(pcm16k: FloatArray, n: Int): Result? {
        var best: Result? = null
        var end = n
        while (end >= N * FRAMES) {
            val r = measure(pcm16k, end) ?: break
            if (best == null || r.peakToMedian > best.peakToMedian) best = r
            end -= N * FRAMES
        }
        return best
    }

    /** In-place iterative radix-2 FFT, N a power of two. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until len / 2) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val ur = re[i + m]; val ui = im[i + m]
                    val vr = re[i + m + len / 2] * wr - im[i + m + len / 2] * wi
                    val vi = re[i + m + len / 2] * wi + im[i + m + len / 2] * wr
                    re[i + m] = ur + vr; im[i + m] = ui + vi
                    re[i + m + len / 2] = ur - vr; im[i + m + len / 2] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
