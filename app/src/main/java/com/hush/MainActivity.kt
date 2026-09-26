package com.hush

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hush.ui.SensorScreen

/** Role picker, then the listening screen. Step 2 adds the Nearby link and the commander screen. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MIC = 1
    }

    private var role: String? = null
    private var screen: SensorScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        HLog.d("MainActivity created on ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showRolePicker()
    }

    private fun showRolePicker() {
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnCommander).setOnClickListener { pickRole("COMMANDER") }
        findViewById<Button>(R.id.btnSensor).setOnClickListener { pickRole("SENSOR") }
    }

    private fun pickRole(name: String) {
        HLog.d("Role picked: $name")
        role = name
        if (hasMicPermission()) {
            showSensorScreen()
        } else {
            HLog.d("Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        HLog.d("RECORD_AUDIO granted: $granted")
        if (granted) {
            showSensorScreen()
        } else {
            role = null
            findViewById<TextView>(R.id.status).text = getString(R.string.mic_denied)
        }
    }

    private fun showSensorScreen() {
        val name = role ?: return
        setContentView(R.layout.screen_sensor)
        screen = SensorScreen(this, name).also { it.start() }
    }

    override fun onStart() {
        super.onStart()
        // Coming back after the screen was off or the app was backgrounded: restart listening.
        if (screen == null && role != null && hasMicPermission()) showSensorScreen()
    }

    override fun onStop() {
        super.onStop()
        // Step 2 moves listening into SensorService so it survives the screen turning off.
        screen?.stop()
        screen = null
    }
}
