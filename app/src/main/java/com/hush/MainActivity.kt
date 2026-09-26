package com.hush

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Role picker. Step 1 (Hello mic) replaces the placeholder text with the live RMS bar. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d("Hush", "MainActivity created on ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}")

        val status = findViewById<TextView>(R.id.status)
        findViewById<Button>(R.id.btnCommander).setOnClickListener {
            Log.d("Hush", "Role picked: COMMANDER")
            status.text = getString(R.string.role_commander_placeholder)
        }
        findViewById<Button>(R.id.btnSensor).setOnClickListener {
            Log.d("Hush", "Role picked: SENSOR")
            status.text = getString(R.string.role_sensor_placeholder)
        }
    }
}
