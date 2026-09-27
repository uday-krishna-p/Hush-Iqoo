package com.hush

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/**
 * Camera-flash strobe for alerts (persona C): visible with the phone face down or across a room. No camera
 * permission is needed for the torch. Never faster than 3 Hz (photosensitive users). Main thread.
 */
object Torch {
    private val main = Handler(Looper.getMainLooper())
    private var cameraId: String? = null
    private var on = false
    private var stopAtMs = 0L
    private var manager: CameraManager? = null

    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= stopAtMs) { set(false); return }
            set(!on)
            main.postDelayed(this, 170)   // ~3 Hz
        }
    }

    private fun set(state: Boolean) {
        val id = cameraId ?: return
        try { manager?.setTorchMode(id, state); on = state } catch (e: Exception) { HLog.d("TORCH: set failed $e"); on = false }
    }

    /** Flashes for [durationMs] (or until [stop]). */
    fun strobe(context: Context, durationMs: Long) {
        try {
            if (manager == null) {
                manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraId = manager!!.cameraIdList.firstOrNull { id ->
                    val c = manager!!.getCameraCharacteristics(id)
                    c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
                HLog.d("TORCH: camera with flash = $cameraId")
            }
            if (cameraId == null) return
            stopAtMs = System.currentTimeMillis() + durationMs
            main.removeCallbacks(tick)
            main.post(tick)
        } catch (e: Exception) { HLog.d("TORCH: strobe failed $e") }
    }

    fun stop() { main.removeCallbacks(tick); set(false) }
}
