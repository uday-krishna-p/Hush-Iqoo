package com.hush.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Reads the microphone at 48 kHz mono on a background thread and hands out one-second windows.
 * The same buffers are reused for every window, so listeners must finish with them before returning.
 */
class AudioCapture(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** Called on the audio thread once per second. [rms] is the band-passed loudness, 0..1. */
        fun onWindow(pcm48k: ShortArray, n48: Int, pcm16k: FloatArray, n16: Int, rms: Float)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val WINDOW = 48_000          // one second
        private const val CHUNK = 4_800    // 100 ms per read
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Human-readable name of the mic source actually in use, for the screen. */
    @Volatile var sourceName: String = "not started"
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "hush-audio").apply { start() }
        Log.d("Hush", "AudioCapture start requested")
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        Log.d("Hush", "AudioCapture stopped")
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before start()
    private fun openRecorder(): AudioRecord? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedOk = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        Log.d("Hush", "UNPROCESSED mic source supported: $unprocessedOk")
        val candidates = if (unprocessedOk)
            listOf(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED", MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
        else
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION", MediaRecorder.AudioSource.MIC to "MIC")

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBytes, WINDOW * 2)
        for ((source, name) in candidates) {
            try {
                val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    sourceName = name
                    Log.d("Hush", "AudioRecord opened with source $name, buffer $bufferBytes bytes")
                    return rec
                }
                Log.d("Hush", "AudioRecord source $name failed to initialise, trying next")
                rec.release()
            } catch (e: Exception) {
                Log.d("Hush", "AudioRecord source $name threw: $e")
            }
        }
        return null
    }

    private fun loop() {
        val rec = openRecorder()
        if (rec == null) {
            sourceName = "NO MIC"
            Log.d("Hush", "ERROR: could not open any microphone source")
            running = false
            return
        }
        val window = ShortArray(WINDOW)
        val chunk = ShortArray(CHUNK)
        val filtered = FloatArray(WINDOW)
        val pcm16 = FloatArray(WINDOW / 3)
        val bandPass = BandPass(SAMPLE_RATE)
        var filled = 0
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(chunk, 0, CHUNK)
                if (n <= 0) {
                    Log.d("Hush", "AudioRecord.read returned $n")
                    Thread.sleep(50)
                    continue
                }
                var i = 0
                while (i < n) {
                    val take = minOf(n - i, WINDOW - filled)
                    System.arraycopy(chunk, i, window, filled, take)
                    filled += take
                    i += take
                    if (filled == WINDOW) {
                        bandPass.process(window, WINDOW, filtered)
                        val rms = Dsp.rms(filtered, WINDOW)
                        val n16 = Dsp.downsample3(window, WINDOW, pcm16)
                        try {
                            listener.onWindow(window, WINDOW, pcm16, n16, rms)
                        } catch (e: Exception) {
                            Log.d("Hush", "ERROR in audio window listener: $e")
                        }
                        filled = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("Hush", "ERROR in audio loop: $e")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            Log.d("Hush", "AudioRecord released")
        }
    }
}
