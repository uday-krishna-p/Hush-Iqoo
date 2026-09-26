package com.hush.model

import com.hush.HLog
import org.json.JSONObject

/** One second of one sensor's hearing. This is the only thing that crosses the network. */
data class SensorEvent(
    val sensorId: String,        // "A" is always the commander
    val tMs: Long,               // sender's SystemClock.elapsedRealtime()
    val rms: Float,              // 0..1 raw band-passed loudness of the last 1 s
    val floor: Float,            // 0..1 this phone's noise floor from just before the Hush window
    val human: Float,            // 0..1 YAMNet HUMAN bucket
    val machine: Float,          // 0..1 YAMNet MACHINE bucket
    val topClass: String,
    val taps: Int = 0,           // knocks found by TapDetector in this second
    val tapScore: Float = 0f,    // 0..1 for this second alone
    val impact: Float = 0f,      // 0..1 YAMNet IMPACT bucket (corroboration only)
    val rhythmScore: Float = 0f, // 0..1 deliberate-tapping confidence over the last 8 s
    val rhythm: String? = null,  // e.g. "3-2" or "steady"
    val label: String = "",      // fused headline: "HUMAN TAPPING", "HUMAN VOICE", "MACHINERY", "MOVEMENT", "quiet"
    val accel: Int = 0,          // accelerometer spikes this second (structure-borne knocks)
    val accelMax: Float = 0f,    // m/s², largest jolt this second
    val moving: Boolean = false, // phone handled / shaken this second: audio from it is suspect, label still shown
    val battery: Int = -1,       // percent
    val chirpTs: Long? = null
) {
    fun toJson(): String = JSONObject().apply {
        put("id", sensorId)
        put("t", tMs)
        put("rms", r(rms))
        put("floor", r(floor))
        put("h", r(human))
        put("m", r(machine))
        put("top", topClass)
        put("taps", taps)
        put("tap", r(tapScore))
        put("imp", r(impact))
        put("rs", r(rhythmScore))
        put("lab", label)
        put("acc", accel)
        put("accm", r(accelMax))
        if (moving) put("mov", true)
        put("bat", battery)
        rhythm?.let { put("rh", it) }
        chirpTs?.let { put("chirp", it) }
    }.toString()

    companion object {
        private fun r(x: Float): Double = Math.round(x * 10000.0) / 10000.0

        fun fromJson(o: JSONObject): SensorEvent? = try {
            SensorEvent(
                sensorId = o.getString("id"),
                tMs = o.getLong("t"),
                rms = o.getDouble("rms").toFloat(),
                floor = o.optDouble("floor", 0.0).toFloat(),
                human = o.getDouble("h").toFloat(),
                machine = o.getDouble("m").toFloat(),
                topClass = o.getString("top"),
                taps = o.optInt("taps", 0),
                tapScore = o.optDouble("tap", 0.0).toFloat(),
                impact = o.optDouble("imp", 0.0).toFloat(),
                rhythmScore = o.optDouble("rs", 0.0).toFloat(),
                label = o.optString("lab"),
                accel = o.optInt("acc", 0),
                accelMax = o.optDouble("accm", 0.0).toFloat(),
                moving = o.optBoolean("mov", false),
                battery = o.optInt("bat", -1),
                rhythm = o.optString("rh").ifEmpty { null },
                chirpTs = if (o.has("chirp")) o.getLong("chirp") else null
            )
        } catch (e: Exception) {
            HLog.d("bad SensorEvent json: $e")
            null
        }
    }
}

/** Commander → sensors. */
data class Command(val type: String, val seconds: Int = 20, val letter: String? = null) {
    fun toJson(): String = JSONObject().apply {
        put("cmd", type)
        put("sec", seconds)
        letter?.let { put("letter", it) }
    }.toString()

    companion object {
        const val ASSIGN = "ASSIGN"
        const val HUSH = "HUSH"
        const val STOP = "STOP"
        const val CHIRP = "CHIRP"

        fun fromJson(o: JSONObject): Command? = try {
            Command(o.getString("cmd"), o.optInt("sec", 20), o.optString("letter").ifEmpty { null })
        } catch (e: Exception) {
            HLog.d("bad Command json: $e")
            null
        }
    }
}

object Messages {
    /** Returns a [SensorEvent], a [Command], or null. */
    fun parse(text: String): Any? = try {
        val o = JSONObject(text)
        if (o.has("cmd")) Command.fromJson(o) else SensorEvent.fromJson(o)
    } catch (e: Exception) {
        HLog.d("unparseable message: $e / $text")
        null
    }
}
