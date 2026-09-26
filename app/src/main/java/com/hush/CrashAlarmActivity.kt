package com.hush

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hush.model.Alert
import com.hush.model.Response

/**
 * The red screen every OTHER Hush phone gets when one phone has escalated a fall alarm (docs/PLAN-crash.md, build A2):
 * who fell, how long ago, how far from here, whether this phone is the closest, and one-tap buttons to call the
 * fallen person's emergency contacts or 112, or to say "I'm going". State lives in [CrashGuard.nearby].
 */
class CrashAlarmActivity : AppCompatActivity(), CrashGuard.Listener {

    private lateinit var who: TextView
    private lateinit var closest: TextView
    private lateinit var details: TextView
    private lateinit var responders: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var btnGoing: Button
    private lateinit var btnDismiss: Button
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_crash_alarm)
        who = findViewById(R.id.alarmWho)
        closest = findViewById(R.id.alarmClosest)
        details = findViewById(R.id.alarmDetails)
        responders = findViewById(R.id.alarmResponders)
        buttons = findViewById(R.id.alarmButtons)
        btnGoing = findViewById(R.id.btnAlarmGoing)
        btnDismiss = findViewById(R.id.btnAlarmDismiss)
        btnGoing.setOnClickListener { CrashGuard.respond(Response.GOING); render() }
        btnDismiss.setOnClickListener { CrashGuard.dismissNearby(); finish() }
        HLog.d("CrashAlarmActivity shown (from ${CrashGuard.nearby?.name}, state ${CrashGuard.nearby?.state}, nearest ${CrashGuard.nearby?.nearest}, me ${Engine.letter})")
        render()
    }

    override fun onStart() { super.onStart(); CrashGuard.nearbyListener = this; render() }
    override fun onStop() { super.onStop(); if (CrashGuard.nearbyListener === this) CrashGuard.nearbyListener = null }
    override fun onCrashChanged() = render()

    /** Opens the phone app with the number typed in (one more tap). Build B upgrades this to a direct call when allowed. */
    private fun dial(number: String, what: String, action: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            HLog.d("CrashAlarmActivity: dialer opened on $what ${EmergencyContacts.mask(number)}")
            CrashGuard.respond(action, what)
        } catch (e: Exception) {
            HLog.d("CrashAlarmActivity: dialer failed for $what: $e")
            android.widget.Toast.makeText(this, "Could not open the dialer: $e", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val a = CrashGuard.nearby
        if (a == null) { finish(); return }
        val me = Engine.letter
        val fallen = (if (a.letter != null) "Sensor ${a.letter}" else "phone") + " (${a.name.takeLast(4)})"
        val ago = ((System.currentTimeMillis() - a.atMs) / 1000).coerceAtLeast(0)
        if (a.state == Alert.CANCELLED) {
            who.text = getString(R.string.nearby_cancelled, fallen)
            closest.text = ""
            buttons.visibility = View.GONE; btnGoing.visibility = View.GONE
            details.text = ""
            responders.text = ""
            return
        }
        who.text = getString(R.string.nearby_who, fallen, a.kind, fmt.format(java.util.Date(a.atMs)), ago)
        val myDist = a.distances[me]
        closest.text = when {
            a.nearest == null -> getString(R.string.nearby_unknown)
            a.nearest == me -> getString(R.string.nearby_you_closest, myDist ?: "?")
            else -> getString(R.string.nearby_other_closer, a.nearest, a.distances[a.nearest] ?: "?", myDist ?: "?")
        }
        closest.setTextColor(if (a.nearest == me) 0xFFFFEB3B.toInt() else 0xFFFFFFFF.toInt())
        val gps = if (a.lat != null && a.lon != null) "GPS %.5f, %.5f (±%.0f m)".format(a.lat, a.lon, a.gpsAcc ?: 0f) else "no GPS position"
        val cell = if (a.cellular) "their phone can call" else "their phone cannot call (no network)"
        val dists = a.distances.entries.joinToString("  ") { "${it.key}: ${it.value}" }
        details.text = listOf(gps, "battery ${a.battery} %", cell, if (dists.isEmpty()) "" else "distances $dists",
            if (a.actions.isEmpty()) "" else "their phone did:\n  " + a.actions.takeLast(5).joinToString("\n  ")).filter { it.isNotEmpty() }.joinToString("\n")
        // One button per contact of the fallen person, then 112.
        buttons.removeAllViews()
        buttons.visibility = View.VISIBLE
        for ((name, number) in a.contacts) {
            val b = Button(this)
            b.text = getString(R.string.nearby_call_contact, name.ifBlank { number })
            b.textSize = 20f
            b.setBackgroundColor(0xFFFFFFFF.toInt()); b.setTextColor(0xFFB71C1C.toInt())
            b.setOnClickListener { dial(number, name.ifBlank { number }, Response.CALLED_CONTACT) }
            buttons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160).also { it.bottomMargin = 12 })
        }
        val emergency = EmergencyContacts.emergencyNumber(this)
        val b112 = Button(this)
        b112.text = getString(R.string.nearby_call_112, emergency)
        b112.textSize = 20f
        b112.setBackgroundColor(0xFFFFFFFF.toInt()); b112.setTextColor(0xFFB71C1C.toInt())
        b112.setOnClickListener { dial(emergency, emergency, Response.CALLED_112) }
        buttons.addView(b112, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160).also { it.bottomMargin = 12 })
        btnGoing.visibility = View.VISIBLE
        btnGoing.isEnabled = CrashGuard.myResponse != Response.GOING
        btnGoing.text = if (CrashGuard.myResponse == Response.GOING) getString(R.string.nearby_going_sent) else getString(R.string.nearby_going)
        responders.text = if (a.responders.isEmpty()) getString(R.string.nearby_nobody_yet) else getString(R.string.nearby_responders_heading) + "\n" + a.responders.joinToString("\n")
    }
}
