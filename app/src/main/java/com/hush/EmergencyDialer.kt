package com.hush

import android.content.Context
import com.hush.model.Alert

/**
 * Texts and calls the emergency contacts, then opens the dialer on 112 (docs/PLAN-crash.md, build B). Build A: every
 * step is only described ("DRY RUN: would SMS …") so the whole path can be exercised without a SIM and without
 * sending anything. Each line handed to [report] lands in [CrashGuard.actions], the evidence of what was tried.
 */
object EmergencyDialer {

    fun escalate(context: Context, dryRun: Boolean, cellular: Boolean, cellularWhy: String, alert: Alert, report: (String) -> Unit) {
        val contacts = EmergencyContacts.list(context)
        val number = EmergencyContacts.emergencyNumber(context)
        val text = smsText(context, alert)
        HLog.d("Crash: SMS text would be: $text")
        if (contacts.isEmpty()) report("no emergency contacts set (open EMERGENCY CONTACTS on the first screen)")
        if (!cellular) report("texts and calls not possible: $cellularWhy")
        for (c in contacts) {
            if (dryRun) report("DRY RUN: would SMS ${c.label} ${EmergencyContacts.mask(c.number)}")
            else report("SMS ${c.label}: live texting arrives in build B")
        }
        for (c in contacts) {
            if (dryRun) report("DRY RUN: would call ${c.label} ${EmergencyContacts.mask(c.number)} on speaker")
            else report("call ${c.label}: live calling arrives in build B")
        }
        report(if (dryRun) "DRY RUN: would open the dialer on $number" else "dialer on $number: arrives in build B")
    }

    /** Stops anything still in progress (I'M OK). Nothing to stop in build A. */
    fun cancel(context: Context) {}

    /** The text every contact gets: who, when, where (a map link), battery. */
    fun smsText(context: Context, a: Alert): String {
        val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.atMs))
        val where = if (a.lat != null && a.lon != null) "Position https://maps.google.com/?q=%.5f,%.5f (±%.0f m)".format(a.lat, a.lon, a.gpsAcc ?: 0f) else "No GPS position"
        return "Hush: possible ${a.kind.lowercase()} of the owner of phone ${a.name} at $at, no response for ${CrashGuard.COUNTDOWN_S} s. $where. Battery ${a.battery} %."
    }
}
