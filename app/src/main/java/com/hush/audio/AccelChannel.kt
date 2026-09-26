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
        const val SPIKE_THRESHOLD = 0.15f   // m/s². Sensor noise at rest is ~0.02. Tune from the logs.
        const val REFRACTORY_MS = 120L
        const val MOVING_RMS = 0.8f         // hand tremor is ~0.1–0.3
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
    private var samplesSeen = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        if (sensor == null) { HLog.d("Accel: no accelerometer on this phone"); return }
        val t = HandlerThread("hush-accel").also { it.start() }
        thread = t
        val ok = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        HLog.d("Accel: started ok=$ok, ${sensor.name}, minDelay=${sensor.minDelay}us")
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
