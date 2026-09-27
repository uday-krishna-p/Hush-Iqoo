package com.hush

import kotlin.math.sqrt

/**
 * TEACH a sound (docs/PLAN-personas-bc.md, step 5): your own doorbell, your microwave's beep, the water-heater alarm.
 *
 * Doorbells and appliance beeps vary far more than YAMNet's classes do, but each home's are the same every time. The
 * fingerprint of a taught sound is the average of YAMNet's class scores over the loud seconds while it was played
 * (a few hundred numbers, no audio). Live, a second whose scores are close enough (cosine similarity ≥ [MIN_SIMILARITY])
 * is that sound. Coarse (the model has no embedding output) but honest: if two taught sounds collide, the upgrade is a
 * spectrogram template. Pure logic; the file lives in `files/sounds.txt`, one line per sound, written by [Alerting].
 */
class SoundLibrary {

    data class Taught(val name: String, val colour: Int, val vector: Map<String, Float>, val seconds: Int)

    /** A teaching session in progress: collects loud seconds until it has enough or runs out of time. */
    class Session(val name: String, val startedMs: Long) {
        val sums = HashMap<String, Float>()
        var seconds = 0
        val status: String get() = "Make the sound now… %d of %d heard".format(seconds, NEED_SECONDS)
    }

    companion object {
        const val MIN_SIMILARITY = 0.85f
        const val NEED_SECONDS = 3
        const val SESSION_MS = 30_000L
        /** A second counts for teaching when it is this loud against the room and the model raised something. */
        const val TEACH_LOUD = 2f
        const val TEACH_MIN_TOP = 0.10f
        val PALETTE = intArrayOf(0xFFC2185B.toInt(), 0xFF00897B.toInt(), 0xFF5D4037.toInt(), 0xFF7CB342.toInt(), 0xFF3949AB.toInt(), 0xFFF4511E.toInt())

        fun cosine(a: Map<String, Float>, b: Map<String, Float>): Float {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for ((k, v) in a) { na += v * v; b[k]?.let { dot += v * it } }
            for ((_, v) in b) nb += v * v
            if (na <= 0.0 || nb <= 0.0) return 0f
            return (dot / sqrt(na * nb)).toFloat()
        }
    }

    val sounds = ArrayList<Taught>()
    var session: Session? = null
        private set

    fun startTeaching(name: String, nowMs: Long): String {
        val n = name.trim().ifEmpty { "Sound ${sounds.size + 1}" }
        session = Session(n, nowMs)
        HLog.d("TEACH '$n': listening for ${NEED_SECONDS} loud seconds")
        return session!!.status
    }

    fun cancelTeaching() { session?.let { HLog.d("TEACH '${it.name}' cancelled") }; session = null }

    /**
     * One second while teaching. Returns the new [Taught] when the session completed, else null; [status] tells the
     * screen what is happening. Times out after [SESSION_MS].
     */
    fun feed(nowMs: Long, scores: Map<String, Float>, loudFactor: Float): Taught? {
        val s = session ?: return null
        if (nowMs - s.startedMs > SESSION_MS) {
            HLog.d("TEACH '${s.name}': timed out with ${s.seconds} loud seconds, nothing saved")
            session = null; timedOut = true
            return null
        }
        val top = scores.values.maxOrNull() ?: 0f
        if (loudFactor < TEACH_LOUD || top < TEACH_MIN_TOP) return null
        for ((k, v) in scores) s.sums[k] = (s.sums[k] ?: 0f) + v
        s.seconds++
        HLog.d("TEACH '${s.name}': second ${s.seconds} loud=x%.1f top=%s".format(loudFactor, scores.maxByOrNull { it.value }?.let { "${it.key} %.2f".format(it.value) }))
        if (s.seconds < NEED_SECONDS) return null
        val vector = s.sums.mapValues { it.value / s.seconds }.filterValues { it >= 0.01f }
        val colour = PALETTE[sounds.size % PALETTE.size]
        val t = Taught(s.name, colour, vector, s.seconds)
        sounds.removeAll { it.name.equals(t.name, ignoreCase = true) }
        sounds.add(t)
        session = null
        HLog.d("TEACH '${t.name}' saved: ${vector.size} classes, strongest " + vector.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} %.2f".format(it.value) })
        return t
    }

    /** Set when the last session ran out of time; cleared on read. */
    var timedOut = false
        get() { val v = field; field = false; return v }

    fun forget(name: String): Boolean { val r = sounds.removeAll { it.name == name }; if (r) HLog.d("TEACH '$name' forgotten"); return r }

    /** The taught sound this second resembles most, with its similarity, if any passes [MIN_SIMILARITY]. */
    fun match(scores: Map<String, Float>): Pair<Taught, Float>? {
        var best: Taught? = null; var bestSim = 0f
        for (t in sounds) {
            val sim = cosine(t.vector, scores)
            if (sim > bestSim) { bestSim = sim; best = t }
        }
        return if (best != null && bestSim >= MIN_SIMILARITY) best to bestSim else null
    }

    // ---- one line per sound: name \t colour \t seconds \t class=score;class=score… ----

    fun toLines(): List<String> = sounds.map { t ->
        listOf(t.name.replace('\t', ' ').replace('\n', ' '), t.colour.toString(), t.seconds.toString(),
            t.vector.entries.joinToString(";") { "${it.key.replace(';', ',').replace('=', ' ')}=${"%.4f".format(it.value)}" }).joinToString("\t")
    }

    fun fromLines(lines: List<String>) {
        sounds.clear()
        for (l in lines) {
            if (l.isBlank()) continue
            try {
                val p = l.split('\t')
                if (p.size < 4) continue
                val vec = HashMap<String, Float>()
                for (e in p[3].split(';')) { val i = e.lastIndexOf('='); if (i > 0) vec[e.substring(0, i)] = e.substring(i + 1).toFloat() }
                sounds.add(Taught(p[0], p[1].toInt(), vec, p[2].toInt()))
            } catch (e: Exception) { HLog.d("TEACH: bad line ignored: $e") }
        }
    }
}
