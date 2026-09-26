package com.hush.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.hush.HLog
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ranging chirp: an 80 ms linear sweep 2–6 kHz with a Hann window, and the matched filter that finds it
 * in a recorded stretch of audio to the sample. Same template on every phone.
 */
object Chirp {
    const val SAMPLE_RATE = 48_000
    const val DURATION_MS = 80               // was 40: twice the length = +3 dB of matched-filter gain in noise
    const val F_START = 2000.0
    const val F_END = 6000.0
    const val SPEED_OF_SOUND = 343f          // m/s at ~20 °C
    const val VOLUME_FRACTION = 0.4f         // of max alarm volume: 0.7 → 0.9 for range, 0.4 on 26 Sep 23:15 at the team's request (loud on a table); raise again for a hall

    val template: FloatArray by lazy {
        val n = SAMPLE_RATE * DURATION_MS / 1000
        val t = FloatArray(n)
        val dur = n.toDouble() / SAMPLE_RATE
        for (i in 0 until n) {
            val tt = i.toDouble() / SAMPLE_RATE
            val phase = 2 * PI * (F_START * tt + (F_END - F_START) * tt * tt / (2 * dur))
            val hann = 0.5 * (1 - cos(2 * PI * i / (n - 1)))
            t[i] = (sin(phase) * hann).toFloat()
        }
        t
    }

    /**
     * The chirp's quadrature partner: the same sweep with cos instead of sin under the same window (its Hilbert
     * transform, exact enough because the window varies far more slowly than a 2 kHz cycle). Correlating with
     * both and taking sqrt(I² + Q²) gives the smooth envelope of the correlation.
     */
    val quadrature: FloatArray by lazy {
        val n = SAMPLE_RATE * DURATION_MS / 1000
        val q = FloatArray(n)
        val dur = n.toDouble() / SAMPLE_RATE
        for (i in 0 until n) {
            val tt = i.toDouble() / SAMPLE_RATE
            val phase = 2 * PI * (F_START * tt + (F_END - F_START) * tt * tt / (2 * dur))
            val hann = 0.5 * (1 - cos(2 * PI * i / (n - 1)))
            q[i] = (cos(phase) * hann).toFloat()
        }
        q
    }

    /** Coarse search runs at 48 kHz / DECIMATE = 16 kHz: the 2–6 kHz chirp fits below its 8 kHz limit. */
    private const val DECIMATE = 3
    /** The 48 kHz refinement looks this many samples either side of the coarse pick. */
    private const val REFINE = 12

    /** The template averaged in threes, matching how the audio is decimated for the coarse search. */
    private val template16: FloatArray by lazy {
        val t = template
        FloatArray(t.size / DECIMATE) { k -> (t[DECIMATE * k] + t[DECIMATE * k + 1] + t[DECIMATE * k + 2]) / DECIMATE }
    }

    /** Plays the chirp on the alarm channel. Returns immediately. */
    fun play(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        AlarmVolume.raise(am, VOLUME_FRACTION)
        try {
            val n = template.size
            val pad = SAMPLE_RATE / 20   // 50 ms of silence before, so the start is not clipped by the track ramp-up
            val buf = ShortArray(pad + n + pad)
            for (i in 0 until n) buf[pad + i] = (template[i] * 0.95 * 32767).toInt().toShort()
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({
                try { track.release() } catch (e: Exception) { HLog.d("Chirp: release failed $e") }
                AlarmVolume.restore(am)
            }, 600)
            HLog.d("Chirp: played")
        } catch (e: Exception) {
            HLog.d("Chirp play failed: $e")
            AlarmVolume.restore(am)
        }
    }

    data class Detection(val offset: Int, val peak: Float, val ratio: Float, val fine: Double = offset.toDouble(), val firstArrivalShift: Int = 0)

    /** Look back this far before the strongest correlation peak for an earlier, direct arrival (30 ms ≈ 10 m of extra path). */
    const val FIRST_ARRIVAL_WINDOW = 1440
    /** An earlier envelope peak must reach this fraction of the strongest to count as the direct arrival. */
    const val FIRST_ARRIVAL_FRACTION = 0.3
    /** ...and stand this many times above the median envelope, so noise before a weak chirp is never taken. */
    const val FIRST_ARRIVAL_NOISE = 6.0

    /**
     * FFT plan for one transform size: bit-reversal table, twiddle factors, and the conjugated spectrum of the
     * zero-padded 16 kHz template. Built once per size (the 5 s search always uses 2^17) and shared by all searches.
     */
    private class Plan(val n: Int) {
        val rev = IntArray(n)
        val cos = DoubleArray(n / 2)
        val sin = DoubleArray(n / 2)
        val tRe = DoubleArray(n)
        val tIm = DoubleArray(n)
        init {
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
            for (k in 0 until n / 2) { val a = 2 * PI * k / n; cos[k] = kotlin.math.cos(a); sin[k] = kotlin.math.sin(a) }
            val t = template16
            for (i in t.indices) tRe[i] = t[i].toDouble()
            fft(tRe, tIm, this, false)
            for (i in 0 until n) tIm[i] = -tIm[i]   // conjugate: multiplying by it correlates instead of convolving
        }
    }
    @Volatile private var plan: Plan? = null
    private fun planFor(n: Int): Plan = synchronized(this) { plan?.takeIf { it.n == n } ?: Plan(n).also { plan = it } }

    /** In-place iterative radix-2 complex FFT (inverse includes the 1/n scaling). */
    private fun fft(re: DoubleArray, im: DoubleArray, p: Plan, inverse: Boolean) {
        val n = p.n
        for (i in 0 until n) {
            val j = p.rev[i]
            if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = p.cos[k]; val wi = sign * p.sin[k]
                    val xr = re[j + half]; val xi = im[j + half]
                    val vr = xr * wr - xi * wi; val vi = xr * wi + xi * wr
                    val ur = re[j]; val ui = im[j]
                    re[j] = ur + vr; im[j] = ui + vi
                    re[j + half] = ur - vr; im[j + half] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val s = 1.0 / n; for (i in 0 until n) { re[i] *= s; im[i] *= s } }
    }

    /**
     * Matched filter over [audio]: returns the sample offset where the chirp starts, the peak of the correlation
     * envelope, how far the peak stands above the typical correlation level (credible when ratio > 5), and the
     * sub-sample position of the peak.
     *
     * How (rewritten 27 Sep): (1) coarse search on a 16 kHz copy (samples averaged in threes) with FFTs, as the
     * ANALYTIC correlation (negative frequencies removed), whose magnitude is the smooth envelope of the
     * correlation; (2) the chosen arrival refined at 48 kHz within ±[REFINE] samples by correlating directly with
     * the template and its [quadrature] partner. A 5 s search took 1.2–5.6 s on the phone sample by sample.
     * Peaks are picked on the envelope, so the pick can no longer slip by one 4 kHz carrier cycle (the old code
     * moved 11–12 samples early on every self-detection and put the two mics up to 43 samples apart).
     *
     * Direct path first: in the calibration room the direct sound between phones lying on a table was often
     * weaker than a reflection 3–20 ms later (replayed 26 Sep audio: C heard A's chirp with the envelope flat
     * until −1004 samples, then a direct arrival at 0.45–0.67 of the strongest peak, which came 20 ms later).
     * So: strongest envelope peak, then the EARLIEST envelope peak within [FIRST_ARRIVAL_WINDOW] before it that
     * reaches [FIRST_ARRIVAL_FRACTION] of it and [FIRST_ARRIVAL_NOISE] × the median envelope. The look-back is
     * fixed to the strongest peak (the old loop slid with every earlier peak and walked back up to 4407 samples).
     */
    fun detect(audio: ShortArray, n: Int): Detection {
        val t = template
        val q = quadrature
        val m = t.size
        val n16 = n / DECIMATE
        val m16 = template16.size
        if (n16 <= m16 + 2) return Detection(-1, 0f, 0f)
        // ---- Stage 1: coarse, 16 kHz, FFT ----
        var size = 1
        while (size < n16) size = size shl 1
        val p = planFor(size)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until n16) {
            val j = DECIMATE * i
            re[i] = (audio[j] + audio[j + 1] + audio[j + 2]) / (DECIMATE * 32768.0)
        }
        fft(re, im, p, false)
        // Multiply by the template's conjugate spectrum and keep positive frequencies only (×2), so the inverse
        // transform is the analytic correlation: real part = correlation, magnitude = its envelope.
        val half = size / 2
        for (k in 0 until size) {
            val xr = re[k]; val xi = im[k]
            var yr = xr * p.tRe[k] - xi * p.tIm[k]
            var yi = xr * p.tIm[k] + xi * p.tRe[k]
            if (k in 1 until half) { yr *= 2; yi *= 2 } else if (k > half) { yr = 0.0; yi = 0.0 }
            re[k] = yr; im[k] = yi
        }
        fft(re, im, p, true)
        val count = n16 - m16          // valid lags: the template lies wholly inside the audio
        val env = FloatArray(count)
        var sumAbs = 0.0
        var best = 0f
        var strongestAt = 0
        for (i in 0 until count) {
            val e = kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i]).toFloat()
            env[i] = e
            sumAbs += kotlin.math.abs(re[i])
            if (e > best) { best = e; strongestAt = i }
        }
        // Same "ratio" meaning as before: peak over the mean |correlation|, so the thresholds (5) still apply.
        val mean = (sumAbs / count).toFloat().coerceAtLeast(1e-9f)
        // Median envelope from every 4th lag (plenty for a noise level, and quick to sort).
        val sample = FloatArray((count + 3) / 4) { env[it * 4] }
        sample.sort()
        val median = sample[sample.size / 2]
        val threshold = maxOf(best * FIRST_ARRIVAL_FRACTION, median * FIRST_ARRIVAL_NOISE).toFloat()
        var at16 = strongestAt
        for (i in maxOf(1, strongestAt - FIRST_ARRIVAL_WINDOW / DECIMATE) until strongestAt) {
            if (env[i] >= threshold && env[i] >= env[i - 1] && env[i] >= env[i + 1]) { at16 = i; break }
        }
        val ratio = env[at16] / mean
        val shift = DECIMATE * (strongestAt - at16)
        // ---- Stage 2: refine at 48 kHz around the coarse pick (in-phase and quadrature correlation) ----
        val c48 = DECIMATE * at16
        val lo = maxOf(0, c48 - REFINE)
        val hi = minOf(n - m, c48 + REFINE)
        if (hi < lo) return Detection(c48, 0f, ratio, c48.toDouble(), shift)
        val env48 = DoubleArray(hi - lo + 1)
        for (lag in lo..hi) {
            var si = 0.0; var sq = 0.0
            for (k in 0 until m) { val v = audio[lag + k].toDouble(); si += v * t[k]; sq += v * q[k] }
            env48[lag - lo] = kotlin.math.sqrt(si * si + sq * sq) / 32768.0
        }
        // The envelope peak nearest the coarse pick (a local maximum), else the largest value in the range.
        var at = -1
        for (k in 1 until env48.size - 1) {
            if (env48[k] >= env48[k - 1] && env48[k] >= env48[k + 1] &&
                (at < 0 || kotlin.math.abs(lo + k - c48) < kotlin.math.abs(at - c48))) at = lo + k
        }
        if (at < 0) { var bi = 0; for (k in env48.indices) if (env48[k] > env48[bi]) bi = k; at = lo + bi }
        var fine = at.toDouble()
        val k = at - lo
        if (k in 1 until env48.size - 1) {
            val y0 = env48[k - 1]; val y1 = env48[k]; val y2 = env48[k + 1]
            val denom = y0 - 2 * y1 + y2
            if (denom < 0) fine = at + (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5)
        }
        return Detection(at, env48[k].toFloat(), ratio, fine, shift)
    }

    /** Builds the FFT tables and lets the runtime compile the search before the first chirp (the first call took 4 s). */
    fun warmUp() {
        val t0 = System.nanoTime()
        val n = SAMPLE_RATE * 5
        val a = ShortArray(n) { ((it * 7919) % 200 - 100).toShort() }
        repeat(3) { detect(a, n) }
        HLog.d("Chirp: matched filter warmed up in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    fun samplesToMetres(samples: Double): Double = samples / SAMPLE_RATE * SPEED_OF_SOUND

    @Suppress("unused")
    private fun rms(x: FloatArray): Float { var s = 0.0; for (v in x) s += v * v; return sqrt(s / x.size).toFloat() }
}
