package com.hush.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hush.HLog
import com.hush.audio.TapDetector
import android.widget.ProgressBar
import android.widget.TextView
import com.hush.R
import com.hush.audio.AudioCapture
import com.hush.audio.Classifier
import kotlin.math.log10

/**
 * The listening screen: live loudness bar, top class, and the raw top-5 debug list.
 * Both roles show this in step 1; the commander gets its own screen in step 2.
 */
class SensorScreen(private val activity: Activity, private val roleName: String) : AudioCapture.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val roleLabel: TextView = activity.findViewById(R.id.roleLabel)
    private val sourceLabel: TextView = activity.findViewById(R.id.sourceLabel)
    private val rmsBar: ProgressBar = activity.findViewById(R.id.rmsBar)
    private val rmsText: TextView = activity.findViewById(R.id.rmsText)
    private val topClass: TextView = activity.findViewById(R.id.topClass)
    private val top5: TextView = activity.findViewById(R.id.top5)
    private val tapText: TextView = activity.findViewById(R.id.tapText)

    private var capture: AudioCapture? = null
    private var classifier: Classifier? = null
    private val tapDetector = TapDetector(AudioCapture.SAMPLE_RATE)
    private var windows = 0

    fun start() {
        roleLabel.text = roleName
        sourceLabel.text = activity.getString(R.string.mic_starting)
        if (classifier == null) {
            try {
                classifier = Classifier(activity)
            } catch (e: Exception) {
                HLog.d("ERROR loading classifier: $e")
                top5.text = activity.getString(R.string.classifier_failed, e.toString())
            }
        }
        windows = 0
        capture = AudioCapture(activity, this).also {
            it.debugWav = java.io.File(activity.filesDir, "debug.wav")   // debug capture, see CLAUDE.md
            it.start()
        }
        main.postDelayed({ sourceLabel.text = activity.getString(R.string.mic_source, capture?.sourceName ?: "?") }, 800)
    }

    fun stop() {
        capture?.stop()
        capture = null
        classifier?.close()
        classifier = null
    }

    override fun onWindow(pcm48k: ShortArray, n48: Int, pcm16k: FloatArray, n16: Int, rms: Float) {
        val windowStartMs = SystemClock.elapsedRealtime() - 1000
        val tap = tapDetector.analyse(pcm48k, n48, windowStartMs)
        val result = classifier?.classify(pcm16k, n16)
        windows++
        val top5Text = result?.top5?.joinToString("\n") { (name, score) -> "%5.2f  %s".format(score, name) } ?: ""
        HLog.d("window $windows rms=%.4f taps=%d peak=x%.0f gaps=%s tapScore=%.2f human=%.2f machine=%.2f | %s".format(
            rms, tap.taps, tap.peakRatio, tap.intervalsMs, tap.score, result?.human ?: 0f, result?.machine ?: 0f,
            result?.top5?.joinToString(", ") { (name, score) -> "%s %.2f".format(name, score) } ?: "-"))
        val human = result?.human ?: 0f
        val machine = result?.machine ?: 0f
        val tapLine = activity.getString(R.string.tap_value, tap.taps, tap.peakRatio, tap.score, tap.intervalsMs.joinToString(" "))
        main.post {
            rmsBar.progress = rmsToPercent(rms)
            rmsText.text = activity.getString(R.string.rms_value, rms, 20f * log10(rms.coerceAtLeast(1e-6f)), classifier?.lastGain ?: 1f)
            tapText.text = tapLine
            topClass.text = activity.getString(R.string.top_class_value, result?.topClass ?: "…", human, machine)
            top5.text = top5Text
        }
    }

    /** Maps RMS to a 0..100 bar on a decibel scale. Phone mics sit around -70 dB in a quiet room: -85 dB → 0, -15 dB → 100. */
    private fun rmsToPercent(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20f * log10(rms)
        return ((db + 85f) / 70f * 100f).toInt().coerceIn(0, 100)
    }
}
