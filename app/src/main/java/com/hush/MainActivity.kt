package com.hush

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hush.net.Probe
import com.hush.ui.CommanderScreen
import com.hush.ui.SensorScreen

/**
 * First screen: asks for permissions and ARMS the phone (passive probe port), then offers the two roles.
 * An armed phone can be closed; a commander's probe wakes it through [ProbeReceiver] → [BeaconActivity].
 * The work happens in [Engine] inside [SensorService].
 *
 * Laptop hooks (no coordinate tapping needed):
 *   adb shell am start -n com.hush/.MainActivity --es role COMMANDER     (or SENSOR)
 *   adb shell am start -n com.hush/.MainActivity --ez probe true          (commander: ACTIVATE SENSORS)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 1
        const val EXTRA_ROLE = "role"
        const val EXTRA_PROBE = "probe"
    }

    private var role: String? = null
    private var screen: SensorScreen? = null
    private var pendingRole: String? = null

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

        // If the service is already running (screen was rotated / app reopened / woken by a probe), go straight to that role.
        val running = Engine.role
        if (running != null) {
            role = running
            showRoleScreen()
        } else {
            showRolePicker()
            val missing = missingPermissions()
            if (missing.isNotEmpty()) {
                HLog.d("First launch: requesting permissions $missing")
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
            } else {
                armed()
            }
        }
        handleHooks(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleHooks(intent)
    }

    private fun handleHooks(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROLE)?.let { r ->
            HLog.d("Hook: role $r")
            if (Engine.role == null) pickRole(r)
        }
        if (intent.getBooleanExtra(EXTRA_PROBE, false)) {
            HLog.d("Hook: probe")
            if (Engine.role == Engine.ROLE_COMMANDER) Engine.activateSensors() else HLog.d("Hook: probe ignored, not a commander")
        }
        intent.removeExtra(EXTRA_ROLE); intent.removeExtra(EXTRA_PROBE)
    }

    private fun showRolePicker() {
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnCommander).setOnClickListener { pickRole(Engine.ROLE_COMMANDER) }
        findViewById<Button>(R.id.btnSensor).setOnClickListener { pickRole(Engine.ROLE_SENSOR) }
        findViewById<Button>(R.id.btnBackground).setOnClickListener { askBackgroundAllowance(force = true) }
        findViewById<TextView>(R.id.status).text = getString(R.string.pick_role)
    }

    private fun backgroundAllowed(): Boolean =
        try { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) } catch (_: Exception) { false }

    private var askedBackground = false

    /**
     * The system dialog "Allow Hush to run in the background?". Without it the passive port is registered
     * but never delivers on these phones (measured 26 Sep: scans ran for minutes with zero results; with the
     * exemption the same probe woke the phone in 2–12 s).
     */
    private fun askBackgroundAllowance(force: Boolean = false) {
        if (backgroundAllowed()) return
        if (askedBackground && !force) return
        askedBackground = true
        try {
            @Suppress("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
            startActivity(i)
            HLog.d("Asked for the battery-optimisation exemption")
        } catch (e: Exception) {
            HLog.d("Battery exemption dialog failed: $e")
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system dialog: refresh the armed text.
        if (role == null && findViewById<TextView>(R.id.status) != null && Probe.isArmed(this) && missingPermissions().isEmpty()) showArmed(lastArmStatus)
    }

    private var lastArmStatus = "Probe port armed"


    /** Permissions granted: register the passive port (takes a few seconds) and say so on screen. */
    private fun armed() {
        findViewById<TextView>(R.id.status)?.text = getString(R.string.arming_status)
        Probe.arm(this, "app opened") { s -> if (!isDestroyed) showArmed(s) }
    }

    private fun showArmed(s: String) {
        lastArmStatus = s
        val ok = s.startsWith("Probe port armed")
        val nm = getSystemService(NotificationManager::class.java)
        val notif = nm.areNotificationsEnabled()
        val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val bg = backgroundAllowed()
        HLog.d("Armed check: scan=$ok notifications=$notif fullScreen=$fsi backgroundAllowed=$bg")
        val text = buildString {
            append(if (ok && bg) getString(R.string.armed_status) else if (ok) getString(R.string.armed_no_background) else getString(R.string.not_armed_status, s))
            if (!notif) append("\n\n").append(getString(R.string.armed_no_notifications))
            if (!fsi) append("\n\n").append(getString(R.string.armed_no_fullscreen))
        }
        findViewById<TextView>(R.id.status)?.text = text
        findViewById<Button>(R.id.btnBackground)?.visibility = if (bg) android.view.View.GONE else android.view.View.VISIBLE
        if (!bg) askBackgroundAllowance()
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
            p += Manifest.permission.POST_NOTIFICATIONS          // the rescue alert on a woken phone
        }
        p += Manifest.permission.ACCESS_FINE_LOCATION      // GPS overlay for large sites
        p += Manifest.permission.ACTIVITY_RECOGNITION      // step detector for placement dead reckoning
        if (Build.VERSION.SDK_INT >= 36) p += "android.permission.RANGING"   // Bluetooth Channel Sounding spike
        return p
    }

    private fun missingPermissions() = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    /** What a role needs at minimum; notifications, GPS and steps are nice-to-have. */
    private fun essentialMissing() = missingPermissions().filter {
        it == Manifest.permission.RECORD_AUDIO || it.startsWith("android.permission.BLUETOOTH")
    }

    private fun pickRole(name: String) {
        HLog.d("Role picked: $name")
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            role = name
            startRole()
        } else {
            pendingRole = name
            HLog.d("Requesting permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val missing = missingPermissions()
        HLog.d("Permissions result, still missing: $missing")
        val wanted = pendingRole
        pendingRole = null
        if (essentialMissing().isNotEmpty()) {
            findViewById<TextView>(R.id.status)?.text = getString(R.string.perms_denied)
            return
        }
        if (wanted != null) {
            role = wanted
            startRole()
        } else {
            armed()
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
        Activation.cancel(this)
        screen = null
        role = null
        showRolePicker()
        // The port was never unregistered while the role ran (probes are simply ignored while active).
        if (Probe.isArmed(this)) findViewById<TextView>(R.id.status)?.text = getString(R.string.armed_status)
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
