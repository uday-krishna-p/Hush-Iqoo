package com.hush.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.hush.HLog
import kotlin.math.sqrt

/**
 * One-off check: does this phone give two DIFFERENT microphone channels in stereo capture?
 * If yes, the delay of a chirp between the two mics gives its direction of arrival (future alignment source).
 */
object MicProbe {
    @SuppressLint("MissingPermission")
    fun run(): String {
        return try {
            val sr = 48_000
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return "stereo capture not supported (minBuf=$minBuf)"
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 2 * 2))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "stereo AudioRecord failed to initialise" }
            rec.startRecording()
            val n = sr / 2 * 2   // 0.5 s interleaved
            val buf = ShortArray(n)
            var got = 0
            while (got < n) { val r = rec.read(buf, got, n - got); if (r <= 0) break; got += r }
            rec.stop(); rec.release()
            var l = 0.0; var rr = 0.0; var d = 0.0
            var i = sr / 10 * 2   // skip the first 100 ms
            while (i + 1 < got) {
                val a = buf[i] / 32768.0; val b = buf[i + 1] / 32768.0
                l += a * a; rr += b * b; d += (a - b) * (a - b)
                i += 2
            }
            val m = (got - sr / 10 * 2) / 2
            val rmsL = sqrt(l / m); val rmsR = sqrt(rr / m); val rmsD = sqrt(d / m)
            val verdict = if (rmsL > 1e-5 && rmsD / rmsL > 0.2) "TWO DISTINCT MICS" else "channels identical (mono duplicated)"
            "stereo probe: %s  L=%.5f R=%.5f diff=%.5f".format(verdict, rmsL, rmsR, rmsD)
        } catch (e: Exception) {
            "stereo probe threw $e"
        }
    }
}
