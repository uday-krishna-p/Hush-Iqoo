package com.hush

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The fallen phone's own screen (docs/PLAN-crash.md, build A): red, over the lock screen, a big countdown, a huge
 * I'M OK button, CALL NOW, and once escalated the list of what the phone did. The state lives in [CrashGuard];
 * this only draws it, so it survives being closed and reopened.
 */
class CrashActivity : AppCompatActivity(), CrashGuard.Listener {

    private lateinit var title: TextView
    private lateinit var countdown: TextView
    private lateinit var message: TextView
    private lateinit var actions: TextView
    private lateinit var btnOk: Button
    private lateinit var btnCall: Button

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_crash)
        title = findViewById(R.id.crashTitle)
        countdown = findViewById(R.id.crashCountdown)
        message = findViewById(R.id.crashMessage)
        actions = findViewById(R.id.crashActions)
        btnOk = findViewById(R.id.btnCrashOk)
        btnCall = findViewById(R.id.btnCrashCall)
        btnOk.setOnClickListener { CrashGuard.imOk(); finish() }
        btnCall.setOnClickListener { CrashGuard.callNow() }
        HLog.d("CrashActivity shown (state ${CrashGuard.state}, kind ${CrashGuard.kind})")
        render()
    }

    override fun onStart() { super.onStart(); CrashGuard.listener = this; render() }
    override fun onStop() { super.onStop(); if (CrashGuard.listener === this) CrashGuard.listener = null }

    override fun onCrashChanged() = render()

    private fun render() {
        val st = CrashGuard.state
        if (st == CrashGuard.State.IDLE) { finish(); return }
        title.text = when (CrashGuard.kind) {
            "FALL" -> getString(R.string.crash_title_fall)
            "TEST" -> getString(R.string.crash_title_test)
            else -> getString(R.string.crash_title_impact)
        }
        val contacts = EmergencyContacts.list(this)
        val (cell, why) = CrashGuard.cellular(this)
        val plan = when {
            !cell -> getString(R.string.crash_no_cell, why)
            contacts.isEmpty() -> getString(R.string.crash_no_contacts, EmergencyContacts.emergencyNumber(this))
            else -> getString(R.string.crash_cell, contacts.joinToString(", ") { it.label }, EmergencyContacts.emergencyNumber(this))
        }
        val dry = if (CrashGuard.dryRun(this)) "\n" + getString(R.string.crash_dry_run) else ""
        if (st == CrashGuard.State.COUNTDOWN) {
            countdown.text = CrashGuard.secondsLeft().toString()
            message.text = getString(R.string.crash_countdown_text, CrashGuard.secondsLeft()) + "\n\n" + plan + dry
            btnCall.visibility = View.VISIBLE
            actions.text = ""
        } else {
            countdown.text = getString(R.string.crash_escalated)
            message.text = plan + dry
            btnCall.visibility = View.GONE
            val resp = CrashGuard.responders
            actions.text = (CrashGuard.actions + (if (resp.isEmpty()) emptyList() else listOf("") + resp)).joinToString("\n")
        }
    }
}
