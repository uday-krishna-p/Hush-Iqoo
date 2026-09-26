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
    private val accelText: TextView = activity.findViewById(R.id.accelText)
    private val topClass: TextView = activity.findViewById(R.id.topClass)
    private val top5: TextView = activity.findViewById(R.id.top5)

    /** Only on a sensor: the arrow (fused bearing, own two mics, or the commander's fix). The commander draws its own. */
    private val sensorArrow: ArrowView? = activity.findViewById(R.id.sensorArrow)
        ?: if (Engine.role != Engine.ROLE_COMMANDER) activity.findViewById(R.id.arrow) else null
    private var arrowTicks = 0
    private val sensorArrowTick = object : Runnable {
        override fun run() {
            val v = sensorArrow ?: return
            val own = Engine.ownArrow()
            val sharedRaw = Engine.sharedArrow()
            val shared = if (Engine.preferShared(sharedRaw, own)) sharedRaw else null
            val a = Engine.sensorArrow()
            v.twinAngleDeg = null   // one arrow only (team, 27 Sep 03:30); the mirror shows as low confidence instead
            v.confidence = shared?.confidence ?: own?.confidence ?: 1f
            if (shared != null) {
                // The network's estimate (every phone's mics, fused by the commander, mirrors resolved across phones),
                // drawn through this phone's own compass. Team, 27 Sep 03:10: this comes first, not the single phone.
                v.active = true; v.angleDeg = shared.screenDeg
                v.label = activity.getString(if (shared.twinDeg == null) R.string.arrow_shared else R.string.arrow_shared_unresolved, shared.phones, (shared.confidence * 100).toInt())
            } else if (own != null) {
                // No fusion yet (or this phone is the only one hearing it): its own two mics.
                v.active = true; v.angleDeg = own.screenDeg
                v.label = ownArrowLabel(activity, own)
            } else if (a != null && a.here) {
                v.active = false; v.angleDeg = 0f
                v.label = activity.getString(R.string.arrow_here)
            } else if (a == null) {
                v.active = false
                v.label = activity.getString(if (Engine.receivedFix == null) R.string.sensor_arrow_wait else R.string.sensor_arrow_off_map)
            } else {
                val dist = when {
                    a.edge -> " · far"
                    a.metres != null -> " · %.1f m".format(a.metres)
                    else -> ""
                }
                val what = a.near?.let { " (Sensor $it, loudest)" } ?: ""
                if (a.screenDeg != null) {
                    v.active = true; v.angleDeg = a.screenDeg
                    v.label = activity.getString(R.string.sensor_arrow, what + dist, a.north.ifEmpty { "?" })
                } else {
                    // No north yet: grey arrow drawn as if the phone's top were map-up.
                    v.active = false; v.angleDeg = a.mapBearing
                    v.label = activity.getString(R.string.sensor_arrow_no_north, what + dist)
                }
            }
            if (++arrowTicks % 40 == 0 && a != null) {
                com.hush.HLog.d("SENSOR ARROW target=%s mapBearing=%.0f° dist=%s heading=%.0f° screen=%s | north via %s".format(
                    if (a.here) "HERE" else a.near?.let { "Sensor $it (loudest)" } ?: "located source", a.mapBearing, a.metres?.let { "%.2f m".format(it) } ?: "-", Engine.headingDeg,
                    a.screenDeg?.let { "%.0f°".format(it) } ?: "- (no north)", a.north.ifEmpty { "-" }))
            }
            v.postDelayed(this, 50)
        }
    }

    init {
        roleLabel.text = roleName
        sourceLabel.text = activity.getString(R.string.mic_starting)
        sensorArrow?.let { it.label = activity.getString(R.string.sensor_arrow_wait); it.post(sensorArrowTick) }
    }

    override fun onOwnWindow(w: Engine.Window) {
        val suffix = buildString {
            if (w.rhythm.rhythm != null) append(" · ").append(w.rhythm.rhythm)
            if (w.event.label == Engine.LABEL_TAPPING && w.structureConfirmed) append(" ✓felt")
            if (w.accel.moving) append(" ⚠moving")
        }
        headline.text = w.event.label + suffix
        headline.setTextColor(when (w.event.label) {
            Engine.LABEL_TAPPING -> 0xFF1B8A3A.toInt()
            Engine.LABEL_VOICE -> 0xFF1B4F8A.toInt()
            Engine.LABEL_MACHINE -> 0xFF8A1B1B.toInt()
            Engine.LABEL_MOVEMENT -> 0xFFB05A00.toInt()
            else -> 0xFF808080.toInt()
        })
        accelText.text = activity.getString(R.string.accel_value, w.accel.spikes, w.accel.maxHp, w.accel.rmsHp, w.rhythm.count, w.tap.rejectedSustained)
        rmsBar.progress = rmsToPercent(w.rms)
        rmsText.text = activity.getString(R.string.rms_value, w.rms, 20f * log10(w.rms.coerceAtLeast(1e-6f)), w.gain)
        tapText.text = activity.getString(R.string.tap_value, w.tap.taps, w.tap.peakRatio, w.tap.score, w.tap.intervalsMs.joinToString(" "))
        topClass.text = activity.getString(R.string.top_class_value, w.cls?.topClass ?: "…", w.event.human, w.event.impact, w.event.machine)
        top5.text = w.cls?.top5?.joinToString("\n") { (name, score) -> "%5.2f  %s".format(score, name) } ?: ""
        sourceLabel.text = activity.getString(R.string.mic_source_letter, "${Engine.letter}  ·  phone ${Engine.name.takeLast(4)}")
    }

    override fun onLinkStatus(text: String) {
        // A sensor shows a heard rescuer probe under its link line (the commander screen has its own status block).
        linkStatus.text = if (Engine.role == Engine.ROLE_SENSOR && Engine.probeStatus.isNotEmpty()) text + "\n" + Engine.probeStatus else text
    }

    override fun onCountdown(secondsLeft: Int) {
        if (secondsLeft < 0) {
            countdown.visibility = View.GONE
        } else if (secondsLeft == 99) {
            countdown.visibility = View.VISIBLE
            countdown.text = activity.getString(R.string.ranging_now)
        } else {
            countdown.visibility = View.VISIBLE
            countdown.text = secondsLeft.toString()
        }
    }

    override fun onEvent(event: SensorEvent, peerName: String) {}
    override fun onPeers(peers: List<Engine.Peer>) {}
    override fun onRanking(ranks: List<Engine.Rank>, brief: String) {}

    /** Label under the phone's own two-mic arrow (shared with the commander screen). */
    protected fun ownArrowLabel(activity: Activity, e: com.hush.audio.KnockBearing.Estimate): String {
        val base = when {
            e.resolvedBy != null -> activity.getString(R.string.arrow_knock_by, e.knocks, e.resolvedBy)
            e.resolved -> activity.getString(R.string.arrow_knock, e.knocks)
            else -> activity.getString(R.string.arrow_knock_unresolved, e.knocks)
        }
        val withConf = base + activity.getString(R.string.arrow_conf, (e.confidence * 100).toInt())
        return if (e.felt * 2 > e.knocks) withConf + activity.getString(R.string.arrow_knock_table) else withConf
    }

    /** Maps RMS to a 0..100 bar on a decibel scale. Phone mics sit around -70 dB in a quiet room: -85 dB → 0, -15 dB → 100. */
    private fun rmsToPercent(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20f * log10(rms)
        return ((db + 85f) / 70f * 100f).toInt().coerceIn(0, 100)
    }
}
