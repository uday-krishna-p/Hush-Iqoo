package com.hush.audio

/**
 * Turns the last few seconds of tap onsets into "is somebody tapping deliberately?".
 * Deliberate tapping is regular (1 per second) or patterned (3-2, 3-2). Random room impacts are neither.
 */
class RhythmTracker {

    data class Result(
        val count: Int,          // onsets in the last HISTORY_MS
        val score: Float,        // 0..1
        val rhythm: String?,     // "3-2", "steady", or null
        val tempoMs: Int = 0     // mean gap between onsets, ms (the source's signature; 0 if unknown)
    )

    companion object {
        const val HISTORY_MS = 8000L
        const val GROUP_GAP_MS = 600L    // onsets closer than this belong to the same group ("3 quick knocks")
        const val REGULAR_CV = 0.35f     // coefficient of variation below this = regular
        const val MIN_ONSETS = 3         // USAR asks victims to "tap three times"; three regular hits count (was 4, lowered 26 Sep 16:20)
        const val MAX_ONSETS = 40        // more than 5 hits per second for 8 s is rattling, not signalling (was 20: real 2.5/s knocking tripped it)
        const val STEADY_CV = 0.45f      // regularity of raw knock spacing at any tempo; random noise has CV ≈ 1
        const val MERGE_MS = 100L        // an accelerometer spike this close to an audio onset is the same knock
    }

    private val onsets = ArrayDeque<Long>()

    /** Adds onsets from one source, merging any that duplicate an onset already known (audio + accelerometer). */
    fun add(onsetAbsMs: List<Long>) {
        for (t in onsetAbsMs) {
            if (onsets.none { kotlin.math.abs(it - t) <= MERGE_MS }) onsets.addLast(t)
        }
        val sorted = onsets.sorted()
        onsets.clear()
        onsets.addAll(sorted)
    }

    fun evaluate(nowMs: Long): Result {
        while (onsets.isNotEmpty() && onsets.first() < nowMs - HISTORY_MS) onsets.removeFirst()
        val times = onsets.toList()
        return score(times, nowMs)
    }

    fun reset() = onsets.clear()

    fun score(times: List<Long>, nowMs: Long): Result {
        val n = times.size
        if (n < 2) return Result(n, 0f, null)
        if (n > MAX_ONSETS) return Result(n, 0f, null)

        // 1. Steady knocking at ANY tempo: consecutive gaps are regular. Measured 26 Sep: people knock at
        //    1/s or 2–3/s; both are intent. At 600 ms grouping the fast case merged into one endless group.
        val allGaps = times.zipWithNext { a, b -> (b - a).toFloat() }
        val tempo = if (allGaps.isEmpty()) 0 else allGaps.average().toInt()
        if (n >= MIN_ONSETS) {
            val mean = allGaps.average().toFloat()
            val sd = kotlin.math.sqrt(allGaps.map { (it - mean) * (it - mean) }.average().toFloat())
            if (mean in 150f..2500f && sd / mean < STEADY_CV) return Result(n, 0.9f, "steady", tempo)
        }

        // 2. Patterned knocking (3-2): group onsets into bursts.
        val groups = ArrayList<MutableList<Long>>()
        for (t in times) {
            if (groups.isEmpty() || t - groups.last().last() > GROUP_GAP_MS) groups.add(mutableListOf(t)) else groups.last().add(t)
        }
        // The last group may still be growing; keep it out of the pattern match.
        val lastGrowing = nowMs - groups.last().last() < GROUP_GAP_MS
        val complete = if (lastGrowing && groups.size > 1) groups.dropLast(1) else groups
        val sizes = complete.map { it.size }

        // Pattern: sizes repeat with period 1..3, at least two repetitions.
        var pattern: String? = null
        for (p in 1..3) {
            if (sizes.size < 2 * p) continue
            var ok = true
            for (i in p until sizes.size) if (sizes[i] != sizes[i - p]) { ok = false; break }
            if (ok) {
                var unit = sizes.take(p)
                // Rotate so the pattern always starts with its largest group: "2-3" and "3-2" are the same tapping.
                val maxAt = unit.indexOf(unit.max())
                unit = unit.drop(maxAt) + unit.take(maxAt)
                pattern = if (p == 1 && unit[0] == 1) "steady" else unit.joinToString("-")
                break
            }
        }

        // Regularity of group starts.
        val starts = complete.map { it.first() }
        var regular = false
        if (starts.size >= 3) {
            val gaps = starts.zipWithNext { a, b -> (b - a).toFloat() }
            val mean = gaps.average().toFloat()
            val sd = kotlin.math.sqrt(gaps.map { (it - mean) * (it - mean) }.average().toFloat())
            regular = mean > 0 && sd / mean < REGULAR_CV
        }

        val score = when {
            n < MIN_ONSETS -> 0.2f
            pattern != null && pattern != "steady" -> 1f
            regular -> 0.9f
            else -> 0.5f
        }
        val rhythm = when {
            pattern != null && pattern != "steady" -> pattern
            regular -> "steady"
            else -> null
        }
        // For patterned tapping the signature is the group repeat interval, not the gap between hits.
        val groupTempo = if (starts.size >= 2) starts.zipWithNext { a, b -> (b - a).toFloat() }.average().toInt() else tempo
        return Result(n, score, rhythm, if (rhythm != null && rhythm != "steady") groupTempo else tempo)
    }
}
