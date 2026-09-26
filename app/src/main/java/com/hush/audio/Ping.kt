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
import kotlin.math.min
import kotlin.math.sin

/** Plays a loud tone on the alarm channel at maximum volume. Used to mark the start and end of a Hush window. */
object Ping {

    private const val SAMPLE_RATE = 48_000

    /** [pattern]: list of (frequencyHz, durationMs). 0 Hz = silence. */
    fun play(context: Context, pattern: List<Pair<Int, Int>>) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: SecurityException) {
                HLog.d("Ping: could not raise alarm volume (Do Not Disturb?): $e")
            }
            val total = pattern.sumOf { it.second } * SAMPLE_RATE / 1000
            val buf = ShortArray(total)
            var pos = 0
            for ((freq, ms) in pattern) {
                val n = ms * SAMPLE_RATE / 1000
                val ramp = SAMPLE_RATE / 100  // 10 ms fade in/out so it does not click
                for (i in 0 until n) {
                    val env = min(1f, min(i.toFloat() / ramp, (n - i).toFloat() / ramp))
                    val v = if (freq == 0) 0.0 else sin(2.0 * PI * freq * i / SAMPLE_RATE)
                    buf[pos + i] = (v * 0.95 * 32767 * env).toInt().toShort()
                }
                pos += n
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(total * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, total)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ try { track.release() } catch (_: Exception) {} }, (total * 1000L / SAMPLE_RATE) + 300)
            HLog.d("Ping: played $pattern at alarm volume ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        } catch (e: Exception) {
            HLog.d("Ping failed: $e")
        }
    }
}
