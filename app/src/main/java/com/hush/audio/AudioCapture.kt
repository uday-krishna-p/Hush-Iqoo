package com.hush.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.hush.HLog

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
        const val DEBUG_WAV_MAX_SECONDS = 90
        const val RING_SECONDS = 8
    }

    private fun wavOpen() {
        val f = debugWav ?: return
        try {
            f.delete()
            wav = java.io.RandomAccessFile(f, "rw").also { it.write(ByteArray(44)) }
            wavBytes = 0
            wavHeader()
            HLog.d("debug wav started: ${f.absolutePath}")
        } catch (e: Exception) {
            HLog.d("debug wav open failed: $e")
            wav = null
        }
    }

    /** Writes a valid 16-bit mono header for the bytes so far, so a hard kill still leaves a playable file. */
    private fun wavHeader() {
        val w = wav ?: return
        val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt((36 + wavBytes).toInt()).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(wavBytes.toInt())
        w.seek(0)
        w.write(bb.array())
        w.seek(44 + wavBytes)
    }

    private fun wavWrite(chunk: ShortArray, n: Int) {
        val w = wav ?: return
        if (wavBytes >= DEBUG_WAV_MAX_SECONDS.toLong() * SAMPLE_RATE * 2) return
        val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(chunk[i])
        w.write(bb.array())
        wavBytes += n * 2
        if ((wavBytes / 2) % SAMPLE_RATE < n) wavHeader()   // refresh the header about once a second
    }

    private fun wavClose() {
        try { wavHeader(); wav?.close() } catch (_: Exception) {}
        wav = null
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    // Ring buffers of the last RING_SECONDS of raw 48 kHz audio for each mic, with an absolute sample
    // counter, so a chirp can be located to the sample on this phone's own clock, per microphone.
    private val ring = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private val ring2 = ShortArray(SAMPLE_RATE * RING_SECONDS)
    private var ringWrite = 0
    @Volatile var samplesCaptured = 0L
        private set
    @Volatile var stereo = false
        private set
    private val ringLock = Any()

    private fun ringPush(ch0: ShortArray, ch1: ShortArray?, n: Int) {
        synchronized(ringLock) {
            for (i in 0 until n) {
                ring[ringWrite] = ch0[i]
                ring2[ringWrite] = if (ch1 != null) ch1[i] else ch0[i]
                ringWrite = (ringWrite + 1) % ring.size
            }
            samplesCaptured += n
        }
    }

    /**
     * Copies [count] samples of microphone [channel] (0 or 1) starting at absolute sample [fromSample].
     * Returns null if that stretch is no longer (or not yet) in the buffer.
     */
    fun snapshot(fromSample: Long, count: Int, channel: Int = 0): ShortArray? {
        synchronized(ringLock) {
            val src = if (channel == 1) ring2 else ring
            val oldest = samplesCaptured - src.size
            if (fromSample < oldest || fromSample + count > samplesCaptured || count > src.size) return null
            val out = ShortArray(count)
            var idx = ((fromSample % src.size).toInt() + src.size) % src.size
            for (i in 0 until count) {
                out[i] = src[idx]
                idx = (idx + 1) % src.size
            }
            return out
        }
    }

    /**
     * DEBUG ONLY: when set before start(), the raw 48 kHz stream is also written to this WAV file
     * (private app storage, capped at [DEBUG_WAV_MAX_SECONDS]). Pulled over USB, never over the network.
     */
    var debugWav: java.io.File? = null
    private var wav: java.io.RandomAccessFile? = null
    private var wavBytes = 0L

    /** Human-readable name of the mic source actually in use, for the screen. */
    @Volatile var sourceName: String = "not started"
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "hush-audio").apply { start() }
        HLog.d("AudioCapture start requested")
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        HLog.d("AudioCapture stopped")
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before start()
    private fun openRecorder(): AudioRecord? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedOk = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        HLog.d("UNPROCESSED mic source supported: $unprocessedOk")
        // Measured on the iQOO I2501: UNPROCESSED gives ~-70 dB signal (near-silence), so it is a fallback only.
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC",
        ) + if (unprocessedOk) listOf(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED") else emptyList()

        // Stereo first: the I2501 exposes two distinct mics, which gives chirp direction of arrival.
        for (ch in listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val channels = if (ch == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, ch, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) continue
            val bufferBytes = maxOf(minBytes, WINDOW * 2 * channels)
            for ((source, name) in candidates) {
                try {
                    val rec = AudioRecord(source, SAMPLE_RATE, ch, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        sourceName = name + if (channels == 2) " (2 mics)" else ""
                        stereo = channels == 2
                        HLog.d("AudioRecord opened with source $name, channels=$channels, buffer $bufferBytes bytes")
                        return rec
                    }
                    HLog.d("AudioRecord source $name channels=$channels failed to initialise, trying next")
                    rec.release()
                } catch (e: Exception) {
                    HLog.d("AudioRecord source $name channels=$channels threw: $e")
                }
            }
        }
        return null
    }

    private fun loop() {
        val rec = openRecorder()
        if (rec == null) {
            sourceName = "NO MIC"
            HLog.d("ERROR: could not open any microphone source")
            running = false
            return
        }
        val window = ShortArray(WINDOW)
        val channels = if (stereo) 2 else 1
        val raw = ShortArray(CHUNK * channels)
        val chunk = ShortArray(CHUNK)
        val chunk2 = if (stereo) ShortArray(CHUNK) else null
        val filtered = FloatArray(WINDOW)
        val pcm16 = FloatArray(WINDOW / 3)
        val bandPass = BandPass(SAMPLE_RATE)
        var filled = 0
        try {
            rec.startRecording()
            wavOpen()
            while (running) {
                val got = rec.read(raw, 0, CHUNK * channels)
                if (got <= 0) {
                    HLog.d("AudioRecord.read returned $got")
                    Thread.sleep(50)
                    continue
                }
                val n = got / channels
                if (stereo) {
                    for (i in 0 until n) { chunk[i] = raw[2 * i]; chunk2!![i] = raw[2 * i + 1] }
                } else {
                    System.arraycopy(raw, 0, chunk, 0, n)
                }
                wavWrite(chunk, n)
                ringPush(chunk, chunk2, n)
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
                            HLog.d("ERROR in audio window listener: $e")
                        }
                        filled = 0
                    }
                }
            }
        } catch (e: Exception) {
            HLog.d("ERROR in audio loop: $e")
        } finally {
            wavClose()
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            HLog.d("AudioRecord released")
        }
    }
}
