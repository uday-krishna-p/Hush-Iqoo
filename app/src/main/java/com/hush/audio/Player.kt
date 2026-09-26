package com.hush.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.hush.HLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Calibration only: plays a 16-bit mono WAV from the app's private files through the speaker (alarm
 * stream at [fraction] of maximum) and logs the exact moment playback starts, on this phone's audio
 * sample clock, so a knock schedule inside the file becomes ground truth for the detectors.
 * Push a file with:  adb push knocks.wav /data/local/tmp/ && adb shell run-as com.hush cp /data/local/tmp/knocks.wav files/
 */
object Player {
    @Volatile private var track: AudioTrack? = null

    fun play(context: Context, file: File, fraction: Float, sampleClock: () -> Long) {
        stop()
        if (!file.exists()) { HLog.d("PLAY: ${file.name} not found"); return }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Thread({
            try {
                val raf = RandomAccessFile(file, "r")
                val header = ByteArray(44); raf.readFully(header)
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt(); val rate = bb.getInt(24); val bits = bb.getShort(34).toInt()
                if (channels != 1 || bits != 16) { HLog.d("PLAY: need 16-bit mono, got ch=$channels bits=$bits"); raf.close(); return@Thread }
                val dataBytes = (raf.length() - 44).toInt()
                AlarmVolume.raise(am, fraction)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(rate * 2)   // 1 s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                val buf = ByteArray(rate / 10 * 2)   // 100 ms
                // Pre-fill, then start: the first samples leave the speaker right after play().
                var first = raf.read(buf); if (first > 0) t.write(buf, 0, first)
                val startSample = sampleClock(); val startMs = SystemClock.elapsedRealtime()
                t.play()
                HLog.d("PLAY: started ${file.name} (${dataBytes / 2 / rate} s at $rate Hz, alarm x$fraction) at sample $startSample elapsed $startMs")
                var written = first.coerceAtLeast(0)
                while (track === t) {
                    val n = raf.read(buf); if (n <= 0) break
                    var off = 0
                    while (off < n && track === t) { val w = t.write(buf, off, n - off); if (w < 0) break; off += w }
                    written += n
                }
                raf.close()
                if (track === t) {
                    // Let the last buffer drain, then release.
                    Thread.sleep(1200)
                    HLog.d("PLAY: finished ${file.name} ($written bytes)")
                }
                try { t.stop(); t.release() } catch (e: Exception) { HLog.d("PLAY: release failed $e") }
                if (track === t) track = null
                AlarmVolume.restore(am)
            } catch (e: Exception) {
                HLog.d("PLAY failed: $e")
                AlarmVolume.restore(am)
            }
        }, "hush-play").start()
    }

    fun stop() {
        val t = track ?: return
        track = null
        try { t.stop(); t.release(); HLog.d("PLAY: stopped") } catch (e: Exception) { HLog.d("PLAY: stop failed $e") }
    }
}
