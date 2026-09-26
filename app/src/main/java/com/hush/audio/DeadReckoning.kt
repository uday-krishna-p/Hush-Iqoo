package com.hush.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.hush.HLog
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where has this phone been carried since it started? Each detected step adds one stride along the
 * compass heading. Gives a north-referenced offset in metres from the start point, good to roughly
 * 10 % of distance plus compass error. Used to orient the ranged map without any button.
 */
class DeadReckoning(context: Context, private val compass: Compass) : SensorEventListener {

    companion object {
        const val STRIDE_M = 0.70f
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    @Volatile var east = 0f; private set
    @Volatile var north = 0f; private set
    @Volatile var steps = 0; private set
    @Volatile var lastStepMs = 0L; private set
    val available: Boolean get() = stepSensor != null

    fun start() {
        if (stepSensor == null) { HLog.d("DeadReckoning: no step detector"); return }
        val ok = manager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        HLog.d("DeadReckoning: started=$ok")
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val h = Math.toRadians(compass.headingDeg.toDouble())
        east += (STRIDE_M * sin(h)).toFloat()
        north += (STRIDE_M * cos(h)).toFloat()
        steps++
        lastStepMs = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
