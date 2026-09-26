package com.hush.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private var capture: AudioCapture? = null
    private var classifier: Classifier? = null
    private var windows = 0

    fun start() {
        roleLabel.text = roleName
        sourceLabel.text = activity.getString(R.string.mic_starting)
        if (classifier == null) {
            try {
                classifier = Classifier(activity)
            } catch (e: Exception) {
                Log.d("Hush", "ERROR loading classifier: $e")
                top5.text = activity.getString(R.string.classifier_failed, e.toString())
            }
        }
        capture = AudioCapture(activity, this).also { it.start() }
        main.postDelayed({ sourceLabel.text = activity.getString(R.string.mic_source, capture?.sourceName ?: "?") }, 800)
    }

    fun stop() {
        capture?.stop()
        capture = null
        classifier?.close()
        classifier = null
    }

    override fun onWindow(pcm48k: ShortArray, n48: Int, pcm16k: FloatArray, n16: Int, rms: Float) {
        val result = classifier?.classify(pcm16k, n16)
        windows++
        if (windows % 5 == 0 || windows <= 3) {
            Log.d("Hush", "window $windows rms=%.4f top=%s human=%.2f machine=%.2f".format(
                rms, result?.topClass ?: "-", result?.human ?: 0f, result?.machine ?: 0f))
        }
        val top5Text = result?.top5?.joinToString("\n") { (name, score) -> "%5.2f  %s".format(score, name) } ?: ""
        val human = result?.human ?: 0f
        val machine = result?.machine ?: 0f
        main.post {
            rmsBar.progress = rmsToPercent(rms)
            rmsText.text = activity.getString(R.string.rms_value, rms)
            topClass.text = activity.getString(R.string.top_class_value, result?.topClass ?: "…", human, machine)
            top5.text = top5Text
        }
    }

    /** Maps RMS to a 0..100 bar on a decibel scale, so quiet rooms still move the bar. -60 dB → 0, 0 dB → 100. */
    private fun rmsToPercent(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20f * log10(rms)
        return ((db + 60f) / 60f * 100f).toInt().coerceIn(0, 100)
    }
}
