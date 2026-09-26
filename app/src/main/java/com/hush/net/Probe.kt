package com.hush.net

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.os.SystemClock
import com.hush.HLog
import com.hush.ProbeReceiver

/**
 * The commander's PROBE and the victim phone's PASSIVE PORT.
 *
 * Passive port: a Bluetooth scan for one 16-bit service UUID, registered with the system through a
 * PendingIntent. The Bluetooth chip does the matching; no Hush process needs to be alive; when a probe
 * advertisement is seen the system starts [ProbeReceiver]. Low-power mode listens about half a second in
 * every five, which is what keeps the battery cost near zero and puts wake latency at up to ~5 s.
 *
 * Probe: the commander advertises that UUID (with its name as service data) at high power for 30 s.
 */
object Probe {
    /** Probe advertisement: 16-bit UUID 0xA5A7 in the Bluetooth base range (one below the Hush tag 0xA5A5 family). */
    val PROBE_UUID: ParcelUuid = ParcelUuid.fromString("0000A5A7-0000-1000-8000-00805F9B34FB")
    /** The Hush tag every awake phone advertises: name suffix, flags, battery. Same value as BleRanging's. */
    val TAG_UUID: ParcelUuid = ParcelUuid.fromString("0000A5A5-0000-1000-8000-00805F9B34FB")

    const val ACTION_SCAN_RESULT = "com.hush.PROBE_SCAN_RESULT"
    const val ACTION_REARM = "com.hush.REARM"
    const val PROBE_SECONDS = 30
    private const val PREFS = "hush"
    private const val KEY_ARMED = "armed"
    private const val REQ_SCAN = 7
    private const val REQ_REARM = 8

    fun isArmed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMED, false)

    private fun scanIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQ_SCAN, Intent(context, ProbeReceiver::class.java).setAction(ACTION_SCAN_RESULT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // mutable: the system fills in the scan results

    private fun rearmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQ_REARM, Intent(context, ProbeReceiver::class.java).setAction(ACTION_REARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Time between unregistering the old scan and registering the new one. A re-registration made in the
     *  same instant went silent three times on 26 Sep (the stack removes the old one asynchronously). */
    private const val SETTLE_MS = 2500L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Registers the passive port. Safe to call repeatedly (the previous registration is replaced after a
     * settle delay). Asynchronous: [onDone] gets a short status for the screen; the log gets it too. Also
     * schedules a re-arm every 15 min, because the registration is lost when Bluetooth is switched off and
     * on and nothing tells us.
     */
    @SuppressLint("MissingPermission")
    fun arm(context: Context, why: String, onDone: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun finish(s: String) { HLog.d(s); onDone?.invoke(s) }
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            finish("Probe port NOT armed ($why): Bluetooth scan permission not granted yet (open Hush once)"); return
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            prefs.edit().putBoolean(KEY_ARMED, true).apply()   // remember the intent; the re-arm alarm retries
            scheduleRearm(app, 2 * 60_000L)
            finish("Probe port NOT armed ($why): Bluetooth is off. Will retry in 2 min."); return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { finish("Probe port NOT armed ($why): no BLE scanner"); return }
        val pi = scanIntent(app)
        try { scanner.stopScan(pi) } catch (e: Exception) { HLog.d("Probe: ignored $e") }
        main.postDelayed({
            val filter = ScanFilter.Builder().setServiceUuid(PROBE_UUID).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setReportDelay(0)
                .build()
            val rc = try { scanner.startScan(listOf(filter), settings, pi) } catch (e: Exception) { HLog.d("Probe port: startScan threw $e"); -1 }
            val ok = rc == 0
            prefs.edit().putBoolean(KEY_ARMED, true).apply()
            scheduleRearm(app, if (ok) AlarmManager.INTERVAL_FIFTEEN_MINUTES else 2 * 60_000L)
            finish(if (ok) "Probe port armed ($why): low-power scan for ${PROBE_UUID.uuid.toString().substring(4, 8).uppercase()}, offloaded filter=${adapter.isOffloadedFilteringSupported}"
                   else "Probe port NOT armed ($why): startScan returned $rc (1=already started 2=app registration failed 3=internal 4=unsupported 5=out of hw resources 6=too frequent). Retry in 2 min.")
        }, SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    fun disarm(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, false).apply()
        try { (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(scanIntent(app)) } catch (e: Exception) { HLog.d("Probe: ignored $e") }
        try { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(rearmIntent(app)) } catch (e: Exception) { HLog.d("Probe: ignored $e") }
        HLog.d("Probe port disarmed")
    }

    /** Later retry without touching the current registration (used when a scan error is reported). */
    fun retryLater(context: Context, delayMs: Long = 2 * 60_000L) = scheduleRearm(context.applicationContext, delayMs)

    private fun scheduleRearm(app: Context, delayMs: Long) {
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Inexact and non-waking: it runs the next time the phone is awake anyway. Costs nothing.
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, AlarmManager.INTERVAL_FIFTEEN_MINUTES, rearmIntent(app))
        } catch (e: Exception) { HLog.d("Probe port: re-arm alarm failed $e") }
    }

    /** The commander's name suffix carried by a probe advertisement, or null if this result is not a probe. */
    fun commanderOf(result: ScanResult): String? {
        val rec = result.scanRecord ?: return null
        val data = rec.getServiceData(PROBE_UUID)
        if (data != null) return String(data, Charsets.US_ASCII)
        return if (rec.serviceUuids?.contains(PROBE_UUID) == true) "?" else null
    }

    // ---- Commander side ----

    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile var probeUntilMs = 0L
        private set
    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { HLog.d("Probe: advertising (tx ${settingsInEffect?.txPowerLevel}, mode ${settingsInEffect?.mode}, timeout ${settingsInEffect?.timeout} ms)") }
        override fun onStartFailure(errorCode: Int) { probeUntilMs = 0L; HLog.d("Probe: advertising FAILED $errorCode (1=data too large 2=too many advertisers 3=already started 4=internal 5=unsupported)") }
    }

    /** Broadcast the probe for [seconds] (max 180). Every armed phone within radio range wakes up. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, commanderName: String, seconds: Int = PROBE_SECONDS): Boolean {
        val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { HLog.d("Probe: Bluetooth is off"); return false }
        val adv = adapter.bluetoothLeAdvertiser ?: run { HLog.d("Probe: no BLE advertiser"); return false }
        advertiser = adv
        try { adv.stopAdvertising(callback) } catch (e: Exception) { HLog.d("Probe: ignored $e") }
        val ms = (seconds * 1000).coerceIn(1000, 180_000)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(ms)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(PROBE_UUID)
            .addServiceData(PROBE_UUID, commanderName.takeLast(4).toByteArray(Charsets.US_ASCII))
            .build()
        return try {
            adv.startAdvertising(settings, data, callback)
            probeUntilMs = SystemClock.elapsedRealtime() + ms
            HLog.d("Probe: started by $commanderName for $seconds s")
            true
        } catch (e: Exception) { HLog.d("Probe: start threw $e"); false }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { advertiser?.stopAdvertising(callback) } catch (e: Exception) { HLog.d("Probe: ignored $e") }
        probeUntilMs = 0L
    }

    val probing: Boolean get() = SystemClock.elapsedRealtime() < probeUntilMs
}
