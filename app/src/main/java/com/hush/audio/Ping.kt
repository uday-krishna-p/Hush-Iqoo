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

/**
 * Our beeps and chirps play on the alarm stream at a level we choose. This raises it and puts the
 * person's own alarm volume back once the last of our overlapping sounds has finished.
 */
object AlarmVolume {
    private var saved: Int? = null
    private var pending = 0

    @Synchronized fun raise(am: AudioManager, fraction: Float) {
        try {
            if (saved == null) saved = am.getStreamVolume(AudioManager.STREAM_ALARM)
            pending++
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * fraction).toInt().coerceAtLeast(1), 0)
        } catch (e: SecurityException) {
            HLog.d("AlarmVolume: could not set alarm volume (Do Not Disturb?): $e")
        }
    }

    /** One call per [raise], when that sound is over. Restores after the last one. */
    @Synchronized fun restore(am: AudioManager) {
        if (pending > 0) pending--
        if (pending > 0) return
        val s = saved ?: return
        saved = null
        try { am.setStreamVolume(AudioManager.STREAM_ALARM, s, 0); HLog.d("AlarmVolume: restored to $s") }
        catch (e: SecurityException) { HLog.d("AlarmVolume: restore failed: $e") }
    }
}

/** Plays a loud tone on the alarm channel. Used to mark the start and end of a Hush window. */
object Ping {

    private const val SAMPLE_RATE = 48_000

    /** Fraction of maximum alarm volume for the beeps. 26 Sep: set to 10 % on request; raise for the demo if wanted. */
    const val VOLUME_FRACTION = 0.10f

    /** [pattern]: list of (frequencyHz, durationMs). 0 Hz = silence. */
    fun play(context: Context, pattern: List<Pair<Int, Int>>, fraction: Float = VOLUME_FRACTION) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        AlarmVolume.raise(am, fraction)
        try {
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
            Handler(Looper.getMainLooper()).postDelayed({
                try { track.release() } catch (e: Exception) { HLog.d("Ping: release failed $e") }
                AlarmVolume.restore(am)
            }, (total * 1000L / SAMPLE_RATE) + 300)
            HLog.d("Ping: played $pattern at alarm volume ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
        } catch (e: Exception) {
            HLog.d("Ping failed: $e")
            AlarmVolume.restore(am)
        }
    }
}
