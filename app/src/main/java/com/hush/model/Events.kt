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
    val tempoMs: Int = 0,        // tapping signature: mean gap (or pattern repeat) in ms
    val lat: Double? = null,     // GPS, only when a fix < 60 s old exists
    val lon: Double? = null,
    val gpsAcc: Float? = null,   // metres
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
        if (tempoMs > 0) put("tempo", tempoMs)
        if (lat != null && lon != null) { put("lat", lat); put("lon", lon); put("gacc", Math.round((gpsAcc ?: 0f) * 10.0) / 10.0) }
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
                tempoMs = o.optInt("tempo", 0),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lon = if (o.has("lon")) o.getDouble("lon") else null,
                gpsAcc = if (o.has("gacc")) o.getDouble("gacc").toFloat() else null,
                rhythm = o.optString("rh").ifEmpty { null },
                chirpTs = if (o.has("chirp")) o.getLong("chirp") else null
            )
        } catch (e: Exception) {
            HLog.d("bad SensorEvent json: $e")
            null
        }
    }
}

/** Commander → sensors. [to] = the sensor's advertised name for ASSIGN; null = everyone. */
data class Command(val type: String, val seconds: Int = 20, val letter: String? = null, val to: String? = null) {
    fun toJson(): String = JSONObject().apply {
        put("cmd", type)
        put("sec", seconds)
        letter?.let { put("letter", it) }
        to?.let { put("to", it) }
    }.toString()

    companion object {
        const val ASSIGN = "ASSIGN"
        const val HUSH = "HUSH"
        const val STOP = "STOP"
        const val CHIRP = "CHIRP"

        fun fromJson(o: JSONObject): Command? = try {
            Command(o.getString("cmd"), o.optInt("sec", 20), o.optString("letter").ifEmpty { null }, o.optString("to").ifEmpty { null })
        } catch (e: Exception) {
            HLog.d("bad Command json: $e")
            null
        }
    }
}

/** Sensor → commander: "I heard the chirp from [from] starting at my sample [sample]". */
data class ChirpReport(
    val hearer: String, val from: String, val sample: Long, val ratio: Float,
    val micDelay: Float? = null,   // samples, mic 1 minus mic 0, for direction of arrival
    val heading: Float? = null,    // hearer's compass heading at that moment
    val level: Float? = null       // RMS of the received chirp, for mic gain calibration
) {
    fun toJson(): String = JSONObject().put("rep", "chirp").put("hearer", hearer).put("from", from).put("sample", sample).put("ratio", ratio.toDouble())
        .apply { micDelay?.let { put("dl", it.toDouble()) }; heading?.let { put("hd", it.toDouble()) }; level?.let { put("lv", it.toDouble()) } }.toString()

    companion object {
        fun fromJson(o: JSONObject): ChirpReport? = try {
            ChirpReport(o.getString("hearer"), o.getString("from"), o.getLong("sample"), o.optDouble("ratio", 0.0).toFloat(),
                if (o.has("dl")) o.getDouble("dl").toFloat() else null, if (o.has("hd")) o.getDouble("hd").toFloat() else null,
                if (o.has("lv")) o.getDouble("lv").toFloat() else null)
        } catch (e: Exception) {
            HLog.d("bad ChirpReport json: $e"); null
        }
    }
}

/** Sensor → commander: "since I started I have been carried [east],[north] metres ([steps] steps)". */
data class Placement(val letter: String, val east: Float, val north: Float, val steps: Int) {
    fun toJson(): String = JSONObject().put("rep", "place").put("id", letter).put("e", east.toDouble()).put("n", north.toDouble()).put("steps", steps).toString()

    companion object {
        fun fromJson(o: JSONObject): Placement? = try {
            Placement(o.getString("id"), o.getDouble("e").toFloat(), o.getDouble("n").toFloat(), o.getInt("steps"))
        } catch (e: Exception) {
            HLog.d("bad Placement json: $e"); null
        }
    }
}

/** Sensor → commander (relayed up the tree): "I am here, [hops] from you", or "I left". */
data class Join(val name: String, val hops: Int, val leaving: Boolean = false) {
    fun toJson(): String = JSONObject().put("rep", if (leaving) "leave" else "join").put("name", name).put("hops", hops).toString()

    companion object {
        fun fromJson(o: JSONObject): Join? = try {
            Join(o.getString("name"), o.optInt("hops", 1), o.optString("rep") == "leave")
        } catch (e: Exception) {
            HLog.d("bad Join json: $e"); null
        }
    }
}

object Messages {
    /** Returns a [SensorEvent], a [Command], a [ChirpReport], a [Placement], a [Join], or null. */
    fun parse(text: String): Any? = try {
        val o = JSONObject(text)
        when {
            o.has("cmd") -> Command.fromJson(o)
            o.optString("rep") == "chirp" -> ChirpReport.fromJson(o)
            o.optString("rep") == "place" -> Placement.fromJson(o)
            o.optString("rep") == "join" || o.optString("rep") == "leave" -> Join.fromJson(o)
            else -> SensorEvent.fromJson(o)
        }
    } catch (e: Exception) {
        HLog.d("unparseable message: $e / $text")
        null
    }
}
