package com.hush

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Emergency contacts (docs/PLAN-crash.md, build B): up to three name + number rows in calling order, picked from the
 * phone book (the system picker hands back one number; no contacts permission needed) or typed; the emergency
 * number; SAVE; TEST (describes what an escalation would do right now, sends nothing); TEST CALL (really dials
 * contact 1 after a confirmation). Stored in the app's private preferences.
 */
class ContactsActivity : AppCompatActivity() {

    private val names = ArrayList<EditText>()
    private val numbers = ArrayList<EditText>()
    private var pickingRow = -1

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null || pickingRow < 0) { HLog.d("Contacts: pick cancelled"); return@registerForActivityResult }
        try {
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    numbers[pickingRow].setText(c.getString(0) ?: "")
                    if (names[pickingRow].text.isBlank()) names[pickingRow].setText(c.getString(1) ?: "")
                    HLog.d("Contacts: row ${pickingRow + 1} picked ${EmergencyContacts.mask(c.getString(0) ?: "")}")
                }
            }
        } catch (e: Exception) { HLog.d("Contacts: pick read failed: $e"); toast("Could not read that contact: $e") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HLog.init(applicationContext)
        setContentView(R.layout.activity_contacts)
        names += findViewById<EditText>(R.id.name1); names += findViewById<EditText>(R.id.name2); names += findViewById<EditText>(R.id.name3)
        numbers += findViewById<EditText>(R.id.number1); numbers += findViewById<EditText>(R.id.number2); numbers += findViewById<EditText>(R.id.number3)
        listOf(R.id.pick1, R.id.pick2, R.id.pick3).forEachIndexed { i, id ->
            findViewById<Button>(id).setOnClickListener { pickingRow = i; pickContact() }
        }
        val emergency = findViewById<EditText>(R.id.emergencyNumber)
        val existing = EmergencyContacts.list(this)
        existing.forEachIndexed { i, c -> names[i].setText(c.name); numbers[i].setText(c.number) }
        emergency.setText(EmergencyContacts.emergencyNumber(this))
        val result = findViewById<TextView>(R.id.contactsResult)

        findViewById<Button>(R.id.btnSaveContacts).setOnClickListener {
            val list = (0 until 3).map { EmergencyContacts.Contact(names[it].text.toString().trim(), numbers[it].text.toString().trim()) }.filter { it.number.isNotBlank() }
            EmergencyContacts.save(this, list)
            EmergencyContacts.setEmergencyNumber(this, emergency.text.toString().ifBlank { EmergencyContacts.DEFAULT_EMERGENCY_NUMBER })
            toast(getString(R.string.contacts_saved, list.size))
            result.text = EmergencyDialer.describe(this)
        }
        findViewById<Button>(R.id.btnTestContacts).setOnClickListener {
            HLog.d("Contacts: TEST pressed")
            result.text = EmergencyDialer.describe(this)
        }
        findViewById<Button>(R.id.btnTestCall).setOnClickListener {
            val first = EmergencyContacts.list(this).firstOrNull()
            if (first == null) { toast(getString(R.string.contacts_none)); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.contacts_test_call))
                .setMessage(getString(R.string.contacts_test_call_confirm, first.label, first.number))
                .setPositiveButton(android.R.string.ok) { _, _ -> testCall(first) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        findViewById<Button>(R.id.btnModeToggle).setOnClickListener {
            val now = CrashGuard.mode(this)
            CrashGuard.setMode(this, if (now == CrashGuard.MODE_LIVE) CrashGuard.MODE_DRY_RUN else CrashGuard.MODE_LIVE)
            renderMode()
            result.text = EmergencyDialer.describe(this)
        }
        renderMode()
        result.text = EmergencyDialer.describe(this)
    }

    private fun renderMode() {
        val live = CrashGuard.mode(this) == CrashGuard.MODE_LIVE
        findViewById<Button>(R.id.btnModeToggle).text = getString(if (live) R.string.contacts_mode_live else R.string.contacts_mode_dry)
    }

    private fun pickContact() {
        try {
            pick.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
        } catch (e: Exception) { HLog.d("Contacts: picker failed: $e"); toast("No contact picker on this phone: type the number") }
    }

    /** Really dials contact 1: a direct call when the phone permission is granted, else the dialer with the number typed in. */
    private fun testCall(c: EmergencyContacts.Contact) {
        val direct = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        try {
            startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${c.number}")))
            HLog.d("Contacts: TEST CALL ${if (direct) "placed" else "dialer opened"} for ${c.label} ${EmergencyContacts.mask(c.number)}")
        } catch (e: Exception) { HLog.d("Contacts: TEST CALL failed: $e"); toast("Call failed: $e") }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
