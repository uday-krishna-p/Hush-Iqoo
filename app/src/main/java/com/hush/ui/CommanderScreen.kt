package com.hush.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.hush.Engine
import com.hush.R
import com.hush.model.SensorEvent

/**
 * Commander: HUSH button, big countdown, list of connected sensors with their latest second,
 * plus the commander's own mic block (it is Sensor A). Ranking arrives in the Listen+Rank step.
 */
class CommanderScreen(activity: Activity) : SensorScreen(activity, activity.getString(R.string.my_mic_a)) {

    private val btnHush: Button = activity.findViewById(R.id.btnHush)
    private val bigCountdown: TextView = activity.findViewById(R.id.bigCountdown)
    private val peersText: TextView = activity.findViewById(R.id.peersText)
    private val commanderStatus: TextView = activity.findViewById(R.id.commanderStatus)

    private var peers: List<Engine.Peer> = emptyList()
    private val latest = LinkedHashMap<String, SensorEvent>()   // letter → newest event

    init {
        btnHush.setOnClickListener { Engine.hush(20) }
        render()
    }

    override fun onLinkStatus(text: String) {
        super.onLinkStatus(text)
        commanderStatus.text = text
    }

    override fun onCountdown(secondsLeft: Int) {
        super.onCountdown(secondsLeft)
        if (secondsLeft < 0) {
            bigCountdown.visibility = View.GONE
            btnHush.isEnabled = true
            btnHush.text = activity.getString(R.string.hush_button)
        } else {
            bigCountdown.visibility = View.VISIBLE
            bigCountdown.text = secondsLeft.toString()
            btnHush.isEnabled = false
            btnHush.text = activity.getString(R.string.hush_running)
        }
    }

    override fun onEvent(event: SensorEvent, peerName: String) {
        latest[event.sensorId] = event
        render()
    }

    override fun onPeers(peers: List<Engine.Peer>) {
        this.peers = peers
        render()
    }

    private fun render() {
        val sb = StringBuilder()
        val names = HashMap<String, String>().apply { peers.forEach { put(it.letter, it.name) } }
        val letters = (listOf("A") + peers.map { it.letter } + latest.keys).distinct().sorted()
        for (l in letters) {
            val e = latest[l]
            val name = if (l == "A") activity.getString(R.string.this_phone) else names[l] ?: activity.getString(R.string.offline)
            sb.append(l).append("  ").append(name).append('\n')
            if (e != null) {
                val rh = if (e.rhythm != null) " · ${e.rhythm}" else ""
                sb.append("   ${e.label}$rh\n")
                sb.append("   rms %.4f  taps %d  rhythm %.1f  voice %.2f  machine %.2f\n".format(e.rms, e.taps, e.rhythmScore, e.human, e.machine))
            } else {
                sb.append("   ").append(activity.getString(R.string.no_data_yet)).append('\n')
            }
        }
        peersText.text = sb.toString()
    }
}
