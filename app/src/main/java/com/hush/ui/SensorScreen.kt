package com.hush.ui

import android.app.Activity
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.hush.Engine
import com.hush.R
import com.hush.model.SensorEvent
import kotlin.math.log10

/**
 * Draws this phone's own hearing: loudness bar, tap line, top class, raw top-5, link status, countdown.
 * Pure display; the microphone lives in [Engine]. The commander screen reuses it for its own mic block.
 */
open class SensorScreen(protected val activity: Activity, private val roleName: String) : Engine.Listener {

    private val roleLabel: TextView = activity.findViewById(R.id.roleLabel)
    private val sourceLabel: TextView = activity.findViewById(R.id.sourceLabel)
    private val linkStatus: TextView = activity.findViewById(R.id.linkStatus)
    private val countdown: TextView = activity.findViewById(R.id.countdown)
    private val rmsBar: ProgressBar = activity.findViewById(R.id.rmsBar)
    private val rmsText: TextView = activity.findViewById(R.id.rmsText)
    private val tapText: TextView = activity.findViewById(R.id.tapText)
    private val headline: TextView = activity.findViewById(R.id.headline)
    private val topClass: TextView = activity.findViewById(R.id.topClass)
    private val top5: TextView = activity.findViewById(R.id.top5)

    init {
        roleLabel.text = roleName
        sourceLabel.text = activity.getString(R.string.mic_starting)
    }

    override fun onOwnWindow(w: Engine.Window) {
        headline.text = if (w.rhythm.rhythm != null) "${w.event.label} · ${w.rhythm.rhythm}" else w.event.label
        headline.setTextColor(when (w.event.label) {
            Engine.LABEL_TAPPING -> 0xFF1B8A3A.toInt()
            Engine.LABEL_TAPPING_MAYBE -> 0xFF8A7A1B.toInt()
            Engine.LABEL_VOICE -> 0xFF1B4F8A.toInt()
            Engine.LABEL_MACHINE -> 0xFF8A1B1B.toInt()
            else -> 0xFF808080.toInt()
        })
        rmsBar.progress = rmsToPercent(w.rms)
        rmsText.text = activity.getString(R.string.rms_value, w.rms, 20f * log10(w.rms.coerceAtLeast(1e-6f)), w.gain)
        tapText.text = activity.getString(R.string.tap_value, w.tap.taps, w.tap.peakRatio, w.tap.score, w.tap.intervalsMs.joinToString(" "))
        topClass.text = activity.getString(R.string.top_class_value, w.cls?.topClass ?: "…", w.event.human, w.event.impact, w.event.machine)
        top5.text = w.cls?.top5?.joinToString("\n") { (name, score) -> "%5.2f  %s".format(score, name) } ?: ""
        sourceLabel.text = activity.getString(R.string.mic_source_letter, Engine.letter)
    }

    override fun onLinkStatus(text: String) {
        linkStatus.text = text
    }

    override fun onCountdown(secondsLeft: Int) {
        if (secondsLeft < 0) {
            countdown.visibility = View.GONE
        } else {
            countdown.visibility = View.VISIBLE
            countdown.text = secondsLeft.toString()
        }
    }

    override fun onEvent(event: SensorEvent, peerName: String) {}
    override fun onPeers(peers: List<Engine.Peer>) {}

    /** Maps RMS to a 0..100 bar on a decibel scale. Phone mics sit around -70 dB in a quiet room: -85 dB → 0, -15 dB → 100. */
    private fun rmsToPercent(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20f * log10(rms)
        return ((db + 85f) / 70f * 100f).toInt().coerceIn(0, 100)
    }
}
