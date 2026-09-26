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
        val rmsHp: Float,            // m/s², continuous shaking level
        val spikeTimesMs: List<Long>,
        val moving: Boolean          // phone handled or ground shaking: its audio is not trustworthy right now
    )

    companion object {
        // Measured 26 Sep on the I2501: at rest noise is 0.02 m/s² (max) / 0.007 (rms);
        // a handled phone shows 2–28 m/s² spikes and 0.4–3 rms.
        const val SPIKE_THRESHOLD = 0.5f    // m/s²
        const val REFRACTORY_MS = 120L
        const val MOVING_RMS = 0.25f        // above this the phone is being handled or shaken
        private const val LOWPASS_ALPHA = 0.02f
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null
    private val lock = Any()

    /**
     * Raw samples for the crash detector (docs/PLAN-crash.md): (isGyro, tMs on the elapsedRealtime clock, x, y, z),
     * accelerometer in m/s² WITH gravity, gyroscope in rad/s. Called on the sensor thread, 200 Hz each.
     */
    @Volatile var rawSink: ((Boolean, Long, Float, Float, Float) -> Unit)? = null
    /** Full scale of the accelerometer in m/s² as Android configured it (the LSM6DSVX can do ±2..±16 g). */
    val maxRangeMs2: Float get() = sensor?.maximumRange ?: 0f

    private var lowpass = 0f
    private var initialised = false
    private var lastSpikeMs = 0L
    private var spikeTimes = ArrayList<Long>()
    private var maxHp = 0f
    private var sumSq = 0.0
    private var count = 0
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
            HLog.d("Accel: started ok=$ok, ${sensor.name}, minDelay=${sensor.minDelay}us, maxRange=%.1f m/s² (%.1f g)".format(sensor.maximumRange, sensor.maximumRange / 9.81f))
            // The gyroscope only feeds the crash detector (tumble during a fall); the knock channel ignores it.
            if (gyro != null) {
                val okG = manager.registerListener(this, gyro, 5000, Handler(t.looper))
                HLog.d("Gyro: started ok=$okG, ${gyro.name}, maxRange=%.0f °/s".format(Math.toDegrees(gyro.maximumRange.toDouble())))
            } else HLog.d("Gyro: no gyroscope on this phone")
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
        // Sensor timestamps share the elapsedRealtime clock on this hardware; convert to ms on that clock.
        val tMs = SystemClock.elapsedRealtime() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            try { rawSink?.invoke(true, tMs, e.values[0], e.values[1], e.values[2]) } catch (ex: Exception) { HLog.d("Gyro sink threw $ex") }
            return
        }
        try { rawSink?.invoke(false, tMs, e.values[0], e.values[1], e.values[2]) } catch (ex: Exception) { HLog.d("Accel sink threw $ex") }
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        if (!initialised) { lowpass = mag; initialised = true }
        lowpass += LOWPASS_ALPHA * (mag - lowpass)
        val hp = abs(mag - lowpass)
        synchronized(lock) {
            samplesSeen++
            if (hp > maxHp) maxHp = hp
            sumSq += hp * hp
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
            val r = Result(spikeTimes.size, maxHp, rms, spikeTimes.toList(), rms > MOVING_RMS)
            spikeTimes = ArrayList()
            maxHp = 0f
            sumSq = 0.0
            count = 0
            return r
        }
    }
}
