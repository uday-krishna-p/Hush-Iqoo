package com.hush.audio

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.hush.HLog

/** The one place that vibrates: Hush window start/end and the rescue-alert pulse on a woken phone. */
object Haptics {

    /**
     * [pattern] is off/on/off/on... in ms. Full amplitude, and tagged as an alarm so the phone does not
     * scale it down the way it does for ordinary app haptics (the first version was barely noticeable).
     */
    private fun vibrator(context: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** Stops whatever is vibrating (a repeating household alarm pattern, once the person has seen it). */
    fun cancel(context: Context, what: String = "vibrate") {
        try { vibrator(context).cancel(); HLog.d("$what cancelled") } catch (e: Exception) { HLog.d("$what cancel failed: $e") }
    }

    /** [repeatFrom] ≥ 0 repeats the pattern from that index until [cancel]; -1 (default) plays it once. */
    fun vibrate(context: Context, pattern: LongArray, what: String = "vibrate", repeatFrom: Int = -1) {
        try {
            val vib = vibrator(context)
            val amplitudes = IntArray(pattern.size) { if (it % 2 == 1) 255 else 0 }
            val effect = if (vib.hasAmplitudeControl()) VibrationEffect.createWaveform(pattern, amplitudes, repeatFrom)
                         else VibrationEffect.createWaveform(pattern, repeatFrom)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vib.vibrate(effect)
            }
            HLog.d("$what ${pattern.contentToString()} amplitudeControl=${vib.hasAmplitudeControl()}")
        } catch (e: Exception) {
            HLog.d("$what failed: $e")
        }
    }

    /** Long-long-short, three times: the rescue-alert pulse a trapped person can feel through a pocket. */
    fun rescuePulse(context: Context) {
        val unit = longArrayOf(600, 150, 600, 150, 250, 700)
        val pattern = LongArray(1 + unit.size * 3)
        pattern[0] = 0
        for (k in 0 until 3) unit.forEachIndexed { i, v -> pattern[1 + k * unit.size + i] = v }
        vibrate(context, pattern, "Activation: haptic pulse")
    }
}
