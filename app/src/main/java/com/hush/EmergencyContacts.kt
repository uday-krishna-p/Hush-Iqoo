package com.hush

import android.content.Context

/**
 * The owner's emergency contacts (docs/PLAN-crash.md, build B): up to [MAX] name + number pairs, in calling order,
 * kept in the app's private preferences. Set from the contacts screen or from the laptop:
 * `adb shell am start -n com.hush/.MainActivity --es contacts "Priya:+919…;Ravi:+919…"` (`--es contacts clear` empties it).
 */
object EmergencyContacts {
    const val MAX = 3
    private const val PREFS = "hush_crash"
    private const val KEY = "contacts"
    const val EMERGENCY_NUMBER_KEY = "emergencyNumber"
    const val DEFAULT_EMERGENCY_NUMBER = Sos.AMBULANCE   // 108, the team's choice (27 Sep)

    data class Contact(val name: String, val number: String) {
        val label: String get() = if (name.isBlank()) number else name
    }

    /** The contacts set here, plus the single emergency contact from the first screen (Sos.contact) if it is not already one of them. */
    fun list(context: Context): List<Contact> = try {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val own = parse(raw)
        val sos = Sos.contact(context)
        if (sos == null || own.any { digits(it.number).endsWith(digits(sos).takeLast(10)) }) own
        else (own + Contact("emergency contact", sos)).take(MAX)
    } catch (e: Exception) { HLog.d("Contacts: unreadable, ignored: $e"); emptyList() }

    private fun digits(n: String) = n.filter { it.isDigit() }

    fun save(context: Context, contacts: List<Contact>) {
        val trimmed = contacts.filter { it.number.isNotBlank() }.take(MAX)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, format(trimmed)).apply()
        HLog.d("Contacts: saved ${trimmed.size}: " + trimmed.joinToString("; ") { "${it.label} ${mask(it.number)}" })
    }

    fun emergencyNumber(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EMERGENCY_NUMBER_KEY, DEFAULT_EMERGENCY_NUMBER)?.ifBlank { DEFAULT_EMERGENCY_NUMBER } ?: DEFAULT_EMERGENCY_NUMBER

    fun setEmergencyNumber(context: Context, number: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(EMERGENCY_NUMBER_KEY, number.trim()).apply()
        HLog.d("Contacts: emergency number set to ${number.trim()}")
    }

    /** "Name:+91…;Name2:+91…" (name optional: "+91…" alone is fine); "clear" empties the list. */
    fun parse(spec: String): List<Contact> {
        if (spec.trim().equals("clear", ignoreCase = true) || spec.isBlank()) return emptyList()
        return spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
            val i = item.lastIndexOf(':')
            if (i < 0) Contact("", item) else Contact(item.substring(0, i).trim(), item.substring(i + 1).trim())
        }.filter { it.number.isNotBlank() }.take(MAX)
    }

    fun format(contacts: List<Contact>): String = contacts.joinToString(";") { if (it.name.isBlank()) it.number else "${it.name}:${it.number}" }

    /** For the log: "+91…4321" style, so a number is recognisable without being written out. */
    fun mask(number: String): String = if (number.length <= 4) number else number.take(3) + "…" + number.takeLast(4)
}
