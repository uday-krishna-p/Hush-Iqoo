package com.hush.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hush.HLog

/** Heading of the phone's top edge, in degrees clockwise from magnetic north, smoothed. */
class Compass(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    @Volatile var headingDeg: Float = 0f
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
        var diff = deg - headingDeg
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        headingDeg = ((headingDeg + diff * 0.25f) + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
