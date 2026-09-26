package com.hush

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Placeholder for the foreground service that will own the microphone and the Nearby link.
 * Declared now so the permissions file never needs another edit; filled in during step 1 and 2.
 */
class SensorService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.d("Hush", "SensorService created (stub)")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
