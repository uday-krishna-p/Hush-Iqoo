package com.hush

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hush.ui.CommanderScreen
import com.hush.ui.SensorScreen

/** Role picker, then the commander or sensor screen. The work happens in [Engine] inside [SensorService]. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 1
    }

    private var role: String? = null
    private var screen: SensorScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        HLog.d("MainActivity created on ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (role != null) leaveRole() else finish()
            }
        })

        // If the service is already running (screen was rotated / app reopened), go straight to that role.
        val running = Engine.role
        if (running != null) {
            role = running
            showRoleScreen()
        } else {
            showRolePicker()
        }
    }

    private fun showRolePicker() {
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnCommander).setOnClickListener { pickRole(Engine.ROLE_COMMANDER) }
        findViewById<Button>(R.id.btnSensor).setOnClickListener { pickRole(Engine.ROLE_SENSOR) }
    }

    private fun requiredPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        p += Manifest.permission.ACCESS_FINE_LOCATION      // GPS overlay for large sites
        p += Manifest.permission.ACTIVITY_RECOGNITION      // step detector for placement dead reckoning
        if (Build.VERSION.SDK_INT >= 36) p += "android.permission.RANGING"   // Bluetooth Channel Sounding spike
        return p
    }

    private fun missingPermissions() = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun pickRole(name: String) {
        HLog.d("Role picked: $name")
        role = name
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startRole()
        } else {
            HLog.d("Requesting permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val missing = missingPermissions()
        HLog.d("Permissions result, still missing: $missing")
        if (missing.isEmpty()) {
            startRole()
        } else {
            role = null
            findViewById<TextView>(R.id.status)?.text = getString(R.string.perms_denied)
        }
    }

    private fun startRole() {
        val name = role ?: return
        val intent = Intent(this, SensorService::class.java).putExtra(SensorService.EXTRA_ROLE, name)
        ContextCompat.startForegroundService(this, intent)
        showRoleScreen()
    }

    private fun showRoleScreen() {
        val name = role ?: return
        screen = if (name == Engine.ROLE_COMMANDER) {
            setContentView(R.layout.screen_commander)
            CommanderScreen(this)
        } else {
            setContentView(R.layout.screen_sensor)
            SensorScreen(this, getString(R.string.role_sensor))
        }
        Engine.listener = screen
    }

    private fun leaveRole() {
        HLog.d("Leaving role $role")
        Engine.listener = null
        stopService(Intent(this, SensorService::class.java))
        Engine.stop()
        screen = null
        role = null
        showRolePicker()
    }

    override fun onStart() {
        super.onStart()
        if (screen != null) Engine.listener = screen
    }

    override fun onStop() {
        super.onStop()
        // The service keeps listening; we just stop drawing while hidden.
        Engine.listener = null
    }
}
