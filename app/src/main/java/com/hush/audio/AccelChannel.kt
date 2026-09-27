package com.hush.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hush.HLog
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The "seismic" channel. A knock on the surface the phone lies on shakes the phone; a cough or a voice
 * in the air does not. High-pass filtered acceleration magnitude, spikes counted per second.
 */
class AccelChannel(context: Context) : SensorEventListener {

    data class Result(
        val spikes: Int,
        val maxHp: Float,            // m/s², largest high-passed jolt this second
        val rmsHp: Float,            // m/s², shaking level over the second (knock jolts included)
        val spikeTimesMs: List<Long>,
        val moving: Boolean,         // phone handled or carried: its audio is not trustworthy right now
        val medianHp: Float = 0f     // m/s², the TYPICAL shake of the second: what [moving] is judged on
    )

    companion object {
        // Measured 26 Sep on the I2501: at rest noise is 0.02 m/s² (max) / 0.007 (rms);
        // a handled phone shows 2–28 m/s² spikes and 0.4–3 rms.
        const val SPIKE_THRESHOLD = 0.5f    // m/s²
        const val REFRACTORY_MS = 120L
        const val MOVING_RMS = 0.25f        // the old rule on the RMS, kept for the log: knocks on the table crossed it
        /**
         * Moving = the MEDIAN high-passed shake of the second above this. A knock on the surface the phone lies on
         * gives a few jolts of ~4 m/s² lasting tens of ms: the RMS went to 0.65–1.44 (27 Sep 08:32, Sensor B, three
         * knocks a second) and every such knock was thrown out of the closest-phone vote as "moving" (149 of 488
         * knocks in that session, always the ones beside a phone). Handling and carrying shake the phone the whole
         * second, so the median rises; a few short jolts leave it at rest (~0.005). 0.15 ≈ the old 0.25 RMS for
         * steady shaking (median of |Gaussian| = 0.67 σ).
         */
        const val MOVING_MEDIAN = 0.15f

        /** Median of the first [n] values (copies; [n] ≤ ~250 a second). */
        fun medianOf(a: FloatArray, n: Int): Float {
            if (n <= 0) return 0f
            val c = a.copyOf(n); c.sort()
            return if (n % 2 == 1) c[n / 2] else (c[n / 2 - 1] + c[n / 2]) / 2f
        }
        private const val LOWPASS_ALPHA = 0.02f
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private val lock = Any()

    private var lowpass = 0f
    private var initialised = false
    private var lastSpikeMs = 0L
    private var spikeTimes = ArrayList<Long>()
    private var maxHp = 0f
    private var sumSq = 0.0
    private var count = 0
    private var hps = FloatArray(512)   // this second's |high-passed| samples, for the median (200 Hz)
    private var samplesSeen = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        if (sensor == null) { HLog.d("Accel: no accelerometer on this phone"); return }
        try {
            val t = HandlerThread("hush-accel").also { it.start() }
            thread = t
            // 200 Hz (5000 µs) is the fastest rate allowed without the HIGH_SAMPLING_RATE_SENSORS permission.
            // SENSOR_DELAY_FASTEST crashed the service with a SecurityException on Android 16.
            val ok = manager.registerListener(this, sensor, 5000, Handler(t.looper))
            HLog.d("Accel: started ok=$ok, ${sensor.name}, minDelay=${sensor.minDelay}us")
        } catch (e: Exception) {
            HLog.d("Accel: failed to start, continuing without it: $e")
            thread?.quitSafely()
            thread = null
        }
    }

    fun stop() {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        if (!initialised) { lowpass = mag; initialised = true }
        lowpass += LOWPASS_ALPHA * (mag - lowpass)
        val hp = abs(mag - lowpass)
        // Sensor timestamps share the elapsedRealtime clock on this hardware; convert to ms on that clock.
        val tMs = SystemClock.elapsedRealtime() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
        synchronized(lock) {
            samplesSeen++
            if (hp > maxHp) maxHp = hp
            sumSq += hp * hp
            if (count < hps.size) hps[count] = hp
            count++
            if (hp > SPIKE_THRESHOLD && tMs - lastSpikeMs > REFRACTORY_MS) {
                spikeTimes.add(tMs)
                lastSpikeMs = tMs
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Returns everything since the last call and resets. Call once per audio window. */
    fun drain(): Result {
        synchronized(lock) {
            val rms = if (count == 0) 0f else sqrt(sumSq / count).toFloat()
            val median = medianOf(hps, minOf(count, hps.size))
            val r = Result(spikeTimes.size, maxHp, rms, spikeTimes.toList(), median > MOVING_MEDIAN, median)
            spikeTimes = ArrayList()
            maxHp = 0f
            sumSq = 0.0
            count = 0
            return r
        }
    }
}
