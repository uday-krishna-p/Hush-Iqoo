package com.hush.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hush.HLog

/**
 * Heading of the phone's top edge in degrees clockwise, smoothed, plus [offsetDeg].
 *
 * Turning is tracked by the GYROSCOPE (game rotation vector: no magnetometer), started from the magnetic heading
 * once. 27 Sep: three parallel phones' magnetic compasses read 98°, 238°, 245°, and after the phones were moved the
 * odd one's error changed by ~130° while still claiming "high" accuracy. What the arrows need is that the phones'
 * headings agree with each other, which a gyroscope keeps (slow drift of a few degrees, no magnetic jumps).
 * The offset is set by SYNC COMPASS so every phone reads the same heading while they lie parallel.
 * Falls back to the magnetic heading on a phone without a game rotation vector.
 */
class Compass(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    /** Smoothed heading before the offset (gyroscope yaw started from the magnetic heading). */
    @Volatile var rawHeadingDeg: Float = 0f
        private set
    /** Degrees added to the raw heading (SYNC COMPASS). */
    @Volatile var offsetDeg: Float = 0f
    val headingDeg: Float get() = ((rawHeadingDeg + offsetDeg) % 360f + 360f) % 360f
    /** The magnetic compass alone, for the log. */
    @Volatile var magneticDeg: Float = 0f
        private set
    /** Magnetometer accuracy: 0 unreliable, 1 low, 2 medium, 3 high; -1 not reported yet. */
    @Volatile var accuracy: Int = -1
        private set
    @Volatile var available = false
        private set
    /** True while the heading follows the gyroscope (false: magnetic fallback). */
    @Volatile var gyro = false
        private set

    private var magReadings = 0
    private var gyroYaw: Float? = null
    /** magnetic − gyro yaw, fixed once from the first magnetic readings. */
    private var base: Float? = null

    fun start() {
        if (magSensor == null && gyroSensor == null) { HLog.d("Compass: no rotation sensor"); return }
        // GAME rate (~50 Hz) with the 0.25 smoothing below: a turn shows within ~80 ms (team, 27 Sep: "not fast enough").
        val m = magSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        gyro = gyroSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        available = m || gyro
        HLog.d("Compass: started=$available magnetic=$m gyroscope=$gyro")
    }

    fun stop() = manager.unregisterListener(this)

    private fun yaw(values: FloatArray): Float {
        SensorManager.getRotationMatrixFromVector(rot, values)
        SensorManager.getOrientation(rot, orient)
        val deg = Math.toDegrees(orient[0].toDouble()).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    private fun smooth(target: Float) {
        var diff = target - rawHeadingDeg
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        rawHeadingDeg = ((rawHeadingDeg + diff * 0.25f) + 360f) % 360f
    }

    override fun onSensorChanged(e: SensorEvent) {
        val y = yaw(e.values)
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            magneticDeg = y
            magReadings++
            if (!gyro) { smooth(y); return }
            // Start the gyroscope heading from the magnetic one after ~0.5 s of readings (rough north only).
            val g = gyroYaw
            if (base == null && magReadings >= 25 && g != null) {
                base = y - g
                rawHeadingDeg = ((g + base!!) % 360f + 360f) % 360f
                HLog.d("Compass: gyroscope heading started from magnetic %.0f°".format(y))
            }
        } else {
            gyroYaw = y
            val b = base ?: (if (magSensor == null) 0f else return)
            smooth(((y + b) % 360f + 360f) % 360f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (accuracy != this.accuracy) HLog.d("Compass: magnetic accuracy $accuracy (0 unreliable, 1 low, 2 medium, 3 high), magnetic %.0f°".format(magneticDeg))
        this.accuracy = accuracy
    }
}
