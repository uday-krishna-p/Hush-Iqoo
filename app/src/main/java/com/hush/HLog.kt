package com.hush

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat AND to a private file, because the iQOO phones drop app logcat output.
 * Read it from the laptop with:  adb shell run-as com.hush cat files/hush.log
 */
object HLog {
    private const val TAG = "Hush"
    private const val MAX_BYTES = 2_000_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "hush.log").also {
            if (it.length() > MAX_BYTES) it.delete()
        }
        d("---- HLog started ----")
    }

    @Synchronized
    fun d(msg: String) {
        Log.d(TAG, msg)
        val f = file ?: return
        try {
            FileWriter(f, true).use { it.write("${fmt.format(Date())} $msg\n") }
        } catch (_: Exception) {
        }
    }
}
