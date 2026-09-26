package com.hush.audio

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.hush.HLog

/** Plain framework GPS (no extra library). A fix is "fresh" for 60 s. Coarse overlay for large outdoor sites. */
class Gps(context: Context) : LocationListener {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var last: Location? = null
    @Volatile private var lastAtMs = 0L

    val fix: Location? get() = if (SystemClock.elapsedRealtime() - lastAtMs < 60_000) last else null

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { HLog.d("GPS: provider disabled"); return }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            HLog.d("GPS: requested updates")
        } catch (e: Exception) {
            HLog.d("GPS: start failed (permission?): $e")
        }
    }

    fun stop() {
        try { manager.removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        last = location
        lastAtMs = SystemClock.elapsedRealtime()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
