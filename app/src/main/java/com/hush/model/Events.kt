package com.hush.model

import com.hush.HLog
import org.json.JSONArray
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
    val chirpTs: Long? = null,
    val micDelay: Float? = null, // samples, mic 1 minus mic 0, over this whole second (voice direction of arrival)
    val micQ: Float? = null,     // 0..1 how well the two mics agreed on that delay
    val bearing: Float? = null,  // compass degrees: where this phone's own two-mic arrow points (only while it shows)
    val bearingQ: Float? = null, // 0..1 confidence of that bearing (about 0.35 while left/right is still open)
    val bearingTwin: Float? = null, // the mirror candidate, present until the phone was turned to resolve it
    val heading: Float? = null   // compass heading of the phone's top edge (after SYNC COMPASS), for the commander's sync
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
        micDelay?.let { put("dl", Math.round(it * 100.0) / 100.0) }
        micQ?.let { put("dq", r(it)) }
        bearing?.let { put("br", Math.round(it * 10.0) / 10.0) }
        bearingQ?.let { put("bq", r(it)) }
        bearingTwin?.let { put("br2", Math.round(it * 10.0) / 10.0) }
        heading?.let { put("hd", Math.round(it * 10.0) / 10.0) }
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
                chirpTs = if (o.has("chirp")) o.getLong("chirp") else null,
                micDelay = if (o.has("dl")) o.getDouble("dl").toFloat() else null,
                micQ = if (o.has("dq")) o.getDouble("dq").toFloat() else null,
                bearing = if (o.has("br")) o.getDouble("br").toFloat() else null,
                bearingQ = if (o.has("bq")) o.getDouble("bq").toFloat() else null,
                bearingTwin = if (o.has("br2")) o.getDouble("br2").toFloat() else null,
                heading = if (o.has("hd")) o.getDouble("hd").toFloat() else null
            )
        } catch (e: Exception) {
            HLog.d("bad SensorEvent json: $e")
            null
        }
    }
}

/** Commander → sensors. [to] = the sensor's advertised name for ASSIGN; null = everyone. */
data class Command(val type: String, val seconds: Int = 20, val letter: String? = null, val to: String? = null, val ble: String? = null,
                   val heading: Float? = null /* SYNC: the heading every phone should read now */) {
    fun toJson(): String = JSONObject().apply {
        put("cmd", type)
        put("sec", seconds)
        letter?.let { put("letter", it) }
        to?.let { put("to", it) }
        ble?.let { put("ble", it) }
        heading?.let { put("hd", Math.round(it * 10.0) / 10.0) }
    }.toString()

    companion object {
        const val ASSIGN = "ASSIGN"
        const val HUSH = "HUSH"
        const val STOP = "STOP"
        const val CHIRP = "CHIRP"
        const val SYNC = "SYNC"

        fun fromJson(o: JSONObject): Command? = try {
            Command(o.getString("cmd"), o.optInt("sec", 20), o.optString("letter").ifEmpty { null }, o.optString("to").ifEmpty { null }, o.optString("ble").ifEmpty { null },
                if (o.has("hd")) o.getDouble("hd").toFloat() else null)
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

/** Sensor → commander (relayed up the tree): "I am here, [hops] from you", or "I left". [ble] = my Bluetooth address for radio ranging. */
data class Join(val name: String, val hops: Int, val leaving: Boolean = false, val ble: String? = null) {
    fun toJson(): String = JSONObject().put("rep", if (leaving) "leave" else "join").put("name", name).put("hops", hops).apply { ble?.let { put("ble", it) } }.toString()

    companion object {
        fun fromJson(o: JSONObject): Join? = try {
            Join(o.getString("name"), o.optInt("hops", 1), o.optString("rep") == "leave", o.optString("ble").ifEmpty { null })
        } catch (e: Exception) {
            HLog.d("bad Join json: $e"); null
        }
    }
}

/**
 * One knock as one phone heard it, to the sample, on that phone's own audio clock. This is what the
 * commander's source locator works from: WHEN each phone first heard the knock (time difference of
 * arrival), HOW LOUD it was there, and from WHICH SIDE of the phone (its two mics).
 */
data class Onset(
    val sample: Long,          // absolute sample index of the first arrival, sender's audio clock (48 kHz)
    val peak: Float,           // 0..1 peak amplitude in the first 10 ms
    val ratio: Float,          // peak over the phone's background level: how sharply the knock stood out
    val micDelay: Float?,      // samples, mic 1 minus mic 0; null if the two mics did not agree
    val micQ: Float?,          // 0..1 correlation quality of micDelay
    val felt: Boolean          // an accelerometer jolt within 100 ms: the knock also reached the phone through the floor
) {
    fun toJson(): JSONObject = JSONObject().put("s", sample).put("pk", Math.round(peak * 10000.0) / 10000.0).put("ra", Math.round(ratio * 10.0) / 10.0).apply {
        micDelay?.let { put("dl", Math.round(it * 100.0) / 100.0) }
        micQ?.let { put("q", Math.round(it * 100.0) / 100.0) }
        if (felt) put("felt", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = Onset(
            o.getLong("s"), o.getDouble("pk").toFloat(), o.optDouble("ra", 0.0).toFloat(),
            if (o.has("dl")) o.getDouble("dl").toFloat() else null, if (o.has("q")) o.getDouble("q").toFloat() else null,
            o.optBoolean("felt", false)
        )
    }
}

/** Sensor → commander, at most once a second and only when there were knocks: the onsets of that second. */
data class OnsetReport(val letter: String, val heading: Float, val moving: Boolean, val onsets: List<Onset>) {
    fun toJson(): String = JSONObject().put("rep", "onsets").put("id", letter).put("hd", Math.round(heading * 10.0) / 10.0)
        .apply { if (moving) put("mov", true) }
        .put("on", org.json.JSONArray().apply { onsets.forEach { put(it.toJson()) } }).toString()

    companion object {
        fun fromJson(o: JSONObject): OnsetReport? = try {
            val arr = o.getJSONArray("on")
            OnsetReport(o.getString("id"), o.optDouble("hd", 0.0).toFloat(), o.optBoolean("mov", false),
                (0 until arr.length()).map { Onset.fromJson(arr.getJSONObject(it)) })
        } catch (e: Exception) {
            HLog.d("bad OnsetReport json: $e"); null
        }
    }
}

/**
 * Commander → every sensor (down the tree like a command): where the located source is, so each sensor can draw its own
 * arrow. Positions are map fractions (x right, y down, the commander's map square); rotation is degrees to add to a
 * map bearing to get a compass bearing (null until north is set); scale is metres per map unit (null if placed by hand).
 */
data class Fix(
    val seq: Int, val x: Float, val y: Float, val radius: Float, val knocks: Int, val edge: Boolean,
    val rotation: Float?, val mirror: Boolean, val scale: Float?, val north: String,
    val dots: Map<String, Pair<Float, Float>>,
    val near: String? = null,    // set when the target is "the phone that hears it loudest" (no located source)
    val hasPoint: Boolean = true,      // false when x/y mean nothing (only the fused bearing below is carried)
    val sharedBearing: Float? = null,  // compass degrees the hearing phones agree on (Engine.fuseBearings)
    val sharedTwin: Float? = null,     // its left/right twin while still ambiguous
    val sharedQ: Float? = null,
    val sharedBy: String? = null       // "A,B": the phones that hear it
) {
    fun toJson(): String = JSONObject().put("rep", "fix").put("seq", seq)
        .put("x", r3(x)).put("y", r3(y)).put("r", r3(radius)).put("k", knocks).put("edge", edge)
        .apply { rotation?.let { put("rot", Math.round(it * 10.0) / 10.0) }; scale?.let { put("sc", r3(it)) } }
        .put("mir", mirror).put("north", north).apply { near?.let { put("near", it) } }
        .apply {
            if (!hasPoint) put("pt", false)
            sharedBearing?.let { put("sb", Math.round(it * 10.0) / 10.0) }
            sharedTwin?.let { put("sb2", Math.round(it * 10.0) / 10.0) }
            sharedQ?.let { put("sbq", Math.round(it * 100.0) / 100.0) }
            sharedBy?.let { put("sby", it) }
        }
        .put("dots", JSONObject().apply { for ((l, p) in dots) put(l, JSONArray().put(r3(p.first)).put(r3(p.second))) })
        .toString()

    companion object {
        private fun r3(v: Float) = Math.round(v * 1000.0) / 1000.0
        fun fromJson(o: JSONObject): Fix? = try {
            val d = o.getJSONObject("dots")
            val dots = LinkedHashMap<String, Pair<Float, Float>>()
            for (k in d.keys()) { val a = d.getJSONArray(k); dots[k] = a.getDouble(0).toFloat() to a.getDouble(1).toFloat() }
            Fix(o.getInt("seq"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optDouble("r", 0.0).toFloat(),
                o.optInt("k", 0), o.optBoolean("edge", false),
                if (o.has("rot")) o.getDouble("rot").toFloat() else null, o.optBoolean("mir", false),
                if (o.has("sc")) o.getDouble("sc").toFloat() else null, o.optString("north", ""), dots,
                o.optString("near").ifEmpty { null },
                o.optBoolean("pt", true),
                if (o.has("sb")) o.getDouble("sb").toFloat() else null,
                if (o.has("sb2")) o.getDouble("sb2").toFloat() else null,
                if (o.has("sbq")) o.getDouble("sbq").toFloat() else null,
                o.optString("sby").ifEmpty { null })
        } catch (e: Exception) {
            HLog.d("bad Fix json: $e"); null
        }
    }
}

/** Sensor → commander: a button pressed on a sensor's screen, run by the commander (HUSH, STOP, SWEEP, MODE, SYNC). */
data class Request(val type: String, val from: String, val arg: String? = null) {
    fun toJson(): String = JSONObject().put("req", type).put("from", from).apply { arg?.let { put("arg", it) } }.toString()

    companion object {
        const val HUSH = "HUSH"; const val HUSH_SOLO = "HUSH_SOLO"; const val STOP = "STOP"; const val SWEEP = "SWEEP"
        const val MODE = "MODE"; const val SYNC = "SYNC"
        fun fromJson(o: JSONObject): Request? = try {
            Request(o.getString("req"), o.optString("from"), o.optString("arg").ifEmpty { null })
        } catch (e: Exception) { HLog.d("bad Request json: $e"); null }
    }
}

/**
 * Commander → every phone, once a second: what the commander's screen shows, so every phone can show the same
 * (team, 27 Sep: "they all need to have the same UI"). The brief, the ranking, each phone's latest event, the
 * names behind the letters, the listen mode and the commander's status lines. Small JSON only, never audio.
 */
data class Board(val brief: String, val mode: String, val ranks: List<Rank>, val names: Map<String, String>,
                 val events: List<SensorEvent>, val status: String, val discovered: String) {
    data class Rank(val letter: String, val score: Float, val evidence: Float, val source: Int)

    fun toJson(): String = JSONObject().put("rep", "board").put("brief", brief).put("mode", mode).put("st", status).put("disc", discovered)
        .put("rk", JSONArray().apply { ranks.forEach { put(JSONObject().put("l", it.letter).put("s", it.score.toDouble()).put("e", Math.round(it.evidence * 100.0) / 100.0).put("src", it.source)) } })
        .put("nm", JSONObject().apply { names.forEach { (l, n) -> put(l, n) } })
        .put("ev", JSONArray().apply { events.forEach { put(JSONObject(it.toJson())) } })
        .toString()

    companion object {
        fun fromJson(o: JSONObject): Board? = try {
            val rk = o.getJSONArray("rk"); val nm = o.getJSONObject("nm"); val ev = o.getJSONArray("ev")
            Board(o.optString("brief"), o.optString("mode", "TAPPING"),
                (0 until rk.length()).map { val r = rk.getJSONObject(it); Rank(r.getString("l"), r.getDouble("s").toFloat(), r.getDouble("e").toFloat(), r.optInt("src", 0)) },
                LinkedHashMap<String, String>().apply { for (k in nm.keys()) put(k, nm.getString(k)) },
                (0 until ev.length()).mapNotNull { SensorEvent.fromJson(ev.getJSONObject(it)) },
                o.optString("st"), o.optString("disc"))
        } catch (e: Exception) { HLog.d("bad Board json: $e"); null }
    }
}

object Messages {
    /** Returns a [SensorEvent], a [Command], a [ChirpReport], a [Placement], a [Join], an [OnsetReport], a [Fix], or null. */
    fun parse(text: String): Any? = try {
        val o = JSONObject(text)
        when {
            o.has("cmd") -> Command.fromJson(o)
            o.has("req") -> Request.fromJson(o)
            o.optString("rep") == "board" -> Board.fromJson(o)
            o.optString("rep") == "chirp" -> ChirpReport.fromJson(o)
            o.optString("rep") == "place" -> Placement.fromJson(o)
            o.optString("rep") == "onsets" -> OnsetReport.fromJson(o)
            o.optString("rep") == "fix" -> Fix.fromJson(o)
            o.optString("rep") == "join" || o.optString("rep") == "leave" -> Join.fromJson(o)
            else -> SensorEvent.fromJson(o)
        }
    } catch (e: Exception) {
        HLog.d("unparseable message: $e / $text")
        null
    }
}
