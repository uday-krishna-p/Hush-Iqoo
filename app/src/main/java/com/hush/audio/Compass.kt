package com.hush.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hush.HLog

/**
 * Heading of the phone's top edge, in degrees clockwise from magnetic north, smoothed, plus [offsetDeg].
 * The offset is set by SYNC COMPASS (27 Sep: three parallel phones read 98°, 238°, 245°; the odd one drew the
 * fused arrow backwards), so every phone's heading agrees with the majority even when one compass is disturbed.
 */
class Compass(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    /** Smoothed sensor heading before the offset. */
    @Volatile var rawHeadingDeg: Float = 0f
        private set
    /** Degrees added to the sensor heading (SYNC COMPASS). */
    @Volatile var offsetDeg: Float = 0f
    val headingDeg: Float get() = ((rawHeadingDeg + offsetDeg) % 360f + 360f) % 360f
    /** SensorManager accuracy: 0 unreliable, 1 low, 2 medium, 3 high; -1 not reported yet. */
    @Volatile var accuracy: Int = -1
        private set
    @Volatile var available = false
        private set

    fun start() {
        if (sensor == null) { HLog.d("Compass: no rotation vector sensor"); return }
        // GAME rate (~50 Hz) with the 0.25 smoothing below: a turn shows within ~80 ms (UI rate lagged ~250 ms, 27 Sep team: "not fast enough").
        available = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        HLog.d("Compass: started=$available")
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, orient)
        var deg = Math.toDegrees(orient[0].toDouble()).toFloat()
        if (deg < 0) deg += 360f
        // Smooth around the wrap-around.
        var diff = deg - rawHeadingDeg
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        rawHeadingDeg = ((rawHeadingDeg + diff * 0.25f) + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy != this.accuracy) HLog.d("Compass: accuracy $accuracy (0 unreliable, 1 low, 2 medium, 3 high), raw heading %.0f°".format(rawHeadingDeg))
        this.accuracy = accuracy
    }
}
