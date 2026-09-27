package com.hush

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The red screen a woken phone shows over its lock screen: "rescuers are nearby, tap 3 times or shout".
 * Because this activity is on screen, it may start the microphone foreground service, which is how a
 * passive phone becomes a live sensor without anyone touching it.
 */
class BeaconActivity : AppCompatActivity() {

    companion object { private const val KEY_SOS_DONE = "sosDoneForProbe" }

    private val keepOnMs = 3 * 60_000L
    private val letScreenSleep = Runnable { try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (e: Exception) { HLog.d("BeaconActivity: ignored $e") } }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    // SOS: if nobody presses I AM SAFE (or OPEN HUSH) within Sos.UNANSWERED_S of the probe, text the ambulance and
    // the emergency contact. Counted from the probe's time, so a recreated screen does not restart it; once per probe.
    private val prefs by lazy { getSharedPreferences("hush", MODE_PRIVATE) }
    private val probeAt by lazy { Activation.lastProbeAt(this) }
    private val sosTick = object : Runnable {
        override fun run() {
            val view = findViewById<TextView>(R.id.beaconSos) ?: return
            if (prefs.getLong(KEY_SOS_DONE, 0L) == probeAt) return
            if (probeAt <= 0L || System.currentTimeMillis() - probeAt > 10 * 60_000L) { HLog.d("BeaconActivity: no recent probe, no SOS countdown"); return }
            val left = ((probeAt + Sos.UNANSWERED_S * 1000L - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
            if (left > 0) {
                view.text = getString(R.string.beacon_sos_countdown, left)
                view.postDelayed(this, 1000L)
                return
            }
            prefs.edit().putLong(KEY_SOS_DONE, probeAt).apply()
            HLog.d("BeaconActivity: no answer for ${Sos.UNANSWERED_S} s, sending SOS")
            view.text = getString(R.string.beacon_sos_sending)
            Sos.sendUnanswered(this@BeaconActivity) { s -> if (!isDestroyed) view.text = getString(R.string.beacon_sos_result, s) }
        }
    }

    /** The person answered: no SOS for this probe. */
    private fun answered(how: String) {
        if (prefs.getLong(KEY_SOS_DONE, 0L) != probeAt) {
            prefs.edit().putLong(KEY_SOS_DONE, probeAt).apply()
            HLog.d("BeaconActivity: $how pressed, SOS cancelled")
        }
        findViewById<TextView>(R.id.beaconSos)?.removeCallbacks(sosTick)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(letScreenSleep)
        findViewById<TextView>(R.id.beaconSos)?.removeCallbacks(sosTick)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_beacon)
        val commander = intent.getStringExtra(Activation.EXTRA_COMMANDER) ?: Activation.activatedBy(this) ?: "?"
        val rssi = intent.getIntExtra(Activation.EXTRA_RSSI, 0)
        findViewById<TextView>(R.id.beaconCommander).text = getString(R.string.beacon_commander, commander, rssi)
        HLog.d("BeaconActivity shown (commander $commander, rssi $rssi, engine running=${Engine.isRunning})")

        val status = findViewById<TextView>(R.id.beaconStatus)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val btOk = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!Engine.isRunning) {
            if (micOk && btOk) {
                val svc = Intent(this, SensorService::class.java)
                    .putExtra(SensorService.EXTRA_ROLE, Engine.ROLE_SENSOR)
                    .putExtra(SensorService.EXTRA_PROBE_ACTIVATED, true)
                try {
                    ContextCompat.startForegroundService(this, svc)
                    HLog.d("BeaconActivity: sensor service start requested")
                    status.text = getString(R.string.beacon_status_listening)
                } catch (e: Exception) {
                    HLog.d("BeaconActivity: could not start the sensor service: $e")
                    status.text = getString(R.string.beacon_status_failed)
                }
            } else {
                HLog.d("BeaconActivity: permissions missing (mic=$micOk bt=$btOk), cannot listen")
                status.text = getString(R.string.beacon_status_perms)
            }
        } else {
            status.text = getString(R.string.beacon_status_listening)
        }

        findViewById<TextView>(R.id.beaconSos).post(sosTick)

        findViewById<Button>(R.id.btnBeaconOpen).setOnClickListener {
            answered("OPEN HUSH")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        findViewById<Button>(R.id.btnBeaconSafe).setOnClickListener {
            HLog.d("BeaconActivity: I AM SAFE pressed, stopping the sensor")
            answered("I AM SAFE")
            Engine.listener = null
            stopService(Intent(this, SensorService::class.java))
            Engine.stop()
            Activation.cancel(this)
            finish()
        }
        // Let the screen sleep again after a few minutes; the sensor keeps running underneath.
        window.decorView.postDelayed(letScreenSleep, keepOnMs)
    }
}
