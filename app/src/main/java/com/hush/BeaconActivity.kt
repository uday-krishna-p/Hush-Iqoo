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

    private val keepOnMs = 3 * 60_000L
    private val letScreenSleep = Runnable { try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (e: Exception) { HLog.d("BeaconActivity: ignored $e") } }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(letScreenSleep)
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

        findViewById<Button>(R.id.btnBeaconOpen).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        findViewById<Button>(R.id.btnBeaconSafe).setOnClickListener {
            HLog.d("BeaconActivity: I AM SAFE pressed, stopping the sensor")
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
