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
 *   adb shell am start -n com.hush/.MainActivity --ez hush true           (commander: HUSH, solo allowed)
 *   adb shell am start -n com.hush/.MainActivity --ez range true          (commander: chirp ranging round)
 *   adb shell am start -n com.hush/.MainActivity --es play knocks.wav --ef level 0.5   (any role: play files/knocks.wav)
 *   adb shell am start -n com.hush/.MainActivity --es layout clear                      (commander: drop the stored hand layout and pointing, so GPS may place the phones)
 *   adb shell am start -n com.hush/.MainActivity --es mic1top false                     (any role: recording channel 1 is the BOTTOM mic)
 *   adb shell am start -n com.hush/.MainActivity --ef micspacing 0.14                   (any role: distance between the two mics, metres)
 *   adb shell am start -n com.hush/.MainActivity --ez sync true                         (any role: SYNC COMPASS, phones lying parallel)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 1
        const val EXTRA_ROLE = "role"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_HUSH = "hush"
        const val EXTRA_RANGE = "range"
        const val EXTRA_ALIGN = "align"
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_PLAY = "play"
        const val EXTRA_PLAY_LEVEL = "level"
        const val EXTRA_MIC1TOP = "mic1top"
        const val EXTRA_MICSPACING = "micspacing"
        const val EXTRA_ALERTTEST = "alerttest"
        const val EXTRA_CAPTIONS = "captions"
        const val EXTRA_SYNC = "sync"
    }

    private var role: String? = null
    private var screen: Engine.Listener? = null
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
        if (intent.getBooleanExtra(EXTRA_HUSH, false)) {
            HLog.d("Hook: hush")
            if (Engine.role == Engine.ROLE_COMMANDER) { Engine.allowSoloHush = true; Engine.hush(20) } else HLog.d("Hook: hush ignored, not a commander")
        }
        if (intent.getBooleanExtra(EXTRA_RANGE, false)) {
            HLog.d("Hook: range")
            if (Engine.role == Engine.ROLE_COMMANDER) Engine.autoPlace() else HLog.d("Hook: range ignored, not a commander")
        }
        if (intent.hasExtra(EXTRA_CAPTIONS)) {
            val on = intent.getBooleanExtra(EXTRA_CAPTIONS, false)
            HLog.d("Hook: captions $on")
            if (Engine.role == Engine.ROLE_ALERT) HLog.d("Hook: captions now ${Engine.setCaptions(on)}") else HLog.d("Hook: captions ignored, not the ALERT role")
        }
        intent.getStringExtra(EXTRA_ALERTTEST)?.let { c ->
            HLog.d("Hook: alerttest $c")
            HLog.d("Hook: " + Engine.alertTest(c))
        }
        intent.getStringExtra(EXTRA_ALIGN)?.let { l ->
            HLog.d("Hook: align $l")
            HLog.d("Hook: align result: " + Engine.alignByPointing(l))
        }
        intent.getStringExtra(EXTRA_LAYOUT)?.let { spec ->
            HLog.d("Hook: layout $spec")
            HLog.d("Hook: layout result: " + Engine.setLayout(spec))
        }
        if (intent.hasExtra(EXTRA_MIC1TOP) || intent.hasExtra(EXTRA_MICSPACING)) {
            val top = intent.getStringExtra(EXTRA_MIC1TOP)?.toBoolean()
            val spacing = if (intent.hasExtra(EXTRA_MICSPACING)) intent.getFloatExtra(EXTRA_MICSPACING, 0f) else null
            HLog.d("Hook: mic geometry mic1top=$top spacing=$spacing")
            HLog.d("Hook: " + Engine.setMicGeometry(top, spacing))
        }
        if (intent.getBooleanExtra(EXTRA_SYNC, false)) {
            HLog.d("Hook: sync compasses")
            HLog.d("Hook: sync result: " + Engine.syncCompasses())
        }
        intent.getStringExtra(EXTRA_PLAY)?.let { name ->
            val fraction = intent.getFloatExtra(EXTRA_PLAY_LEVEL, 0.5f)
            HLog.d("Hook: play $name at $fraction")
            if (Engine.isRunning) Engine.playFile(name, fraction) else HLog.d("Hook: play ignored, engine not running")
        }
        intent.removeExtra(EXTRA_ROLE); intent.removeExtra(EXTRA_PROBE); intent.removeExtra(EXTRA_HUSH)
        intent.removeExtra(EXTRA_RANGE); intent.removeExtra(EXTRA_PLAY); intent.removeExtra(EXTRA_PLAY_LEVEL); intent.removeExtra(EXTRA_ALIGN); intent.removeExtra(EXTRA_LAYOUT)
        intent.removeExtra(EXTRA_MIC1TOP); intent.removeExtra(EXTRA_MICSPACING); intent.removeExtra(EXTRA_ALERTTEST); intent.removeExtra(EXTRA_CAPTIONS); intent.removeExtra(EXTRA_SYNC)
    }

    private fun showRolePicker() {
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnCommander).setOnClickListener { pickRole(Engine.ROLE_COMMANDER) }
        findViewById<Button>(R.id.btnSensor).setOnClickListener { pickRole(Engine.ROLE_SENSOR) }
        findViewById<Button>(R.id.btnAlerts).setOnClickListener { pickRole(Engine.ROLE_ALERT) }
        findViewById<Button>(R.id.btnHome).setOnClickListener { pickRole(Engine.ROLE_HOME) }
        findViewById<Button>(R.id.btnBackground).setOnClickListener { askBackgroundAllowance(force = true) }
        findViewById<TextView>(R.id.status).text = getString(R.string.pick_role)
    }

    private fun backgroundAllowed(): Boolean =
        try { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) } catch (e: Exception) { HLog.d("battery exemption check failed: $e"); false }

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
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { HLog.d("MainActivity: ignored $e") }
        }
    }

    override fun onResume() {
        super.onResume()
        // If a role is running, ask the service to (re)enter the foreground now that we are on screen: a role
        // started while the screen was off never became a foreground service and loses the microphone.
        if (role != null && Engine.isRunning) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, SensorService::class.java).putExtra(SensorService.EXTRA_ROLE, role))
            } catch (e: Exception) { HLog.d("MainActivity: could not re-assert the foreground service: $e") }
        }
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

    /** The household roles only listen: microphone (and notifications for the alerts). No Bluetooth, location or steps asked for. */
    private fun neededFor(roleName: String, perms: List<String>): List<String> =
        if (Engine.isHousehold(roleName)) perms.filter { it == Manifest.permission.RECORD_AUDIO || it == Manifest.permission.POST_NOTIFICATIONS } else perms

    private fun pickRole(name: String) {
        HLog.d("Role picked: $name")
        val missing = neededFor(name, missingPermissions())
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
        if ((if (wanted != null) neededFor(wanted, essentialMissing()) else essentialMissing()).isNotEmpty()) {
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
        screen = if (name == Engine.ROLE_ALERT) {
            setContentView(R.layout.screen_alert)
            com.hush.ui.AlertScreen(this)
        } else if (name == Engine.ROLE_HOME) {
            setContentView(R.layout.screen_home)
            com.hush.ui.HomeScreen(this)
        } else {
            // Every rescue phone shows the same screen (27 Sep, team); on a sensor its data comes from the commander.
            setContentView(R.layout.screen_commander)
            CommanderScreen(this)
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
