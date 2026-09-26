package com.hush

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hush.net.Probe

/**
 * Runs when the system has something for the passive port: a probe advertisement was seen (delivered
 * through the PendingIntent scan), the phone finished booting, the app was updated, or the half-hourly
 * re-arm alarm fired. No Hush process needs to be alive beforehand; the system starts one for this.
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        HLog.init(context.applicationContext)
        when (intent.action) {
            Probe.ACTION_SCAN_RESULT -> onScanResults(context, intent)
            Probe.ACTION_REARM, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!Probe.isArmed(context)) { HLog.d("ProbeReceiver: ${intent.action}, not armed by the user, nothing to do"); return }
                // After a boot Bluetooth may still be starting, and right after an update the stack is still
                // clearing the old registration: arm a little later, keeping the process alive meanwhile
                // (background broadcasts allow up to 60 s before finish()).
                val why = intent.action ?: "?"
                val delay = when (intent.action) { Intent.ACTION_BOOT_COMPLETED -> 10_000L; Intent.ACTION_MY_PACKAGE_REPLACED -> 3_000L; else -> 0L }
                HLog.d("ProbeReceiver: $why, arming in ${delay / 1000} s")
                val pending = goAsync()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { Probe.arm(context.applicationContext, why) { pending.finish() } } catch (e: Exception) { HLog.d("ProbeReceiver: arm threw $e"); pending.finish() }
                }, delay)
            }
            else -> HLog.d("ProbeReceiver: unexpected ${intent.action}")
        }
    }

    private fun onScanResults(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) { HLog.d("Probe port: scan error $error (1=already started 2=app registration failed 5=out of hw resources 6=too frequent), retry in 2 min"); Probe.retryLater(context); return }
        @Suppress("DEPRECATION")
        val results: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        else intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: emptyList()
        var best: ScanResult? = null; var commander: String? = null
        for (r in results) {
            val c = Probe.commanderOf(r) ?: continue
            if (best == null || r.rssi > best.rssi) { best = r; commander = c }
        }
        if (best == null || commander == null) { HLog.d("Probe port: ${results.size} result(s), none a probe"); return }
        HLog.d("Probe heard from commander $commander rssi=${best.rssi} address=${best.device.address}")
        Activation.onProbe(context, commander, best.rssi)
    }
}
