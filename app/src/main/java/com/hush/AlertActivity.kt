package com.hush

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The colour screen a household alert brings up over the lock screen (persona C): the whole screen in the
 * category's colour, the word in the largest type, the time. It flashes colour/black at 2 Hz for the first
 * 3 s (never faster than 3 Hz: photosensitive users) and then holds. Tap anywhere to dismiss, which also stops a
 * repeating alarm buzz. Same mechanism as [BeaconActivity]: opened by a notification's full-screen intent, so it
 * turns the screen on and shows when locked.
 */
class AlertActivity : AppCompatActivity() {

    companion object {
        const val FLASH_MS = 250L       // 2 Hz
        const val FLASH_TOTAL_MS = 3000L
        const val AUTO_CLOSE_MS = 120_000L
    }

    private var colour = Color.RED
    private var flashesLeft = 0
    private val root: View get() = findViewById(R.id.alertRoot)
    private val flash = object : Runnable {
        override fun run() {
            if (flashesLeft <= 0) { root.setBackgroundColor(colour); return }
            flashesLeft--
            root.setBackgroundColor(if (flashesLeft % 2 == 0) colour else Color.BLACK)
            root.postDelayed(this, FLASH_MS)
        }
    }
    private val autoClose = Runnable { HLog.d("AlertActivity: auto-closed"); finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full brightness: the colour must be seen from across the room or through closed eyelids at night.
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        setContentView(R.layout.activity_alert)
        root.setOnClickListener { dismiss() }
        findViewById<View>(R.id.alertDismiss).setOnClickListener { dismiss() }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent) {
        val word = i.getStringExtra(Alerting.EXTRA_WORD) ?: "SOUND"
        colour = i.getIntExtra(Alerting.EXTRA_COLOUR, Color.RED)
        val at = i.getLongExtra(Alerting.EXTRA_AT, System.currentTimeMillis())
        val repeats = i.getBooleanExtra(Alerting.EXTRA_REPEATS, false)
        HLog.d("AlertActivity shown: '$word' category=${i.getStringExtra(Alerting.EXTRA_CATEGORY)}")
        findViewById<TextView>(R.id.alertWord).text = word
        findViewById<TextView>(R.id.alertTime).text = getString(R.string.alert_heard_at, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at)))
        findViewById<TextView>(R.id.alertHint).text = getString(if (repeats) R.string.alert_tap_to_stop else R.string.alert_tap_to_dismiss)
        flashesLeft = (FLASH_TOTAL_MS / FLASH_MS).toInt()
        root.removeCallbacks(flash); root.post(flash)
        root.removeCallbacks(autoClose); root.postDelayed(autoClose, if (Alerting.night) 10 * AUTO_CLOSE_MS else AUTO_CLOSE_MS)
    }

    private fun dismiss() {
        Alerting.dismiss(this)
        finish()
    }

    override fun onDestroy() {
        root.removeCallbacks(flash); root.removeCallbacks(autoClose)
        super.onDestroy()
    }
}
