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
 * The ranging chirp: a 40 ms linear sweep 2–6 kHz with a Hann window, and the matched filter that finds it
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

    /** A later, stronger correlation peak within this many samples is taken to be a reflection of an earlier one (30 ms ≈ 10 m of path). */
    const val FIRST_ARRIVAL_WINDOW = 1440
    /** An earlier peak must be at least this fraction of the strongest to count as the direct arrival. */
    const val FIRST_ARRIVAL_FRACTION = 0.6f

    /**
     * Matched filter over [audio]: returns the sample offset where the chirp starts, the peak correlation,
     * how far the peak stands above the typical correlation level (credible when ratio > 6), and the
     * sub-sample peak position from a parabola through the three highest points (for direction of arrival).
     */
    fun detect(audio: ShortArray, n: Int): Detection {
        val t = template
        val m = t.size
        if (n <= m) return Detection(-1, 0f, 0f)
        val x = FloatArray(n) { audio[it] / 32768f }
        val out = FloatArray(n - m)
        var best = 0f
        var bestAt = -1
        var sumAbs = 0.0
        for (i in 0 until n - m) {
            var acc = 0f
            var k = 0
            while (k < m) {
                acc += x[i + k] * t[k]
                k++
            }
            val a = if (acc < 0) -acc else acc
            out[i] = a
            sumAbs += a
            if (a > best) { best = a; bestAt = i }
        }
        val mean = (sumAbs / (n - m)).toFloat().coerceAtLeast(1e-9f)
        // Direct path first: on a table the strongest peak was sometimes an echo ~40 ms late (7.8 m instead of
        // 1.2 m on 26 Sep 23:13). Walk back from the strongest peak and take the earliest local peak that is
        // still a large fraction of it.
        val strongestAt = bestAt
        var i = bestAt - 1
        val floorVal = best * FIRST_ARRIVAL_FRACTION
        while (i > 0 && bestAt - i <= FIRST_ARRIVAL_WINDOW) {
            if (out[i] >= floorVal && out[i] >= out[i - 1] && out[i] >= out[i + 1]) bestAt = i
            i--
        }
        var fine = bestAt.toDouble()
        if (bestAt in 1 until out.size - 1) {
            val y0 = out[bestAt - 1].toDouble(); val y1 = out[bestAt].toDouble(); val y2 = out[bestAt + 1].toDouble()
            val denom = y0 - 2 * y1 + y2
            if (denom < 0) fine = bestAt + 0.5 * (y0 - y2) / denom
        }
        return Detection(bestAt, out[bestAt], out[bestAt] / mean, fine, strongestAt - bestAt)
    }

    fun samplesToMetres(samples: Double): Double = samples / SAMPLE_RATE * SPEED_OF_SOUND

    @Suppress("unused")
    private fun rms(x: FloatArray): Float { var s = 0.0; for (v in x) s += v * v; return sqrt(s / x.size).toFloat() }
}
