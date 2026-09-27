package com.hush

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.os.BatteryManager
import com.hush.audio.AccelChannel
import com.hush.audio.AudioCapture
import com.hush.audio.Classifier
import com.hush.audio.Ping
import com.hush.audio.RhythmTracker
import com.hush.audio.TapDetector
import com.hush.model.Command
import com.hush.model.Messages
import com.hush.model.SensorEvent
import com.hush.net.NearbyLink
import java.io.File
import kotlin.math.ceil

/**
 * The one place that owns the microphone, the classifier, the tap detector, the Nearby link and the
 * Hush window. Lives as long as SensorService runs. Screens attach as [Listener] to draw the state.
 */
object Engine : AudioCapture.Listener, NearbyLink.Listener {

    const val ROLE_COMMANDER = "COMMANDER"
    const val ROLE_SENSOR = "SENSOR"
    /** Persona C: one phone at home, listening for doorbells, knocks, alarms (docs/PLAN-personas-bc.md). No radios. */
    const val ROLE_ALERT = "ALERT"
    /** Persona B: one phone at home, whistle counter and walk-to-triangulate. No radios. */
    const val ROLE_HOME = "HOME"
    /** The "one phone at home" roles: no mesh, no Bluetooth, no GPS, no chirps, classifier only on loud seconds. */
    fun isHousehold(r: String?) = r == ROLE_ALERT || r == ROLE_HOME

    /** Persona C's category detector; its `enabled` set and `sensitivity` are the ALERT screen's settings. */
    val soundAlerts = com.hush.audio.SoundAlerts()
    /** Sounds the person taught the phone (TEACH on the ALERT screen); matched by [soundAlerts]. */
    val library = SoundLibrary()
    /** ALERT screen: start teaching a sound under this name; the next 3 loud seconds are its fingerprint. */
    fun teach(name: String): String {
        val s = library.startTeaching(name, SystemClock.elapsedRealtime())
        listener?.onTeaching(s, false)
        return s
    }
    fun cancelTeach() { library.cancelTeaching(); listener?.onTeaching("", true) }
    fun forget(name: String) { if (library.forget(name)) { appContext?.let { Alerting.saveLibrary(library) } }; listener?.onTeaching("", true) }

    /** Persona B's whistle counter (HOME role); `target` is the screen's setting. */
    val whistles = com.hush.audio.WhistleCounter()
    /** HOME role also hears kitchen timers and alarms, with its own switch set (TIMER + ALARM only). */
    private val homeAlerts = com.hush.audio.SoundAlerts().also {
        it.enabled.clear(); it.enabled.add(com.hush.audio.SoundAlerts.Category.TIMER); it.enabled.add(com.hush.audio.SoundAlerts.Category.ALARM)
    }
    /** The DONE beeps: the cook is in another room, so these are loud (fraction of the alarm volume). */
    private const val DONE_BEEP_FRACTION = 0.6f

    fun resetWhistles() { whistles.reset(); HLog.d("WHISTLE counter reset") }

    /** Persona B's FIND: walk-to-triangulate with this one phone (HOME role). Arriving at a new spot clears the own-arrow histogram. */
    val walk = WalkLocator { com.hush.audio.KnockBearing.reset() }
    @Volatile var walkState: WalkLocator.State = walk.state
        private set
    /** HOME role: this phone's own arrow right now, ungated (the person asked to find a noise, so every knock counts). */
    fun homeArrow(): com.hush.audio.KnockBearing.Estimate? = com.hush.audio.KnockBearing.estimate(SystemClock.elapsedRealtime(), headingDeg)
    fun walkMark(): String {
        val dr = deadReckoning
        val r = walk.markNow(SystemClock.elapsedRealtime(), dr?.east?.toDouble() ?: 0.0, dr?.north?.toDouble() ?: 0.0, homeArrow())
        walkState = walk.state
        HLog.d("WALK MARK button: $r")
        return r
    }
    fun walkReset() { walk.reset(); walkState = walk.state }
    /** Where the walker stands, metres east/north of where the role started (step counting along the compass). */
    fun walkerPosition(): Pair<Double, Double> = deadReckoning.let { (it?.east?.toDouble() ?: 0.0) to (it?.north?.toDouble() ?: 0.0) }
    /** FIND: which kind of noise feeds the arrow. TICKS = sharp onsets (drip, tick, knock, beep); HUM = a continuous sound (fridge, fan, pump); ANY = both. */
    enum class FindMode { TICKS, HUM, ANY }
    @Volatile var findMode: FindMode = FindMode.ANY
        set(value) { field = value; HLog.d("FIND mode: $value"); com.hush.audio.KnockBearing.reset() }
    /** Hum bearings: the whole second's two-mic delay in this band (a 100 Hz hum has a 3.4 m wavelength, so no cycle ambiguity, only a broad peak). */
    private const val HUM_LOW_HZ = 60f
    private const val HUM_HIGH_HZ = 1500f
    private const val HUM_MIN_Q = 0.4f
    private var humSeconds = 0L
    /** FIND listens for slow noises (a smoke-alarm chirp every 30–60 s) and holds a spot's knocks longer than the rescue arrow does. */
    private fun setWalkTuning() { com.hush.audio.KnockBearing.TAU_MS = 60_000.0; com.hush.audio.KnockBearing.ACTIVE_MS = 90_000L; com.hush.audio.KnockBearing.MIN_KNOCKS = 2 }
    /** How many seconds the classifier was skipped as too quiet (household roles), for the battery note in the log. */
    private var classifierSkipped = 0L
    private var classifierRun = 0L

    /** Everything one second of this phone's own hearing produced. */
    data class Window(
        val rms: Float,
        val floor: Float,
        val gain: Float,
        val tap: TapDetector.Result,
        val rhythm: RhythmTracker.Result,
        val accel: AccelChannel.Result,
        val structureConfirmed: Boolean,   // an audio knock and an accelerometer jolt agreed within 100 ms recently
        val cls: Classifier.Result?,
        val event: SensorEvent
    )

    const val LABEL_TAPPING = "HUMAN TAPPING"
    const val LABEL_VOICE = "HUMAN VOICE"
    const val LABEL_MACHINE = "MACHINERY"
    const val LABEL_MOVEMENT = "MOVEMENT"
    const val LABEL_QUIET = "quiet"

    /**
     * The headline for one second, most trusted evidence first. Audio rhythm alone is enough for HUMAN TAPPING:
     * in a collapse nothing guarantees a solid structure to carry vibration, so the accelerometer only ever adds.
     * Movement is reported as a warning flag beside the label, never instead of it.
     * A single impact never makes a headline: rescuers ask victims to tap repeatedly for exactly this reason.
     */
    fun fuseLabel(rhythm: RhythmTracker.Result, cls: Classifier.Result?): String {
        val voice = cls?.human ?: 0f
        val machine = cls?.machine ?: 0f
        return when {
            rhythm.score >= 0.9f -> LABEL_TAPPING
            voice >= 0.30f -> LABEL_VOICE
            machine >= 0.35f -> LABEL_MACHINE
            else -> LABEL_QUIET
        }
    }

    data class Peer(val endpointId: String, val name: String, val letter: String)

    /** What the commander is listening for. Decides which evidence feeds the rank score. */
    enum class Mode { TAPPING, VOICE, ANY }

    /** One sensor's result for one Hush window. */
    data class Rank(
        val letter: String,
        val score: Float,        // mean of the 5 best windows of (rms above floor) × evidence
        val evidence: Float,     // best evidence seen in the window, 0..1
        val rhythm: String?,     // most frequent rhythm name, if any
        val windows: Int,        // seconds of data received
        val moving: Boolean,     // flagged as handled/shaken during the window
        val tempoMs: Int = 0,    // tapping signature
        var source: Int = 0      // 1, 2, … which distinct source this sensor is hearing (0 = none)
    )

    /**
     * Two people tapping do not tap at the same rate. Sensors with tapping evidence are grouped by signature:
     * same pattern name, or tempo within 25 %. Each group is one source, numbered strongest first.
     */
    private fun assignSources(ranks: List<Rank>): Int {
        val tapping = ranks.filter { it.evidence >= 0.9f && it.rhythm != null }
        var next = 1
        for (r in tapping) {
            if (r.source != 0) continue
            r.source = next
            for (o in tapping) {
                if (o.source != 0 || o === r) continue
                val samePattern = r.rhythm != "steady" && r.rhythm == o.rhythm
                val sameTempo = r.tempoMs > 0 && o.tempoMs > 0 &&
                    kotlin.math.abs(r.tempoMs - o.tempoMs).toFloat() / maxOf(r.tempoMs, o.tempoMs) <= 0.25f
                if (samePattern || sameTempo) o.source = next
            }
            next++
        }
        return next - 1
    }

    interface Listener {
        fun onOwnWindow(w: Window)
        fun onLinkStatus(text: String)
        /** Seconds left in the Hush window, or -1 when no window is running. */
        fun onCountdown(secondsLeft: Int)
        /** Commander only: an event from any sensor, including itself. */
        fun onEvent(event: SensorEvent, peerName: String)
        /** Commander only: the connected sensor list changed. */
        fun onPeers(peers: List<Peer>)
        /** Commander only: ranking after a Hush window, strongest first, plus the one-line brief. */
        fun onRanking(ranks: List<Rank>, brief: String)
        /** Commander only: phones seen by their Bluetooth tag (woken by a probe or already sensors), nearest first. */
        fun onDiscovered(phones: List<Discovered>) {}
        /** ALERT role: a household sound was recognised (the phone has already buzzed and posted the notification). */
        fun onAlert(alert: com.hush.audio.SoundAlerts.Alert) {}
        /** HOME role: a cooker whistle was counted, the target reached, or the cooker has gone quiet for too long. */
        fun onWhistle(event: com.hush.audio.WhistleCounter.Event) {}
        /** ALERT role: teaching progress ("Make the sound now… 1 of 3 heard"); [done] when the session ended (saved, cancelled or timed out) or the list changed. */
        fun onTeaching(status: String, done: Boolean) {}
        /** ALERT role, captions on: a piece of recognised speech ([final] false = still being recognised). */
        fun onCaption(text: String, final: Boolean) {}
        fun onCaptionStatus(status: String) {}
        /** Every phone: the closest-phone panel changed (commander computes it, sensors get it in the Board). */
        fun onClosest(text: String) {}
    }

    // ---- Probe / discovered phones (PRD 2.1) ----

    /** One phone seen by its Hush Bluetooth tag. RSSI is a warmer/colder hint, never a distance. */
    data class Discovered(
        val suffix: String, val address: String, val rssi: Int, val medianRssi: Int, val trend: String,
        val roughMetres: Float, val awakeByProbe: Boolean, val battery: Int, val letter: String?, val lastSeenMs: Long, val firstSeenMs: Long
    )
    private class Sighting(val suffix: String) {
        var address = ""; val rssi = ArrayDeque<Int>(); var flags = 0; var battery = -1; var lastMs = 0L; var firstMs = 0L; var announced = false
    }
    private val sightings = LinkedHashMap<String, Sighting>()
    private var lastDiscoveredEmitMs = 0L
    @Volatile var probeStatus: String = ""
        private set
    @Volatile var activatedByProbe = false
        private set
    private var lastRoutedMs = 0L
    private const val PASSIVE_AFTER_MS = 10 * 60_000L

    /** Calibration: play a WAV from the app's private files (see [com.hush.audio.Player]). Any role. */
    fun playFile(name: String, fraction: Float) {
        val ctx = appContext ?: return
        val cap = capture
        com.hush.audio.Player.play(ctx, File(ctx.filesDir, name), fraction) { cap?.samplesCaptured ?: -1L }
    }

    /** Commander: broadcast the probe (PRD "RADIO SWEEP / ACTIVATE SENSORS"). Repeatable at any time. */
    fun activateSensors(): Boolean {
        val ctx = appContext ?: return false
        if (role == ROLE_SENSOR) return requestUp(com.hush.model.Request.SWEEP)
        if (role != ROLE_COMMANDER) return false
        val ok = com.hush.net.Probe.start(ctx, localName)
        probeStatus = if (ok) "Probe: broadcasting for ${com.hush.net.Probe.PROBE_SECONDS} s. Armed phones nearby wake up, buzz and join." else "Probe FAILED: Bluetooth off or advertiser busy (see log)"
        sessionLog.addRecord("probe", mapOf("ok" to ok, "seconds" to com.hush.net.Probe.PROBE_SECONDS))
        ble?.scanForTags()
        listener?.onLinkStatus(lastStatus)
        main.removeCallbacksAndMessages(probeToken)
        main.postDelayed({ if (!com.hush.net.Probe.probing) { probeStatus = "Probe finished. ${sightings.size} phone(s) seen by tag. Tap again to repeat."; listener?.onLinkStatus(lastStatus) } }, probeToken, com.hush.net.Probe.PROBE_SECONDS * 1000L + 500)
        return ok
    }
    private val probeToken = Any()

    /** A rescuer's probe reached this phone while it already runs a role: a short buzz and a status line for 10 s. */
    fun noteProbeHeard(commander: String, rssi: Int) {
        val ctx = appContext ?: return
        try { com.hush.audio.Haptics.vibrate(ctx, longArrayOf(0, 150, 100, 150), "probe heard") } catch (e: Exception) { HLog.d("probe buzz failed: $e") }
        val text = "Rescuer probe heard from $commander ($rssi dBm) · this phone is already ${if (role == ROLE_COMMANDER) "the commander" else "Sensor $letter"}"
        probeStatus = text
        main.post { listener?.onLinkStatus(lastStatus) }
        main.postDelayed({ if (probeStatus == text) { probeStatus = ""; listener?.onLinkStatus(lastStatus) } }, 10_000L)
    }

    /** Commander: every Hush tag sighting lands here (several per second per phone while scanning). */
    private fun onTagSeen(suffix: String, address: String, rssi: Int, flags: Int, battery: Int) {
        val now = SystemClock.elapsedRealtime()
        val s = sightings.getOrPut(suffix) { Sighting(suffix).also { it.firstMs = now } }
        s.address = address; s.flags = flags; s.battery = battery; s.lastMs = now
        s.rssi.addLast(rssi); while (s.rssi.size > 12) s.rssi.removeFirst()
        if (!s.announced) {
            s.announced = true
            val awake = flags and com.hush.net.BleRanging.FLAG_AWAKE_BY_PROBE != 0
            HLog.d("Tag seen: $suffix at $address rssi=$rssi flags=$flags battery=$battery" + if (awake) " (woken by a probe)" else "")
            if (awake) { probeStatus = "VICTIM PHONE DISCOVERED: $suffix (~%.0f m?, rssi $rssi). Waiting for it to join…".format(roughMetres(rssi)); listener?.onLinkStatus(lastStatus) }
            sessionLog.addRecord("tag_seen", mapOf("suffix" to suffix, "rssi" to rssi, "flags" to flags, "battery" to battery))
        }
        if (now - lastDiscoveredEmitMs >= 1000) { lastDiscoveredEmitMs = now; listener?.onDiscovered(discoveredPhones()) }
    }

    /** Log-distance path loss, -59 dBm at 1 m, exponent 2.7: a hint only (these phones read 6-14 m at 0.5 m). */
    private fun roughMetres(rssi: Int): Float = Math.pow(10.0, (-59.0 - rssi) / 27.0).toFloat()

    fun discoveredPhones(): List<Discovered> {
        val now = SystemClock.elapsedRealtime()
        return sightings.values.filter { now - it.lastMs < 120_000 }.map { s ->
            val recent = s.rssi.toList()
            val med = recent.sorted()[recent.size / 2]
            val trend = if (recent.size >= 6) {
                val a = recent.takeLast(3).average(); val b = recent.dropLast(3).takeLast(3).average()
                if (a - b >= 3) "↑ warmer" else if (b - a >= 3) "↓ colder" else "→"
            } else ""
            val letter = peers.values.firstOrNull { it.name.endsWith(s.suffix) }?.letter
            Discovered(s.suffix, s.address, recent.last(), med, trend, roughMetres(med), s.flags and com.hush.net.BleRanging.FLAG_AWAKE_BY_PROBE != 0, s.battery, letter, s.lastMs, s.firstMs)
        }.sortedByDescending { it.medianRssi }
    }

    /** Set by SensorService when the start came from a probe: this phone goes back to passive if no commander shows up. */
    fun markActivatedByProbe() {
        activatedByProbe = true
        lastRoutedMs = SystemClock.elapsedRealtime()
        HLog.d("Sensor activated by a probe; returns to passive after ${PASSIVE_AFTER_MS / 60_000} min without a commander")
        main.removeCallbacks(passiveWatchdog)
        main.postDelayed(passiveWatchdog, 30_000)
    }

    private val passiveWatchdog = object : Runnable {
        override fun run() {
            if (role != ROLE_SENSOR || !activatedByProbe) return
            val now = SystemClock.elapsedRealtime()
            if (link?.isRouted == true) lastRoutedMs = now
            if (now - lastRoutedMs > PASSIVE_AFTER_MS) {
                HLog.d("No commander for ${PASSIVE_AFTER_MS / 60_000} min: back to passive (probe port stays armed)")
                val ctx = appContext ?: return
                Activation.cancel(ctx)
                listener?.onLinkStatus("Back to passive: no commander for 10 min")
                ctx.stopService(android.content.Intent(ctx, SensorService::class.java))   // → onDestroy → Engine.stop()
                return
            }
            main.postDelayed(this, 30_000)
        }
    }

    private fun batteryPercent(): Int = try {
        (appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (e: Exception) { HLog.d("battery level unavailable: $e"); -1 }

    /** Sensor: (re)advertise the Hush tag with the current flags and battery, so a commander sees us within seconds. */
    private val tagRefresh = object : Runnable {
        override fun run() {
            if (role != ROLE_SENSOR) return
            ble?.advertiseConnectable(localName, if (activatedByProbe) com.hush.net.BleRanging.FLAG_AWAKE_BY_PROBE else 0, batteryPercent())
            main.postDelayed(this, 120_000)
        }
    }

    @Volatile var mode: Mode = Mode.TAPPING
        set(value) { if (value != field) HLog.d("Listen mode: $value"); field = value }

    /** A mode button: the commander switches, a sensor asks the commander (the board brings the new mode back). */
    /** HIGH: a knock counts when one phone hears it ≥ ×4 over its background (NORMAL ×8) and WARMER/COLDER reacts at
     *  2 dB (NORMAL 3). Team, 27 Sep: "maybe make it a little more sensitive? or give it a toggle to listen to even lower
     *  decibels". The commander holds it (it judges the knocks); a sensor asks for it; the Board shows it on every phone. */
    @Volatile var sensitiveHigh = false
        private set

    fun setSensitivity(high: Boolean) {
        if (role == ROLE_SENSOR) { requestUp(com.hush.model.Request.SENS, if (high) "HIGH" else "NORMAL"); return }
        sensitiveHigh = high
        closest.minLoudRatio = if (high) 4f else Closest.MIN_LOUD_RATIO
        warmth.stepDb = if (high) 2f else Warmth.STEP_DB
        HLog.d("SENSITIVITY: ${if (high) "HIGH (knocks from x4, trend at 2 dB)" else "NORMAL (knocks from x8, trend at 3 dB)"}")
        listener?.onClosest(closestText)
        sendBoardDown(force = true)
    }

    fun chooseMode(m: Mode) {
        if (role == ROLE_SENSOR) requestUp(com.hush.model.Request.MODE, m.name) else mode = m
    }

    private val hushEvents = HashMap<String, MutableList<SensorEvent>>()   // letter → events during the window
    private val peerHeading = HashMap<String, Float>()                     // letter → last compass heading a sensor reported
    /** One log per session: a fresh one at every start(), so an export holds this session only. */
    var sessionLog = com.hush.log.SessionLog()
        private set
    /** Guards the audio thread's noise-floor history and rhythm tracker against main-thread resets. */
    private val audioLock = Any()
    /** After a window ends, late sensor events for its last second are still accepted until this time. */
    private var hushGraceUntilMs = 0L

    /** Commander's map: letter → (x, y) fractions of the square. Kept here so it survives screen changes. */
    val mapDots = LinkedHashMap<String, Pair<Float, Float>>()

    // ---- The phone's own knock arrow (two mics, no chirps; compass plan step 1) ----

    /** The own arrow stays on screen this long after the last second with tapping evidence. */
    private const val OWN_ARROW_HOLD_MS = 8_000L
    /** Own rhythm score from which a second counts as deliberate tapping: 0.9 = steady, 1.0 = a pattern (0.5 is any 3 onsets). */
    private const val OWN_ARROW_RHYTHM = 0.9f
    /** Or (27 Sep 02:10, "sensitivity too low"): this many usable knocks ≥ LOUD_KNOCK_RATIO × the background within 8 s.
     *  Knuckle knocks measured ×11–28 (×5–169 in the tight-space rounds), room noise ×2–6, so irregular loud knocks count too. */
    private const val LOUD_KNOCK_RATIO = 8f
    private const val LOUD_KNOCKS = 3
    private val loudOnsetMs = ArrayDeque<Long>()
    @Volatile private var ownArrowUntilMs = 0L

    /** This phone's own estimate (its two mics alone), or null: no knocking lately, or no tapping evidence. */
    fun ownArrowRaw(): com.hush.audio.KnockBearing.Estimate? {
        val now = SystemClock.elapsedRealtime()
        if (now > ownArrowUntilMs) return null
        return com.hush.audio.KnockBearing.estimate(now, headingDeg)
    }

    /**
     * The own arrow for the screen: the raw estimate, with its left/right twin settled by the OTHER phones when the
     * commander's fused bearing (sharedBearing here, the Fix on a sensor) is resolved and lies within 40° of one
     * candidate. The event and the fusion use the raw estimate, so a phone never votes with a choice it was given.
     */
    fun ownArrow(): com.hush.audio.KnockBearing.Estimate? {
        val e = ownArrowRaw() ?: return null
        if (e.resolved) return e
        val s = currentShared() ?: return e
        if (s.twinDeg != null) return e
        val twinBearing = e.twinBearingDeg ?: return e
        val dBest = angDiffF(s.bearingDeg, e.bearingDeg); val dTwin = angDiffF(s.bearingDeg, twinBearing)
        return when {
            dBest <= 40f && dBest <= dTwin -> e.copy(twinDeg = null, twinBearingDeg = null, resolved = true, resolvedBy = s.phones)
            dTwin <= 40f -> e.copy(screenDeg = ((twinBearing - headingDeg) + 720f) % 360f, bearingDeg = twinBearing,
                                   twinDeg = null, twinBearingDeg = null, resolved = true, resolvedBy = s.phones)
            else -> e
        }
    }

    private fun angDiffF(a: Float, b: Float): Float { val d = kotlin.math.abs(((a - b) % 360f + 360f) % 360f); return if (d > 180f) 360f - d else d }

    /** The bearing the hearing phones agree on, as seen from THIS phone through its own compass. */
    data class SharedArrow(val screenDeg: Float, val twinDeg: Float?, val phones: String, val confidence: Float,
                           val ageMs: Long, val others: Boolean /* phones other than this one contributed */)

    /**
     * The network's estimate on this phone's screen, or null. Since 27 Sep 03:10 (team: use every phone's mics) this
     * is what every phone draws first; its own two-mic estimate is the fallback when the fusion is missing, stale
     * (> 4 s) or made of this phone alone (then the own estimate is the same thing, fresher).
     */
    fun sharedArrow(): SharedArrow? {
        val s = currentShared() ?: return null
        val now = SystemClock.elapsedRealtime()
        val age = if (role == ROLE_COMMANDER) now - sharedBearingMs else now - receivedFixMs
        val others = s.phones.split(',').any { it.isNotEmpty() && it != letter }
        fun screen(b: Float) = ((b - headingDeg) + 720f) % 360f
        return SharedArrow(screen(s.bearingDeg), s.twinDeg?.let { screen(it) }, s.phones, s.confidence, age, others)
    }

    /** True when the fused bearing should be drawn instead of this phone's own: fresh and not just this phone's echo. */
    fun preferShared(shared: SharedArrow?, own: com.hush.audio.KnockBearing.Estimate?): Boolean =
        shared != null && shared.ageMs <= 4000L && (shared.others || own == null)

    /** Commander: its own fusion (< 10 s old); sensor: the fusion carried by the latest Fix (< 15 s old). */
    private fun currentShared(): Shared? {
        val now = SystemClock.elapsedRealtime()
        if (role == ROLE_COMMANDER) return sharedBearing?.takeIf { now - sharedBearingMs <= CROSS_HOLD_MS }
        val f = receivedFix ?: return null
        if (now - receivedFixMs > 15_000L) return null
        val b = f.sharedBearing ?: return null
        return Shared(b, f.sharedTwin, f.sharedQ ?: 0f, f.sharedBy ?: "")
    }

    /** Which channel is the top mic and how far apart the mics are: laptop hooks, kept in preferences. */
    fun setMicGeometry(mic1Top: Boolean?, spacingM: Float?): String {
        val p = try { appContext?.getSharedPreferences("hush_mic", Context.MODE_PRIVATE) } catch (e: Exception) { HLog.d("Mic geometry prefs: $e"); null }
        mic1Top?.let { com.hush.audio.KnockBearing.mic1IsTop = it; p?.edit()?.putBoolean("mic1top", it)?.apply() }
        spacingM?.takeIf { it in 0.05f..0.30f }?.let { com.hush.audio.KnockBearing.micSpacingM = it.toDouble(); p?.edit()?.putFloat("spacing", it)?.apply() }
        com.hush.audio.KnockBearing.reset()
        val s = micGeometryText()
        HLog.d(s); return s
    }

    private fun micGeometryText() = "Mic geometry: mic1IsTop=${com.hush.audio.KnockBearing.mic1IsTop} spacing=%.3f m (end-fire delay %.1f samples)".format(
        com.hush.audio.KnockBearing.micSpacingM, com.hush.audio.KnockBearing.maxDelaySamples())

    private fun loadMicGeometry(context: Context) {
        try {
            val p = context.getSharedPreferences("hush_mic", Context.MODE_PRIVATE)
            com.hush.audio.KnockBearing.mic1IsTop = p.getBoolean("mic1top", com.hush.audio.KnockBearing.mic1IsTop)
            com.hush.audio.KnockBearing.micSpacingM = p.getFloat("spacing", com.hush.audio.KnockBearing.micSpacingM.toFloat()).toDouble()
        } catch (e: Exception) { HLog.d("Mic geometry prefs unreadable, defaults kept: $e") }
        HLog.d(micGeometryText())
    }

    // ---- Compass / go-to arrow ----

    private var compass: com.hush.audio.Compass? = null
    private var deadReckoning: com.hush.audio.DeadReckoning? = null
    private var gps: com.hush.audio.Gps? = null
    private var lastPlacementSentSteps = -1
    private var stillSeconds = 0

    /** Commander: latest carried-offset reports from sensors, letter → Placement. */
    private val placements = HashMap<String, com.hush.model.Placement>()

    /** How the map got its north, for the screen. */
    @Volatile var alignSource: String = ""
        private set

    /** Sensor: after walking and then standing still for 3 s, tell the commander how far we were carried. */
    private fun trackPlacement(acc: AccelChannel.Result) {
        val dr = deadReckoning ?: return
        if (acc.moving) { stillSeconds = 0; return }
        stillSeconds++
        if (stillSeconds == 3 && dr.steps != lastPlacementSentSteps && dr.steps >= 2) {
            lastPlacementSentSteps = dr.steps
            val p = com.hush.model.Placement(letter, dr.east, dr.north, dr.steps)
            HLog.d("Placement: carried east=%.1f north=%.1f in %d steps".format(dr.east, dr.north, dr.steps))
            if (role == ROLE_SENSOR) link?.sendUp(p.toJson()) else onPlacement(p)
        }
    }

    private fun onPlacement(p: com.hush.model.Placement) {
        placements[p.letter] = p
        HLog.d("Placement from ${p.letter}: east=%.1f north=%.1f steps=%d".format(p.east, p.north, p.steps))
        alignFromPlacements()
        // A sensor was carried and has settled: its dot is stale, re-range when it is quiet to do so.
        if (role == ROLE_COMMANDER && !inHush && !rangingInProgress && peers.size >= 2 &&
            SystemClock.elapsedRealtime() - lastAFrameMs > 15_000) {
            if (chirpCalibration) { HLog.d("Sensor ${p.letter} settled after moving: re-ranging"); autoPlace() }
            else HLog.d("Sensor ${p.letter} settled after moving: its dot may be stale (chirp calibration off, re-place it)")
        }
    }

    /**
     * Orient the ranged map from carried offsets: the commander's own offset and each sensor's give
     * north-referenced vectors A→sensor; compare with the map vectors A→sensor to get the rotation.
     */
    private fun alignFromPlacements() {
        val dr = deadReckoning
        val myE = dr?.east ?: 0f; val myN = dr?.north ?: 0f
        val estimates = ArrayList<Pair<Float, Float>>()   // (rotation no-mirror, rotation mirror)
        for ((l, p) in placements) {
            if (l == "A" || p.steps < 3) continue
            val mapB = mapBearing("A", l) ?: continue
            val e = p.east - myE; val n = p.north - myN
            if (kotlin.math.hypot(e, n) < 1.0f) continue
            val worldB = ((Math.toDegrees(kotlin.math.atan2(e.toDouble(), n.toDouble())) + 360.0) % 360.0).toFloat()
            val mapBMirror = (360f - mapB) % 360f
            estimates.add((((worldB - mapB) + 720f) % 360f) to (((worldB - mapBMirror) + 720f) % 360f))
        }
        if (estimates.isEmpty()) return
        fun circMean(xs: List<Float>): Float {
            val r = xs.map { Math.toRadians(it.toDouble()) }
            return ((Math.toDegrees(kotlin.math.atan2(r.map { kotlin.math.sin(it) }.average(), r.map { kotlin.math.cos(it) }.average())) + 360.0) % 360.0).toFloat()
        }
        fun spread(xs: List<Float>): Float {
            val r = xs.map { Math.toRadians(it.toDouble()) }
            return (1.0 - kotlin.math.hypot(r.map { kotlin.math.cos(it) }.average(), r.map { kotlin.math.sin(it) }.average())).toFloat()
        }
        val noM = estimates.map { it.first }; val mi = estimates.map { it.second }
        val useMirror = estimates.size >= 2 && spread(mi) < spread(noM)
        if (pointedHeading.isNotEmpty()) { HLog.d("Placement align: %.0f° not used, north comes from pointing".format(if (useMirror) circMean(mi) else circMean(noM))); return }
        if (useMirror != mirror) { mirror = useMirror; HLog.d("Placement align: mirror=$mirror") }
        mapRotationDeg = if (useMirror) circMean(mi) else circMean(noM)
        alignSource = "placement walk (${estimates.size} sensor${if (estimates.size > 1) "s" else ""})"
        HLog.d("Placement align: rotation %.0f° from %d sensors (mirror=%b)".format(mapRotationDeg, estimates.size, mirror))
        setRangingStatus("Map aligned to north from how the sensors were carried out.")
    }
    // ---- Manual north (27 Sep, highest priority): the commander points its top at a sensor and taps ALIGN ----
    /** Letter → the commander's compass heading when its top pointed at that sensor. */
    private val pointedHeading = LinkedHashMap<String, Float>()

    /** Commander: "my top points at [letter] now". Returns a one-line result for the screen. */
    fun alignByPointing(letter: String): String {
        if (role != ROLE_COMMANDER) return "Only the commander aligns"
        val mb = mapBearing("A", letter) ?: return "Place A and $letter on the map first (ranging)"
        pointedHeading[letter] = headingDeg
        peers.values.firstOrNull { it.letter == letter }?.let { pointedByName[it.name.takeLast(4)] = headingDeg; savePrefs() }
        HLog.d("ALIGN: pointed at $letter, heading %.0f°, map bearing A→$letter %.0f°".format(headingDeg, mb))
        return applyPointedAlign()
    }

    /**
     * Rotation that fits every stored pointing: rotation = heading − map bearing. With two or more sensors the
     * mirror is decided too (a reflected map turns the two bearings the wrong way round): if the reflected map
     * fits clearly better, the map is flipped. Re-applied after every ranging round that moves the dots.
     */
    fun applyPointedAlign(): String {
        val entries = pointedHeading.entries.mapNotNull { (l, h) -> mapBearing("A", l)?.let { Triple(l, h, it) } }
        if (entries.isEmpty()) return "Nothing aligned yet"
        fun norm(a: Float) = ((a % 360f) + 360f) % 360f
        fun diff(a: Float, b: Float): Float { val d = kotlin.math.abs(norm(a - b)); return if (d > 180f) 360f - d else d }
        fun mean(xs: List<Float>): Float {
            val r = xs.map { Math.toRadians(it.toDouble()) }
            return norm(Math.toDegrees(kotlin.math.atan2(r.map { kotlin.math.sin(it) }.average(), r.map { kotlin.math.cos(it) }.average())).toFloat())
        }
        fun fit(reflected: Boolean): Pair<Float, Float> {
            val rs = entries.map { (_, h, mb) -> norm(h - if (reflected) norm(180f - mb) else mb) }
            val m = mean(rs)
            return m to (rs.maxOfOrNull { diff(it, m) } ?: 0f)
        }
        var (rot, worst) = fit(false)
        if (entries.size >= 2) {
            val (rotR, worstR) = fit(true)
            HLog.d("ALIGN: map as is fits within %.0f°, mirrored within %.0f°".format(worst, worstR))
            if (worstR + 5f < worst) {
                mirror = !mirror
                val flipped = mapDots.mapValues { (_, p) -> p.first to (1f - p.second) }
                mapDots.clear(); mapDots.putAll(flipped)
                try { syncLocatorPositions() } catch (e: Exception) { HLog.d("ERROR in locator after flip: $e") }
                HLog.d("ALIGN: the map was a mirror image of the room, flipped it (mirror=$mirror)")
                rot = rotR; worst = worstR
            }
        }
        mapRotationDeg = rot
        val who = entries.joinToString(",") { it.first }
        alignSource = "pointed at $who" + if (entries.size >= 2) " (±%.0f°)".format(worst) else ""
        HLog.d("ALIGN: map rotation %.0f° from pointing at %s (worst residual %.0f°)".format(rot, who, worst))
        listener?.onPeers(peers.values.toList())
        return "North set: pointed at $who" + if (entries.size >= 2) ", agree within %.0f°".format(worst) else ". Point at a second sensor too."
    }

    /** Degrees to add to a map bearing to get a real compass bearing; set by ALIGN. Null until aligned. */
    @Volatile var mapRotationDeg: Float? = null
        private set
    val headingDeg: Float get() = compass?.headingDeg ?: 0f

    /** Bearing on the map from dot [from] to dot [to], degrees clockwise from map-up. */
    fun mapBearing(from: String, to: String): Float? {
        val a = mapDots[from] ?: return null
        val b = mapDots[to] ?: return null
        val dx = b.first - a.first; val dy = b.second - a.second   // map y grows downward
        return ((Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()) + 360f) % 360f
    }

    /** Map distance between dots in metres, using the last ranging scale (null if the map was placed by hand). */
    fun mapDistanceMetres(from: String, to: String): Float? {
        val scale = mapMetresPerUnit ?: return null
        val a = mapDots[from] ?: return null
        val b = mapDots[to] ?: return null
        return kotlin.math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat() * scale
    }
    var mapMetresPerUnit: Float? = null
        private set

    // ---- Source location (where the knocking / voice comes from, not which sensor is nearest) ----

    /** Commander only. Fed by every phone's onset reports; read by the map, the arrow and the brief. */
    val locator = Locator()
    val sourceFix: Locator.Fix? get() = if (chirpCalibration || locator.hasAnyClock()) locator.fix else null

    /** A dot's position in metres (x right, y up, origin at the map centre), or null before the map has a scale. */
    fun posMetres(letter: String): Pair<Double, Double>? {
        val s = mapMetresPerUnit ?: return null
        val p = mapDots[letter] ?: return null
        return ((p.first - 0.5) * s).toDouble() to ((0.5 - p.second) * s).toDouble()
    }

    /** Metres → map fractions (may fall outside 0..1 when the source is off the square). */
    fun metresToMap(x: Double, y: Double): Pair<Float, Float>? {
        val s = mapMetresPerUnit ?: return null
        return (0.5 + x / s).toFloat() to (0.5 - y / s).toFloat()
    }

    /** The source fix as map fractions, or null. */
    fun sourceOnMap(): Pair<Float, Float>? = sourceFix?.let { metresToMap(it.x, it.y) }

    private fun syncLocatorPositions() {
        val pos = HashMap<String, Pair<Double, Double>>()
        for (l in mapDots.keys) posMetres(l)?.let { pos[l] = it }
        locator.setPositions(pos)
    }

    /** Commander, once a second: match the knocks heard across phones and refresh the source fix. */
    private fun runLocator() {
        val cap = capture ?: return
        val now = SystemClock.elapsedRealtime()
        // Room noises are onsets too and would be located as if they were knocks: fuse knocks only while
        // some phone hears deliberate tapping (rhythm evidence) or a Hush window is running.
        val tapping = inHush || live.values.any { it.evidence * decay(now - it.lastMs) >= 0.9f }
        val useKnocks = mode != Mode.VOICE && tapping
        val useVoice = mode != Mode.TAPPING
        val changed = try {
            locator.process(cap.samplesCaptured, now, mirror, { gainOf(it) }, useKnocks, useVoice)
        } catch (e: Exception) { HLog.d("ERROR in locator: $e"); false }
        if (changed) {
            sourceFix?.let { f ->
                sessionLog.addRecord("locate", mapOf("x" to f.x, "y" to f.y, "radius" to f.radius, "spread" to f.bearingSpreadDeg,
                    "knocks" to f.knocks, "voice" to f.voiceSeconds, "edge" to f.edge, "distA" to sourceDistanceMetres()))
            }
            listener?.onLinkStatus(lastStatus)   // redraws the map with the source marker
        }
        sendFixDown(changed, now)
    }

    // ---- Positions from GPS (compass plan step 3, 27 Sep): outdoors, phones 10 m+ apart, north-up map ----

    private val gpsSamples = HashMap<String, ArrayDeque<GpsLayout.Sample>>()
    /** True once the dots came from GPS: the map is north-up, A's dot follows its own fix, walking is ignored. */
    @Volatile var gpsLayoutActive = false
        private set
    private var gpsLayoutCheckMs = 0L
    private var gpsLayoutReason = ""
    /** The previous GPS solution: a new one is applied only when every phone agrees with it within the worst accuracy. */
    private var gpsPending: Map<String, Pair<Double, Double>>? = null

    private fun recordGps(e: SensorEvent, now: Long) {
        val lat = e.lat ?: return; val lon = e.lon ?: return
        val q = gpsSamples.getOrPut(e.sensorId) { ArrayDeque() }
        q.addLast(GpsLayout.Sample(lat, lon, e.gpsAcc ?: 99f, now))
        while (q.size > 5) q.removeFirst()
    }

    /** Commander, every 5 s: put every phone on the map from its GPS fixes when that is good enough (GpsLayout rules). */
    private fun applyGpsLayout(now: Long) {
        if (now - gpsLayoutCheckMs < 5000L) return
        gpsLayoutCheckMs = now
        if (layoutByName.isNotEmpty()) {
            val why = "a hand layout is stored, GPS not used (--es layout clear drops it)"
            if (why != gpsLayoutReason) { gpsLayoutReason = why; HLog.d("GPS layout: $why") }
            return
        }
        val letters = listOf("A") + peers.values.map { it.letter }.filter { it != "?" }
        val (r, why) = try { GpsLayout.solve(gpsSamples, letters, now) } catch (e: Exception) { HLog.d("ERROR in GPS layout: $e"); return }
        if (r == null) {
            if (why != gpsLayoutReason) { gpsLayoutReason = why; HLog.d("GPS layout: not yet: $why") }
            return
        }
        gpsLayoutReason = ""
        // Stability (27 Sep 03:08, indoor noise placed a 0.5 m cluster 14 m apart): two consecutive solutions 5 s apart
        // must agree for every phone within the worst accuracy before the map moves.
        val prev = gpsPending
        gpsPending = r.positions
        val jump = prev?.let { p -> r.positions.entries.maxOfOrNull { (l, xy) -> p[l]?.let { q -> kotlin.math.hypot(xy.first - q.first, xy.second - q.second) } ?: Double.MAX_VALUE } }
        if (jump == null || jump > r.worstAccM) {
            HLog.d("GPS layout: waiting for a stable solution (%s)".format(if (jump == null) "first one" else "a phone moved %.0f m in 5 s, accuracy ±%.0f m".format(jump, r.worstAccM)))
            return
        }
        // Same map construction as the hand layout: centred, 1.6 × the largest side per map width.
        var maxSide = 0.0
        for (a in r.positions.values) for (b in r.positions.values) maxSide = maxOf(maxSide, kotlin.math.hypot(a.first - b.first, a.second - b.second))
        val scale = (1.6 * maxSide).toFloat()
        val cx = r.positions.values.map { it.first }.average(); val cy = r.positions.values.map { it.second }.average()
        mapDots.clear()
        for ((l, p) in r.positions) mapDots[l] = (0.5 + (p.first - cx) / scale).toFloat() to (0.5 - (p.second - cy) / scale).toFloat()
        mapMetresPerUnit = scale
        mirror = false
        if (!gpsLayoutActive) {
            gpsLayoutActive = true
            if (pointedHeading.isNotEmpty()) { HLog.d("GPS layout: pointing at ${pointedHeading.keys} not needed, the GPS map is north-up"); pointedHeading.clear() }
        }
        mapRotationDeg = 0f    // east/north frame: map-up is north, so compass bearing = map bearing
        alignSource = "GPS (±%.0f m)".format(r.worstAccM)
        try { syncLocatorPositions() } catch (e: Exception) { HLog.d("ERROR in locator after GPS layout: $e") }
        val text = r.positions.entries.joinToString(" ") { "%s(%.1f,%.1f)".format(it.key, it.value.first, it.value.second) }
        HLog.d("GPS layout: $text m east/north of A, worst ±%.0f m, nearest pair %.0f m, scale %.1f m per map".format(r.worstAccM, r.minPairM, scale))
        val status = "Positions from GPS (±%.0f m): %s".format(r.worstAccM, text)
        if (status != rangingStatus) setRangingStatus(status)
        listener?.onPeers(peers.values.toList())
    }

    // ---- Bearings from every phone → lines on the map → crossing (compass plan step 2, 27 Sep) ----

    /** Commander: what each phone's own two-mic arrow says (compass degrees), its mirror twin, confidence, when. */
    private class PeerBearing(val bearing: Float, val twin: Float?, val q: Float, val atMs: Long, val rhythm: Float = 0.5f)
    private val peerBearing = HashMap<String, PeerBearing>()
    /** Where the phones' bearing lines cross, metres in the map frame (see posMetres), or null. */
    @Volatile var crossFix: Crossing.Result? = null
        private set
    @Volatile private var crossFixMs = 0L
    private var crossNoneLoggedMs = 0L
    /** A phone's bearing counts this long after its last event. */
    private const val CROSS_FRESH_MS = 5_000L
    /** The crossing stays this long after the last solution. */
    private const val CROSS_HOLD_MS = 10_000L

    /** Compass bearing → map bearing (clockwise from map-up). Like arrowAngleTo, the mirror flag is ignored. */
    private fun compassToMap(compass: Float): Double {
        val rot = mapRotationDeg ?: return compass.toDouble()
        return (((compass - rot) % 360f + 360f) % 360f).toDouble()
    }

    /** Commander, once a second: cross the fresh bearing lines of the phones that are on the map. */
    private fun crossBearings(now: Long) {
        val lines = ArrayList<Crossing.Line>()
        if (mapRotationDeg != null && mapMetresPerUnit != null) {
            for ((l, b) in peerBearing) {
                if (now - b.atMs > CROSS_FRESH_MS) continue
                val p = posMetres(l) ?: continue
                lines.add(Crossing.Line(l, p.first, p.second, listOfNotNull(compassToMap(b.bearing), b.twin?.let { compassToMap(it) })))
            }
        }
        var maxSide = 0.0
        val pts = mapDots.keys.mapNotNull { posMetres(it) }
        for (a in pts) for (c in pts) maxSide = maxOf(maxSide, kotlin.math.hypot(a.first - c.first, a.second - c.second))
        val r = if (lines.size >= 2) try { Crossing.solve(lines, 3.0 * maxSide + 2.0) } catch (e: Exception) { HLog.d("ERROR in crossing: $e"); null } else null
        if (r != null) {
            crossFix = r; crossFixMs = now
            HLog.d("CROSS: source at (%.2f, %.2f) m ±%.1f from %s (lines meet at ≥ %.0f°, residual %.2f m)".format(
                r.x, r.y, r.radius, r.chosen.entries.joinToString(" ") { "%s→%.0f°".format(it.key, it.value) }, r.spreadDeg, r.residual))
            sessionLog.addRecord("cross", mapOf("x" to r.x, "y" to r.y, "radius" to r.radius, "phones" to r.chosen.keys.joinToString(""), "residual" to r.residual))
            listener?.onLinkStatus(lastStatus)   // redraws the map
        } else {
            if (crossFix != null && now - crossFixMs > CROSS_HOLD_MS) {
                crossFix = null
                HLog.d("CROSS: no crossing for ${CROSS_HOLD_MS / 1000} s, cleared")
                listener?.onLinkStatus(lastStatus)
            }
            if (lines.isNotEmpty() && now - crossNoneLoggedMs > 5000L) {
                crossNoneLoggedMs = now
                HLog.d("CROSS: no point from %d line%s (%s)%s".format(lines.size, if (lines.size == 1) "" else "s",
                    lines.joinToString(" ") { l -> "%s→%s".format(l.letter, l.bearingsDeg.joinToString("/") { "%.0f°".format(it) }) },
                    if (lines.size < 2) ": need two phones on the map hearing it" else ": ambiguous, parallel, behind a phone or too far"))
            }
        }
    }

    /** letter → (map angle, mirror twin) of each phone's fresh knock bearing, for the map's lines. Commander, main thread. */
    fun bearingLines(): Map<String, Pair<Float, Float?>> {
        if (mapRotationDeg == null) return emptyMap()
        val now = SystemClock.elapsedRealtime()
        val out = LinkedHashMap<String, Pair<Float, Float?>>()
        for ((l, b) in peerBearing) if (now - b.atMs <= CROSS_FRESH_MS && mapDots.containsKey(l))
            out[l] = compassToMap(b.bearing).toFloat() to b.twin?.let { compassToMap(it).toFloat() }
        return out
    }

    /** The crossing as map fractions, or null. */
    fun crossingOnMap(): Pair<Float, Float>? =
        crossFix?.takeIf { SystemClock.elapsedRealtime() - crossFixMs <= CROSS_HOLD_MS }?.let { metresToMap(it.x, it.y) }
    /** The crossing's radius as a fraction of the map, for the disc. */
    fun crossingRadiusOnMap(): Float = crossFix?.let { c -> mapMetresPerUnit?.let { (c.radius / it).toFloat() } } ?: 0f

    data class CrossArrow(val screenDeg: Float, val metres: Float, val radius: Float, val phones: Int)

    /** Commander: the arrow from A to the crossing (screen angle clockwise from the phone's top), or null. */
    fun crossingArrow(): CrossArrow? {
        val c = crossFix ?: return null
        if (SystemClock.elapsedRealtime() - crossFixMs > CROSS_HOLD_MS) return null
        val a = posMetres("A") ?: return null
        val rot = mapRotationDeg ?: return null
        val mb = Crossing.bearing(a.first, a.second, c.x, c.y)
        val screen = ((mb + rot - headingDeg) + 720.0) % 360.0
        return CrossArrow(screen.toFloat(), kotlin.math.hypot(c.x - a.first, c.y - a.second).toFloat(), c.radius.toFloat(), c.chosen.size)
    }

    // ---- Fused bearing: what the phones that hear it agree on, for the phones that do not (27 Sep 02:10) ----

    /** World bearing the hearing phones agree on, its mirror twin while ambiguous, confidence, which phones ("A,B"). */
    data class Shared(val bearingDeg: Float, val twinDeg: Float?, val confidence: Float, val phones: String)
    @Volatile var sharedBearing: Shared? = null
        private set
    @Volatile private var sharedBearingMs = 0L
    private var sharedLogged = ""
    /** The accumulating vote histogram (5° bins) and when it was last decayed. */
    private val sharedHist = DoubleArray(com.hush.audio.KnockBearing.BINS)
    private var sharedHistMs = 0L
    /** Votes fade with this time constant: agreeing phones pile up over ~10 s, a swing of one phone barely moves the peak. */
    private const val SHARED_TAU_MS = 10_000.0

    /**
     * Commander, once a second. Every phone whose own arrow shows votes with its compass bearing in a 5° histogram:
     * a resolved phone one vote, an unresolved one half a vote for each candidate. The peak is the shared bearing.
     * Phones lying at different angles have different mirrors, so the true candidates pile up and the mirrors do
     * not: that settles left/right without anyone turning a phone. Within half a metre of each other the phones see
     * a knock a metre away in nearly the same direction, so the phones that hear nothing draw this bearing through
     * their own compass, and a hearing phone with an open twin takes the candidate nearer to it (ownArrow).
     */
    /**
     * [voted] is the phone whose event just arrived: its current bearing is added as ONE vote (its events come once a
     * second). The histogram decays with [SHARED_TAU_MS], so the estimate is an accumulation over the last ~10 s of
     * everyone's readings (27 Sep 03:30, team: "instead of jumping around, increase confidence from the other phones").
     */
    private fun fuseBearings(now: Long, voted: String? = null) {
        val kb = com.hush.audio.KnockBearing
        if (sharedHistMs > 0L) { val k = kotlin.math.exp(-(now - sharedHistMs) / SHARED_TAU_MS); for (i in sharedHist.indices) sharedHist[i] *= k }
        sharedHistMs = now
        val v = voted?.let { peerBearing[it] }
        if (v != null && now - v.atMs <= CROSS_FRESH_MS) {
            // Rhythm over class (team, 27 Sep 03:15): a phone hearing steady or patterned tapping (0.9–1.0) counts
            // up to twice one that only heard stray loud onsets (0.2–0.5).
            val w = v.q.coerceAtLeast(0.2f).toDouble() * (0.5 + v.rhythm.coerceIn(0f, 1f))
            if (v.twin == null) kb.vote(sharedHist, v.bearing.toDouble(), w)
            else { kb.vote(sharedHist, v.bearing.toDouble(), w / 2); kb.vote(sharedHist, v.twin.toDouble(), w / 2) }
            sharedBearingMs = now
        }
        val fresh = peerBearing.filter { now - it.value.atMs <= CROSS_FRESH_MS }
        val all = sharedHist.sum()
        if (fresh.isEmpty() && sharedBearing != null && now - sharedBearingMs > CROSS_HOLD_MS) {
            sharedBearing = null; sharedLogged = ""; sharedHist.fill(0.0)
            HLog.d("SHARED: nobody has heard it for ${CROSS_HOLD_MS / 1000} s, cleared")
            return
        }
        if (all < 0.05 || (fresh.isEmpty() && sharedBearing == null)) return
        val best = kb.peakBin(sharedHist, -1)
        val second = kb.peakBin(sharedHist, best)
        val bearing = kb.refine(sharedHist, best).toFloat()
        val twin = kb.refine(sharedHist, second).toFloat()
        val resolved = sharedHist[second] * kb.RESOLVE_RATIO <= sharedHist[best]
        var near = 0.0
        for (i in sharedHist.indices) if (kb.angDiff(bearing.toDouble(), i * 360.0 / kb.BINS) <= 24.0) near += sharedHist[i]
        val phones = if (fresh.isNotEmpty()) fresh.keys.sorted().joinToString(",") else (sharedBearing?.phones ?: "")
        sharedBearing = Shared(bearing, if (resolved) null else twin, (near / all).toFloat(), phones)
        val keyText = "%.0f %s %b".format(bearing / 10f, phones, resolved)
        if (keyText != sharedLogged) {
            sharedLogged = keyText
            HLog.d("SHARED: bearing %.0f°%s from %s, conf %.2f (%s)".format(bearing, if (resolved) "" else " or %.0f°".format(twin), phones, near / all,
                fresh.entries.joinToString(" ") { "%s=%.0f°%s r%.1f".format(it.key, it.value.bearing, it.value.twin?.let { t -> "/%.0f°".format(t) } ?: "", it.value.rhythm) }))
        }
    }

    // ---- Fix → sensors (27 Sep): every phone draws an arrow at the located source ----
    private var fixSeq = 0
    private var lastFixSentMs = 0L

    /** Commander: send the source fix down the tree when it changed, and every 5 s while it exists (rotation may change). */
    private var lastFixKey: String? = null

    private fun sendFixDown(changedFix: Boolean, now: Long) {
        if (role != ROLE_COMMANDER) return
        val f = sourceFix
        val cross = if (f == null) crossFix?.takeIf { now - crossFixMs <= CROSS_HOLD_MS } else null
        val near = if (f == null && cross == null) arrowTarget else null
        val shared = sharedBearing?.takeIf { now - sharedBearingMs <= CROSS_HOLD_MS }
        if (f == null && cross == null && near == null && shared == null) return
        val key = (if (f != null) "fix" else if (cross != null) "cross %.0f,%.0f".format(cross.x * 2, cross.y * 2) else if (near != null) "near $near" else "none") +
            (shared?.let { " shared %.0f %s %b".format(it.bearingDeg / 2f, it.phones, it.twinDeg == null) } ?: "")
        val changed = changedFix || key != lastFixKey
        if (!changed && now - lastFixSentMs < 5000L) return
        if (changed && now - lastFixSentMs < 300L) return   // a moving bearing still goes out at most ~3 times a second
        val point = if (f != null) sourceOnMap() else if (cross != null) metresToMap(cross.x, cross.y) else if (near != null) mapDots[near] else null
        if (point == null && shared == null) return
        val src = point ?: (0.5f to 0.5f)   // no map point: only the shared bearing travels (hasPoint = false)
        lastFixKey = key
        val msg = com.hush.model.Fix(++fixSeq, src.first, src.second, (f?.radius ?: cross?.radius)?.toFloat() ?: 0f, f?.knocks ?: (cross?.chosen?.size ?: 0), f?.edge ?: false,
            mapRotationDeg, mirror, mapMetresPerUnit, alignSource, LinkedHashMap(mapDots), if (point != null) near else null,
            hasPoint = point != null, sharedBearing = shared?.bearingDeg, sharedTwin = shared?.twinDeg, sharedQ = shared?.confidence, sharedBy = shared?.phones)
        lastFixSentMs = now
        val l = link
        if (l == null) { HLog.d("FIX not sent: no link"); return }
        l.sendDown(msg.toJson())
        HLog.d("FIX sent seq=%d target=%s src=(%.2f, %.2f) r=%.2f knocks=%d rot=%s mirror=%b scale=%s dots=%s north=%s%s".format(
            msg.seq, if (f != null) "located source" else if (cross != null) "crossing of ${cross.chosen.keys.joinToString("")}" else "loudest $near", msg.x, msg.y, msg.radius, msg.knocks, mapRotationDeg?.let { "%.0f°".format(it) } ?: "-", mirror,
            mapMetresPerUnit?.let { "%.2f".format(it) } ?: "-",
            msg.dots.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) },
            alignSource.ifEmpty { "-" }, if (changed) "" else " (repeat)") +
            (shared?.let { " shared=%.0f°%s by %s".format(it.bearingDeg, it.twinDeg?.let { t -> "/%.0f°".format(t) } ?: "", it.phones) } ?: "") +
            (if (point == null) " (no map point)" else ""))
    }

    /** Sensor: the newest fix from the commander and when it arrived (elapsedRealtime). */
    @Volatile var receivedFix: com.hush.model.Fix? = null
        private set
    @Volatile var receivedFixMs = 0L
        private set

    private fun onFix(f: com.hush.model.Fix) {
        receivedFix = f; receivedFixMs = SystemClock.elapsedRealtime()
        val a = sensorArrow()
        HLog.d("FIX received seq=%d target=%s src=(%.2f, %.2f) knocks=%d rot=%s own dot=%s -> mapBearing=%s dist=%s heading=%.0f° screen=%s".format(
            f.seq, f.near?.let { if (it == letter) "loudest $it = HERE" else "loudest $it" } ?: "located source", f.x, f.y, f.knocks, f.rotation?.let { "%.0f°".format(it) } ?: "-",
            f.dots[letter]?.let { "(%.2f,%.2f)".format(it.first, it.second) } ?: "none($letter)",
            a?.mapBearing?.let { "%.0f°".format(it) } ?: "-", a?.metres?.let { "%.2f m".format(it) } ?: "-", headingDeg,
            a?.screenDeg?.let { "%.0f°".format(it) } ?: "-"))
    }

    /** What a sensor's arrow shows: map bearing own dot → source, metres, and the screen angle (null until north is set). */
    data class SensorArrow(val mapBearing: Float, val metres: Float?, val screenDeg: Float?, val north: String, val knocks: Int, val edge: Boolean,
                           val near: String? = null, val here: Boolean = false)

    /** Sensor: its arrow toward the commander's latest fix, or null (no fix, fix older than 60 s, or not on the map). */
    fun sensorArrow(): SensorArrow? {
        val f = receivedFix ?: return null
        if (SystemClock.elapsedRealtime() - receivedFixMs > 60_000L) return null
        if (f.near != null && f.near == letter) return SensorArrow(0f, 0f, null, f.north, 0, false, f.near, here = true)
        if (!f.hasPoint) return null
        val me = f.dots[letter] ?: return null
        val dx = f.x - me.first; val dy = f.y - me.second   // map y grows downward
        val bearing = ((Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()) + 360f) % 360f
        val metres = f.scale?.let { kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() * it }
        val screen = f.rotation?.let { ((bearing + it - headingDeg) + 720f) % 360f }
        return SensorArrow(bearing, metres, screen, f.north, f.knocks, f.edge, f.near)
    }

    /** Distance from the commander's dot to the source fix, metres, or null. */
    fun sourceDistanceMetres(): Float? {
        val f = sourceFix ?: return null
        val a = posMetres("A") ?: return null
        return kotlin.math.hypot(f.x - a.first, f.y - a.second).toFloat()
    }

    /** Map bearing from the commander's dot to the source fix. */
    fun sourceBearing(): Float? {
        val f = sourceFix ?: return null
        val a = posMetres("A") ?: return null
        return ((Math.toDegrees(kotlin.math.atan2(f.x - a.first, f.y - a.second)).toFloat()) + 360f) % 360f
    }

    /** Screen angle of the arrow that points at the source, or null (needs a fix and north). */
    fun arrowAngleToSource(): Float? {
        val bearing = sourceBearing() ?: return null
        val rot = mapRotationDeg ?: return null
        return ((bearing + rot - headingDeg) + 720f) % 360f
    }

    /** Short text for the brief: how far the source is from the commander. */
    private fun sourceText(): String {
        val f = sourceFix ?: return ""
        val d = sourceDistanceMetres() ?: return ""
        val what = if (f.knocks > 0) "${f.knocks} knock" + (if (f.knocks > 1) "s" else "") else "${f.voiceSeconds} s voice"
        return if (f.edge) " · source ≥ %.0f m away, direction ±%.0f° (%s)".format(f.nearest, f.bearingSpreadDeg, what)
               else " · source %.1f m from you ±%.1f (%s)".format(d, f.radius, what)
    }


    /** Screen angle (clockwise from the phone's top) of the arrow that points at [letter], or null. */
    fun arrowAngleTo(letter: String): Float? {
        val bearing = mapBearing("A", letter) ?: return null
        val rot = mapRotationDeg ?: return null
        return ((bearing + rot - headingDeg) + 720f) % 360f
    }

    // ---- Acoustic ranging (auto placement) ----

    /** heard[hearer letter][chirping letter] = sample index on the hearer's clock. Commander only. */
    private val heard = HashMap<String, HashMap<String, Long>>()
    /** When each letter's chirp was last triggered (our clock, ms), and which trigger each detection belongs to. */
    private val chirpTriggerMs = HashMap<String, Long>()
    private val heardTrig = HashMap<String, HashMap<String, Long>>()
    private var rangingInProgress = false
    private var retriedThisRound = false
    private var lastRoundDist: HashMap<String, Double>? = null
    /** Silent radio (Bluetooth Channel Sounding) distances, pair key "AB" → metres. Filled by [BleRanging]. */
    val radioDistance = HashMap<String, Double>()
    private var ble: com.hush.net.BleRanging? = null
    private var ownBleAddress: String? = null
    private val peerBleAddress = HashMap<String, String>()   // sensor name → address
    private val radioShown = HashMap<String, String>()       // pair → text for the status line ("?" = low confidence)
    @Volatile var radioStatus: String = ""
        private set

    private val bleListener = object : com.hush.net.BleRanging.Listener {
        override fun onDistance(peerLetter: String, metres: Double, technology: String, confidence: Int) {
            main.post {
                if (role == ROLE_COMMANDER) {
                    // Signal-strength readings are shown but never trusted enough to overrule a chirp round
                    // (measured 8–14 m for phones half a metre apart). Only Channel Sounding feeds the fusion.
                    if (technology == "CS") radioDistance["A$peerLetter"] = metres else radioDistance.remove("A$peerLetter")
                    radioShown["A$peerLetter"] = "%.1f m".format(metres) + if (technology == "CS") "" else "?"
                    radioStatus = radioShown.entries.joinToString("  ") { "${it.key} ${it.value}" } + " ($technology)"
                    listener?.onLinkStatus(lastStatus)
                }
            }
        }
        override fun onTagSeen(suffix: String, address: String, rssi: Int, flags: Int, battery: Int) {
            main.post { if (role == ROLE_COMMANDER) this@Engine.onTagSeen(suffix, address, rssi, flags, battery) }
        }
        override fun onOwnAddress(address: String) {
            ownBleAddress = address
            // If we are a sensor already routed, re-announce with the address.
            if (role == ROLE_SENSOR && link?.isRouted == true) link?.sendUp(com.hush.model.Join(localName, link!!.hops, ble = address).toJson())
        }
        override fun onStatus(text: String) { HLog.d(text) }
    }

    private fun startRadioTo(name: String, letter: String) {
        ble?.rangeAsInitiator(letter, name)
    }
    @Volatile var rangingStatus: String = ""
        private set
    var mirror = false   // commander's "flip" for the mirror ambiguity of a triangle

    /**
     * Ranging is SERIALISED: one letter chirps, everyone reports, then the next letter. Commands over the
     * mesh were measured 26 Sep 23:13 to take 0.6 s on one sensor and 1.4–20 s on another, so a fixed
     * 1.8 s schedule put chirps outside the search windows. Now each phone searches ±2.5 s around the
     * moment it received the command (bounded below by the last chirp it heard), and the commander moves
     * on when every phone has reported or after [CHIRP_TIMEOUT_MS].
     */
    private const val SEARCH_BEFORE_MS = 2500
    // 2.5 s makes every chirp slot ~3.4 s. Tried 1.5 s on 27 Sep 08:15–08:21 (slots 2.3 s): rounds lost pairs and
    // disagreed by metres; not separated from the team moving the phones, so back to 2.5 s until a still-phones test.
    private const val SEARCH_AFTER_MS = 2500
    /**
     * Budget per chirp (measured 26–27 Sep with the phones' clock skew removed): command down 0.01–0.25 s
     * (outliers 1.5 s), each phone waits SEARCH_AFTER + 0.1 s, then two searches (mic 0 and mic 1, 37–94 ms each
     * instead of 1.2–5.6 s), then the report goes up (similar latency): ~3–4.5 s. The commander moves on as soon
     * as every phone has reported, so the timeout only costs time when a phone missed the chirp or a link stalls.
     */
    private const val CHIRP_TIMEOUT_MS = 7000L
    private const val CHIRP_SETTLE_MS = 400L
    /** The chosen 4 kHz cycle must beat the neighbouring cycles by this much (27 Sep stereo audio: ambiguous
     *  cases 0.007–0.08, the repeatable ones 0.19–0.24). */
    private const val MIC_DELAY_MIN_MARGIN = 0.15
    /** Samples: ~17 cm of path, the most two mics on a 16 cm phone can differ. */
    private const val MIC_DELAY_MAX = 24.0
    private var roundQueue: List<String> = emptyList()     // letters still to chirp in this pass
    private var roundAll: List<String> = emptyList()       // all letters of the round (for finishRanging)
    private var roundIndex = 0
    private var reportsForCurrent = 0
    /** Sample at which this phone last detected any chirp: the next search starts after it. */
    @Volatile private var lastChirpDetectedSample = -1L

    /** Commander: run one chirp per phone, then place the dots from the pairwise distances. */
    /**
     * Chirp calibration (27 Sep 00:50, the team: "impossible in a chaotic place"): off. Nothing chirps on its own —
     * HUSH goes straight to the window, no re-ranging after walking or a moved sensor, mic gains are taken as 1
     * (identical phones). Positions come from the layout hook or the Place buttons, north from ALIGN pointing, and the
     * arrow target is the phone that hears the knocking loudest. The AUTO-PLACE button and --ez range still chirp.
     */
    @Volatile var chirpCalibration = false

    fun gainOf(letter: String): Float = if (chirpCalibration) micGain[letter] ?: 1f else 1f

    /**
     * Commander: positions without sound. [spec] is "B=x,y;C=x,y" in metres with A at 0,0 (any orientation and either
     * mirror image: ALIGN by pointing fixes both). Returns a one-line result.
     */
    fun setLayout(spec: String): String {
        if (role != ROLE_COMMANDER) return "Only the commander sets the layout"
        if (spec.trim().equals("clear", true)) {
            layoutByName.clear(); pointedByName.clear(); pointedHeading.clear(); savePrefs(); placedByHand = false
            HLog.d("LAYOUT: hand layout and pointing cleared")
            return "Hand layout and pointing cleared (GPS may place the phones now)"
        }
        val byName = LinkedHashMap<String, Pair<Double, Double>>()   // name suffix → metres
        try {
            for (part in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, xy) = part.split('=').map { it.trim() }
                val (x, y) = xy.split(',').map { it.trim().toDouble() }
                // A token is a letter (B) or a phone's name suffix (991e); stored by suffix, letters change on rejoin.
                val suffix = peers.values.firstOrNull { it.letter.equals(k, true) }?.name?.takeLast(4) ?: k.lowercase()
                byName[suffix] = x to y
            }
        } catch (e: Exception) { HLog.d("LAYOUT: bad spec '$spec': $e"); return "Bad layout '$spec' (want B=x,y;C=x,y or 991e=x,y)" }
        layoutByName.clear(); layoutByName.putAll(byName)
        savePrefs()
        rangeBias = null; lastBiasRound = null; autoLocateRounds = 0   // a new tape: calibrate the chirp distances again
        return applyLayout()
    }

    /** Name suffix → position in metres (A at 0,0). Set by the layout hook, kept in the commander's preferences. */
    private val layoutByName = LinkedHashMap<String, Pair<Double, Double>>()
    /** Name suffix → the commander's heading when it pointed at that phone (ALIGN), kept in preferences. */
    private val pointedByName = LinkedHashMap<String, Float>()
    private var prefs: android.content.SharedPreferences? = null

    private fun savePrefs() {
        val p = prefs ?: return
        val layout = layoutByName.entries.joinToString(";") { "%s=%.3f,%.3f".format(java.util.Locale.US, it.key, it.value.first, it.value.second) }
        val align = pointedByName.entries.joinToString(";") { "%s=%.1f".format(java.util.Locale.US, it.key, it.value) }
        p.edit().putString("layout", layout).putString("align", align).apply()
        HLog.d("PREFS saved: layout='$layout' align='$align'")
    }

    private fun loadPrefs(context: Context) {
        val p = context.getSharedPreferences("hush_commander", Context.MODE_PRIVATE)
        prefs = p
        layoutByName.clear(); pointedByName.clear()
        try {
            p.getString("layout", "")!!.split(';').filter { it.contains('=') }.forEach { t ->
                val (k, xy) = t.split('='); val (x, y) = xy.split(',').map { it.toDouble() }; layoutByName[k] = x to y
            }
            p.getString("align", "")!!.split(';').filter { it.contains('=') }.forEach { t ->
                val (k, h) = t.split('='); pointedByName[k] = h.toFloat()
            }
        } catch (e: Exception) { HLog.d("PREFS: unreadable, ignored: $e") }
        HLog.d("PREFS loaded: layout=$layoutByName align=$pointedByName")
    }

    /** Commander: put the stored layout on the map for the phones present now, then re-apply stored pointing. */
    private fun applyLayout(): String {
        if (layoutByName.isEmpty()) return "No layout stored"
        val pts = LinkedHashMap<String, Pair<Double, Double>>()
        // The commander at its own stored spot when the layout names it (any phone may be commander), else at 0,0.
        pts["A"] = layoutByName[localName.takeLast(4).lowercase()] ?: layoutByName[localName.takeLast(4)] ?: (0.0 to 0.0)
        for ((suffix, xy) in layoutByName) {
            val l = peers.values.firstOrNull { it.name.endsWith(suffix) }?.letter ?: continue
            pts[l] = xy
        }
        if (pts.size < 2) return "Layout stored for ${layoutByName.keys}, none of them connected yet"
        var maxSide = 0.0
        for (a in pts.values) for (b in pts.values) maxSide = maxOf(maxSide, kotlin.math.hypot(a.first - b.first, a.second - b.second))
        if (maxSide <= 0.0) return "Layout needs at least one sensor away from A"
        val scale = (1.6 * maxSide).toFloat()
        val cx = pts.values.map { it.first }.average(); val cy = pts.values.map { it.second }.average()
        mapDots.clear()
        for ((l, p) in pts) mapDots[l] = (0.5 + (p.first - cx) / scale).toFloat() to (0.5 - (p.second - cy) / scale).toFloat()
        mapMetresPerUnit = scale
        placedByHand = true
        mirror = false   // dots are as given; stored pointing below decides the mirror again
        try { syncLocatorPositions() } catch (e: Exception) { HLog.d("ERROR in locator after layout: $e") }
        val text = pts.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) }
        HLog.d("LAYOUT: set by hand $text m, scale %.2f m per map, dots %s".format(scale,
            mapDots.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) }))
        setRangingStatus("Placed by hand: $text")
        // Layout frame: +y = where the phones' tops pointed at SYNC COMPASS, so SYNC orients the map (no pointing needed).
        if (pointedHeading.isEmpty()) lastSyncRef?.let { mapRotationDeg = it; alignSource = "layout +y = phones' tops at SYNC" }
        main.postDelayed({ clockRound("layout set") }, 2000)
        // Stored pointing (by phone name) back onto letters present now.
        for ((suffix, h) in pointedByName) peers.values.firstOrNull { it.name.endsWith(suffix) }?.let { pointedHeading[it.letter] = h }
        if (pointedHeading.isNotEmpty()) applyPointedAlign()
        listener?.onPeers(peers.values.toList())
        return "Layout set: $text"
    }

    fun autoPlace() {
        if (role != ROLE_COMMANDER || rangingInProgress) return
        val letters = listOf("A") + peers.values.map { it.letter }
        if (letters.size < (if (handPositions()) 2 else 3)) { setRangingStatus("Need at least ${if (handPositions()) 2 else 3} phones connected (have ${letters.size})"); return }
        rangingInProgress = true
        retriedThisRound = false
        // The walking trigger counts movement since the last round STARTED. It used to be cleared only when a
        // round placed the map, so after a failed round the commander re-ranged every 18 s forever (26 Sep
        // 23:24–23:28, 13 rounds while lying still).
        movedSinceRanging = 0f
        heard.clear(); heardTrig.clear(); chirpTriggerMs.clear()
        doaThisRound.clear()
        doaAll.clear()
        setRangingStatus("Ranging: chirping ${letters.joinToString(" ")}…")
        sessionLog.addRecord("ranging_start", mapOf("letters" to letters.joinToString(" ")))
        startChirpSequence(letters, letters)
    }

    private fun startChirpSequence(queue: List<String>, all: List<String>) {
        roundQueue = queue; roundAll = all; roundIndex = 0
        main.removeCallbacksAndMessages(chirpToken)
        nextChirp()
    }

    /** Commander: chirp the next letter of the queue, or finish the round when the queue is done. */
    private fun nextChirp() {
        if (role != ROLE_COMMANDER) return
        if (roundIndex >= roundQueue.size) { finishRanging(roundAll); return }
        val l = roundQueue[roundIndex]
        reportsForCurrent = 0
        triggerChirp(l)
        main.postDelayed({
            HLog.d("Ranging: chirp $l reported by $reportsForCurrent of ${roundAll.size} phones after ${CHIRP_TIMEOUT_MS / 1000} s, moving on")
            advanceChirp()
        }, chirpToken, CHIRP_TIMEOUT_MS)
    }

    private fun advanceChirp() {
        main.removeCallbacksAndMessages(chirpToken)
        roundIndex++
        main.postDelayed({ nextChirp() }, chirpToken, CHIRP_SETTLE_MS)
    }

    /** Commander: tell [letter] to chirp now; everyone (including us) listens for it. */
    private fun triggerChirp(letter: String) {
        chirpTriggerMs[letter] = SystemClock.elapsedRealtime()
        HLog.d("Ranging: trigger CHIRP $letter")
        synchronized(chirpTimesMs) { chirpTimesMs.addLast(chirpTriggerMs[letter]!!); while (chirpTimesMs.size > 20) chirpTimesMs.removeFirst() }
        link?.sendDown(Command(Command.CHIRP, letter = letter).toJson())
        onChirpCommand(letter)
    }

    /** Every phone: a chirp from [letter] is about to happen. Play it if it is ours, and search for it. */
    private fun onChirpCommand(letter: String) {
        val cap = capture ?: return
        val fs = AudioCapture.SAMPLE_RATE.toLong()
        val now = cap.samplesCaptured
        // Search from 2.5 s before the command (but after the previous chirp we heard) to 2.5 s after it.
        // ...and at least 1 s after the previous chirp this phone heard: its tail and echoes won weak searches 0.34–0.78 s
        // after it once chirps came every 2.4 s (27 Sep 08:18, 0.3 s here); with 3.4 s slots the window never reached there.
        val startSample = maxOf(now - fs * SEARCH_BEFORE_MS / 1000, if (lastChirpDetectedSample > 0) lastChirpDetectedSample + fs else 0L)
        val endSample = now + fs * SEARCH_AFTER_MS / 1000
        lastChirpMs = SystemClock.elapsedRealtime()
        if (letter == this.letter) appContext?.let { com.hush.audio.Chirp.play(it) }
        val count = (endSample - startSample).toInt()
        main.postDelayed(Runnable {
            Thread {
                val audio = cap.snapshot(startSample, count)
                if (audio == null) { HLog.d("Chirp search: audio for $letter not in buffer"); return@Thread }
                val t0 = SystemClock.elapsedRealtime()
                val det = com.hush.audio.Chirp.detect(audio, count)
                val at = startSample + det.offset
                if (det.offset >= 0 && det.ratio >= 5f) lastChirpDetectedSample = at
                HLog.d("Chirp from $letter heard by ${this.letter}: offset=${det.offset} sample=$at peak=%.2f ratio=%.1f firstArrivalShift=%d (%d ms)".format(det.peak, det.ratio, det.firstArrivalShift, SystemClock.elapsedRealtime() - t0))
                if (det.offset < 0 || det.ratio < 5f) { HLog.d("Chirp from $letter: not credible, dropped"); return@Thread }
                if (letter == this.letter) {
                    // Own chirp: log its envelope profile on both mics (which self-peak is the sound leaving the speaker?).
                    val a1 = if (cap.stereo) cap.snapshot(startSample, count, 1) else null
                    HLog.d("Chirp self-profile mic0: ${com.hush.audio.Chirp.envelopePeaks(audio, count, det.offset)}" +
                        (a1?.let { " | mic1: ${com.hush.audio.Chirp.envelopePeaks(it, count, det.offset)}" } ?: ""))
                }
                // Second mic: the delay of the SAME arrival on mic 1 (matched-filter outputs of both mics
                // cross-correlated around the direct arrival found on mic 0), for the direction of arrival.
                var micDelay: Float? = null
                if (cap.stereo && letter != this.letter) {
                    val a2 = cap.snapshot(startSample, count, 1)
                    if (a2 != null) {
                        val md = com.hush.audio.Chirp.micDelay(audio, a2, count, det.offset)
                        if (md != null && md.margin >= MIC_DELAY_MIN_MARGIN && kotlin.math.abs(md.samples) <= MIC_DELAY_MAX) {
                            micDelay = md.samples.toFloat()
                            HLog.d("Chirp from $letter mic delay: %.2f samples (mic1 − mic0), margin=%.2f sim=%.2f, heading=%.0f".format(micDelay, md.margin, md.similarity, headingDeg))
                        } else {
                            HLog.d("Chirp from $letter: second mic not usable (delay %s, margin %s, sim %s: %s)".format(
                                md?.samples?.let { "%.1f".format(it) } ?: "-", md?.margin?.let { "%.2f".format(it) } ?: "-", md?.similarity?.let { "%.2f".format(it) } ?: "-",
                                if (md == null) "no audio" else if (md.margin < MIC_DELAY_MIN_MARGIN) "ambiguous carrier cycle" else "beyond the mic spacing"))
                        }
                    }
                }
                // Received level of the chirp itself (RMS over the template length at the detected start).
                var level = 0.0
                val m = com.hush.audio.Chirp.template.size
                for (i in det.offset until minOf(det.offset + m, count)) { val v = audio[i] / 32768.0; level += v * v }
                val rmsLevel = kotlin.math.sqrt(level / m).toFloat()
                val report = com.hush.model.ChirpReport(this.letter, letter, at, det.ratio, micDelay, headingDeg, rmsLevel)
                main.post {
                    if (role == ROLE_COMMANDER) onChirpReport(report) else link?.sendUp(report.toJson())
                }
            }.start()
        }, chirpToken, SEARCH_AFTER_MS + 100L)
    }

    /** Commander's own two-mic delays this round: chirping letter → (delay samples, heading). */
    private val doaThisRound = HashMap<String, Pair<Float, Float>>()
    /** Every phone's two-mic delays this round: hearer → chirping letter → (delay, hearer's heading). For the locator's mic axes. */
    private val doaAll = HashMap<String, HashMap<String, Pair<Float, Float>>>()
    /** level[hearer][from] = received chirp RMS this round, for mic calibration. */
    private val chirpLevel = HashMap<String, HashMap<String, Float>>()
    /** Mic gain of each sensor relative to the commander (1.0 = same). Loudness is divided by this. */
    val micGain = HashMap<String, Float>()

    private fun onChirpReport(r: com.hush.model.ChirpReport) {
        heard.getOrPut(r.hearer) { HashMap() }[r.from] = r.sample
        chirpTriggerMs[r.from]?.let { heardTrig.getOrPut(r.hearer) { HashMap() }[r.from] = it }
        if (rangingInProgress && roundIndex < roundQueue.size && r.from == roundQueue[roundIndex]) {
            reportsForCurrent++
            if (reportsForCurrent >= roundAll.size) { HLog.d("Ranging: chirp ${r.from} reported by all ${roundAll.size} phones"); advanceChirp() }
        }
        if (r.level != null) chirpLevel.getOrPut(r.hearer) { HashMap() }[r.from] = r.level
        HLog.d("Chirp report: ${r.hearer} heard ${r.from} at ${r.sample} ratio=%.1f level=%s".format(r.ratio, r.level?.let { "%.5f".format(it) } ?: "-"))
        if (r.hearer == "A" && r.micDelay != null && r.heading != null) doaThisRound[r.from] = r.micDelay to r.heading
        if (r.micDelay != null && r.heading != null) doaAll.getOrPut(r.hearer) { HashMap() }[r.from] = r.micDelay to r.heading
    }

    /**
     * Mic calibration from the chirps: for one chirp source j, received level × distance should be the same
     * on every phone. The ratio to the commander's value is that phone's gain. Median over sources and rounds.
     */
    private val gainHistory = HashMap<String, ArrayList<Float>>()

    private fun computeMicGains(letters: List<String>, dist: Map<String, Double>) {
        fun d(i: String, j: String): Double? = dist["$i$j"] ?: dist["$j$i"]
        for (i in letters) {
            if (i == "A") continue
            val samples = ArrayList<Float>()
            for (j in letters) {
                if (j == i || j == "A") continue
                val li = chirpLevel[i]?.get(j) ?: continue
                val la = chirpLevel["A"]?.get(j) ?: continue
                val di = d(i, j) ?: continue
                val da = d("A", j) ?: continue
                if (li <= 0f || la <= 0f || di <= 0.05 || da <= 0.05) continue
                samples.add(((li * di) / (la * da)).toFloat())
            }
            // Also use the commander's chirp heard at i versus the commander hearing i's chirp (reciprocal path).
            val lia = chirpLevel[i]?.get("A"); val lai = chirpLevel["A"]?.get(i)
            if (lia != null && lai != null && lia > 0f && lai > 0f) samples.add(lia / lai)
            if (samples.isEmpty()) continue
            val h = gainHistory.getOrPut(i) { ArrayList() }
            h.addAll(samples)
            while (h.size > 12) h.removeAt(0)
            val g = h.sorted()[h.size / 2].coerceIn(0.2f, 5f)
            micGain[i] = g
            HLog.d("Mic gain: sensor $i = %.2f× commander (from %d samples)".format(g, h.size))
        }
        chirpLevel.clear()
    }

    /**
     * Direction of arrival on the commander: the inter-mic delay of each sensor's chirp gives the angle
     * from the mic axis; the ranged triangle tells the true angle between the sensors, which fixes the
     * unknown mic spacing and the left/right mirror. Result: map rotation to north with nobody moving.
     */
    /** Mic spacing solved in earlier rounds; the median is held so one bad round cannot flip north. */
    private val micSpacingHistory = ArrayList<Double>()
    private val rotationHistory = ArrayList<Float>()

    private fun alignFromDoa(a: String, b: String, c: String, dAB: Double, dAC: Double, dBC: Double) {
        val db = doaThisRound[b] ?: return
        val dc = doaThisRound[c] ?: return
        // Too close and the angle is unreliable: skip such rounds (limit shared with the locator's axes).
        val near = com.hush.Locator.NEAR_FIELD
        if (dAB < near || dAC < near) { HLog.d("DoA align: phones too close (AB %.2f, AC %.2f m, limit %.1f m), skipped".format(dAB, dAC, near)); return }
        // True angle at A between B and C from the triangle.
        val cosG = ((dAB * dAB + dAC * dAC - dBC * dBC) / (2 * dAB * dAC)).coerceIn(-1.0, 1.0)
        val gamma = Math.toDegrees(kotlin.math.acos(cosG))
        val fs = com.hush.audio.Chirp.SAMPLE_RATE.toDouble(); val cs = com.hush.audio.Chirp.SPEED_OF_SOUND.toDouble()
        // Try candidate mic spacings and both mirror choices; keep the one whose DoA angles match gamma.
        var bestErr = 1e9; var bestD = 0.0; var bestMirrorC = false; var bestThB = 0.0; var bestThC = 0.0
        // Once the spacing is known from a few rounds, hold it (median) instead of re-solving.
        val held = if (micSpacingHistory.size >= 3) micSpacingHistory.sorted()[micSpacingHistory.size / 2] else null
        var d = held ?: 0.06
        val dEnd = held ?: 0.20
        while (d <= dEnd + 1e-9) {
            val maxDelay = d * fs / cs
            val cb = (db.first / maxDelay).coerceIn(-1.0, 1.0); val cc = (dc.first / maxDelay).coerceIn(-1.0, 1.0)
            val thB = Math.toDegrees(kotlin.math.acos(cb)); val thC = Math.toDegrees(kotlin.math.acos(cc))
            for (mirrorC in listOf(false, true)) {
                val angleBetween = kotlin.math.abs(thB - (if (mirrorC) -thC else thC))
                val err = kotlin.math.abs(angleBetween - gamma) + 200.0 * kotlin.math.abs(d - 0.14)   // prefer plausible spacing
                if (err < bestErr) { bestErr = err; bestD = d; bestMirrorC = mirrorC; bestThB = thB; bestThC = if (mirrorC) -thC else thC }
            }
            d += 0.005
        }
        if (bestErr > 25.0) { HLog.d("DoA align: no consistent solution (err %.1f°, gamma %.1f°)".format(bestErr, gamma)); return }
        // Angles are measured from the mic axis (phone top). The sign of the mic order is unknown, so two
        // world bearings are possible; the ranged map decides which by comparing map bearings.
        val mapB = mapBearing(a, b) ?: return; val mapC = mapBearing(a, c) ?: return
        var chosenRot = 0f; var chosenSpread = 1e9
        for (signFlip in listOf(1.0, -1.0)) {
            val wb = (db.second + signFlip * bestThB + 720.0) % 360.0
            val wc = (dc.second + signFlip * bestThC + 720.0) % 360.0
            for (mir in listOf(false, true)) {
                val mb = if (mir) (360.0 - mapB) % 360.0 else mapB.toDouble()
                val mc = if (mir) (360.0 - mapC) % 360.0 else mapC.toDouble()
                val r1 = (wb - mb + 720.0) % 360.0; val r2 = (wc - mc + 720.0) % 360.0
                var diff = kotlin.math.abs(r1 - r2); if (diff > 180) diff = 360 - diff
                if (diff < chosenSpread) {
                    chosenSpread = diff
                    val rr = Math.toRadians(r1); val ss = Math.toRadians(r2)
                    chosenRot = ((Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(rr) + kotlin.math.sin(ss), kotlin.math.cos(rr) + kotlin.math.cos(ss))) + 360.0) % 360.0).toFloat()
                    if (mir != mirror) { mirror = mir }
                }
            }
        }
        HLog.d("DoA align: spacing %.3f m, thetaB %.0f° thetaC %.0f° (gamma %.0f°, err %.1f°), rotation %.0f° (spread %.0f°, mirror=%b)".format(bestD, bestThB, bestThC, gamma, bestErr, chosenRot, chosenSpread, mirror))
        if (chosenSpread <= 30.0) {
            micSpacingHistory.add(bestD)
            rotationHistory.add(chosenRot)
            // Smooth over the last few rounds (circular mean) so a single odd round cannot swing the arrow.
            val recent = rotationHistory.takeLast(5).map { Math.toRadians(it.toDouble()) }
            val smoothed = ((Math.toDegrees(kotlin.math.atan2(recent.map { kotlin.math.sin(it) }.average(), recent.map { kotlin.math.cos(it) }.average())) + 360.0) % 360.0).toFloat()
            if (pointedHeading.isNotEmpty()) { HLog.d("DoA align: %.0f° not used, north comes from pointing".format(smoothed)); return }
            mapRotationDeg = smoothed
            alignSource = "two-mic direction (±%.0f°, %d rounds)".format(chosenSpread / 2, rotationHistory.size)
            setRangingStatus("Map aligned to north from chirp directions.")
        }
    }

    // Sensors B and C define the map frame (they do not move); the commander A moves inside it.
    // Map unit = metres × mapScale, centred on the sensors' midpoint. Fixed after the first ranging.
    private var frameB: String? = null
    private var frameC: String? = null
    private var frameDBC = 0.0
    private var lastAInFrame: Pair<Double, Double>? = null      // metres, B at origin, C on +x
    private var lastAFrameMs = 0L

    private fun finishRanging(letters: List<String>) {
        if (role != ROLE_COMMANDER) { HLog.d("Ranging: finish after stop, ignored"); return }
        rangingInProgress = false
        // Sanity: on any one phone's clock two chirps must be as far apart as their TRIGGER times were
        // (network delay and flight time are well under 0.35 s). A detection far off is a wrong peak:
        // drop it. Trigger times rather than "letter order × gap", so after a retry the re-chirped
        // letters (sent seconds later) are judged against their own trigger and earlier good pairs survive.
        val fs = AudioCapture.SAMPLE_RATE
        // Step 1, loose: a chirp cannot be heard more than 1.5 s away from where its trigger time puts it
        // (commands have been seen to reach a sensor seconds late, so this only catches gross errors).
        for ((hearer, byFrom) in heard) {
            val trig = heardTrig[hearer] ?: HashMap()
            val ref = letters.firstOrNull { byFrom.containsKey(it) && trig.containsKey(it) } ?: continue
            val tRef = byFrom[ref]!!; val trigRef = trig[ref]!!
            val bad = byFrom.filter { (from, t) ->
                val tr = trig[from] ?: return@filter true
                from != ref && kotlin.math.abs((t - tRef) - (tr - trigRef) * fs / 1000.0) > 1.5 * fs
            }.keys
            if (bad.isNotEmpty()) { HLog.d("Ranging: $hearer had grossly wrong peaks for $bad, dropped"); bad.forEach { byFrom.remove(it); trig.remove(it) } }
        }
        // Step 2, tight and clock-free: the gap between two chirps is the same on every hearer's clock to
        // within the flight-time difference (< 0.1 s for phones < 30 m apart). A hearer whose gap disagrees
        // with the majority picked a wrong peak (echo, the neighbouring chirp); drop that hearer's value.
        val ref = letters.firstOrNull { l -> heard.values.count { it.containsKey(l) } >= 2 }
        if (ref != null) for (j in letters) {
            if (j == ref) continue
            val gaps = heard.mapNotNull { (h, byFrom) -> val a = byFrom[ref]; val b = byFrom[j]; if (a != null && b != null) h to (b - a) else null }
            if (gaps.size < 3) continue
            val median = gaps.map { it.second }.sorted()[gaps.size / 2]
            for ((h, g) in gaps) if (kotlin.math.abs(g - median) > 0.1 * fs) {
                HLog.d("Ranging: $h heard $j %.0f ms off the other phones (gap to $ref), dropped".format((g - median) * 1000.0 / fs))
                heard[h]?.remove(j)
            }
        }
        val dist = HashMap<String, Double>()
        for (i in letters.indices) for (j in i + 1 until letters.size) {
            val d = Ranging.pairDistance(heard, letters[i], letters[j])
            if (d != null) dist["${letters[i]}${letters[j]}"] = d
        }
        val text = dist.entries.joinToString("  ") { "%s %.2f m".format(it.key, it.value) }
        HLog.d("RANGING result: $text  (heard=$heard)")
        sessionLog.addRecord("ranging", mapOf("distances" to text))
        computeMicGains(letters, dist)
        // Retry once: if a pair is missing, re-chirp the letters involved before giving up on this round.
        if (letters.size >= 3 && !retriedThisRound) {
            val missing = letters.flatMapIndexed { i, x -> letters.drop(i + 1).filter { y -> dist["$x$y"] == null }.map { y -> x to y } }
            if (missing.isNotEmpty() && missing.size < 3) {
                retriedThisRound = true
                rangingInProgress = true
                val again = missing.flatMap { listOf(it.first, it.second) }.distinct()
                HLog.d("Ranging: pairs $missing missing, re-chirping $again")
                startChirpSequence(again, letters)
                return
            }
        }
        retriedThisRound = false
        // The same chirps measure how each sensor's audio clock differs from ours (needed to time knocks). With tape
        // positions the tape distances are used (chirp ranging read 0.78 m for a 0.40 m pair, 27 Sep 00:30), and the
        // round is logged against the tape, to learn whether the inaudible chirp could replace the tape (step 4).
        val clockDist = if (handPositions()) tapeDistances(letters) else correctedChirp(letters, dist)
        if (handPositions()) {
            val cmp = clockDist.entries.joinToString("  ") { (k, v) -> "%s tape %.2f chirp %s".format(k, v, dist[k]?.let { "%.2f".format(it) } ?: "-") }
            HLog.d("RANGING vs tape: $cmp")
            sessionLog.addRecord("ranging_vs_tape", mapOf("pairs" to cmp))
        }
        try { locator.updateClocks(heard, clockDist, letters) } catch (e: Exception) { HLog.d("ERROR in clock update: $e") }
        sendClockRoundDown(letters, clockDist)
        if (handPositions()) {
            clockRoundsDone++
            val missing = letters.filter { it != "A" && !locator.hasClock(it) }
            setRangingStatus("Clocks: inaudible chirp round $clockRoundsDone, " + (if (missing.isEmpty()) "all phones timed" else "no clock yet for ${missing.joinToString(" ")}") +
                (if (rangeBias != null) " · positions from the chirps" else if (autoLocateRounds > 0) " · calibrating the chirp distances" else ""))
            try { autoLocateStep(letters, dist) } catch (e: Exception) { HLog.d("ERROR in auto-locate: $e") }
            if (pendingHushSeconds > 0) { val s = pendingHushSeconds; pendingHushSeconds = 0; broadcastHush(s) }
            return
        }
        // No tape: the chirps place the phones (AutoLocate), and the rounds after that follow them (autoLocateStep).
        if (chirpFirstPlacement(letters, dist)) return
        // Fuse with the silent radio baseline: a chirp pair that disagrees with Bluetooth by > 30 % is dropped.
        for (key in dist.keys.toList()) {
            val radio = radioDistance[key] ?: radioDistance[key.reversed()] ?: continue
            val d = dist[key]!!
            if (kotlin.math.abs(d - radio) / maxOf(radio, 0.3) > 0.30) {
                HLog.d("Ranging: chirp %s %.2f m disagrees with radio %.2f m, dropped".format(key, d, radio))
                dist.remove(key)
            }
        }
        // Fill gaps from radio so the map can still be built silently.
        for (i in letters.indices) for (j in i + 1 until letters.size) {
            val key = "${letters[i]}${letters[j]}"
            if (dist[key] == null) (radioDistance[key] ?: radioDistance[key.reversed()])?.let { dist[key] = it; HLog.d("Ranging: $key from radio %.2f m".format(it)) }
        }
        var placed = false
        if (letters.size >= 3) {
            val a = letters[0]; val b = letters[1]; val c = letters[2]
            val dAB = dist["$a$b"]; val dAC = dist["$a$c"]; val dBC = dist["$b$c"]
            // A round is only usable if the three distances make a real triangle and are plausible.
            val sane = dAB != null && dAC != null && dBC != null && dBC > 0.05 &&
                maxOf(dAB, dAC, dBC) <= 30.0 &&
                dAB <= dAC + dBC + 0.15 && dAC <= dAB + dBC + 0.15 && dBC <= dAB + dAC + 0.15
            if (dAB != null && dAC != null && dBC != null && !sane) HLog.d("Ranging: impossible triangle, round discarded")
            // Two consistent rounds (each pair within 20 %) before the map moves; a lone round only gets logged.
            val consistent = sane && lastRoundDist != null && listOf("$a$b" to dAB!!, "$a$c" to dAC!!, "$b$c" to dBC!!).all { (k, v) ->
                val prev = lastRoundDist!![k] ?: return@all false
                kotlin.math.abs(v - prev) / maxOf(prev, 0.3) <= 0.20
            }
            if (sane) lastRoundDist = HashMap(dist)
            if (sane && !consistent) { HLog.d("Ranging: first or inconsistent round, waiting for a matching one before moving the map"); setRangingStatus("Ranging: $text (confirming…)") }
            if (sane && consistent) {
                dAB!!; dAC!!; dBC!!
                // A in the B–C frame: B=(0,0), C=(dBC,0).
                val x = (dAB * dAB - dAC * dAC + dBC * dBC) / (2 * dBC)
                val y2 = dAB * dAB - x * x
                val y = (if (y2 > 0) kotlin.math.sqrt(y2) else 0.0) * (if (mirror) -1 else 1)
                val aPos = x to y
                if (frameB != b || frameC != c || mapMetresPerUnit == null) {
                    frameB = b; frameC = c; frameDBC = dBC
                    mapMetresPerUnit = (maxOf(dAB, dAC, dBC) * 1.6).toFloat().coerceAtLeast(1f)   // span so the triangle fits with room to walk
                    lastAInFrame = null
                }
                val scale = 1.0 / mapMetresPerUnit!!
                val cx = dBC / 2; val cy = 0.0
                fun toMap(p: Pair<Double, Double>) = ((0.5 + (p.first - cx) * scale).toFloat()) to ((0.5 - (p.second - cy) * scale).toFloat())
                mapDots[b] = toMap(0.0 to 0.0)
                mapDots[c] = toMap(dBC to 0.0)
                mapDots[a] = toMap(aPos)
                autoAlign(aPos)
                alignFromDoa(a, b, c, dAB, dAC, dBC)
                doaThisRound.clear()
                // Source locator: fresh positions, and each phone's mic line learnt from the chirps it heard.
                try {
                    if (micSpacingHistory.size >= 3) locator.micSpacing = micSpacingHistory.sorted()[micSpacingHistory.size / 2]
                    syncLocatorPositions()
                    locator.calibrateAxes(doaAll, dist)
                } catch (e: Exception) { HLog.d("ERROR in locator setup: $e") }
                doaAll.clear()
                setRangingStatus("Placed by sound: $text")
                if (pointedHeading.isNotEmpty()) applyPointedAlign()
                listener?.onPeers(peers.values.toList())
                placed = true
            }
        }
        if (!placed) setRangingStatus(if (dist.isEmpty()) "Ranging failed: no chirps heard. Place manually." else "Ranging incomplete: $text. Place manually.")
        if (pendingHushSeconds > 0) {
            val s = pendingHushSeconds
            pendingHushSeconds = 0
            broadcastHush(s)
        }
    }

    // ---- Automatic alignment to north from the commander's own walking ----

    private var walkHeadingSinX = 0.0
    private var walkHeadingSinY = 0.0
    private var walkSamples = 0
    private var movedSinceRanging = 0f          // seconds of movement since the last ranging
    private var shakeNotWalkLogged = false
    private var alignSolutions = ArrayList<Pair<Float, Float>>()   // (rotation if not mirrored, rotation if mirrored)

    private var lastDrEast = 0f
    private var lastDrNorth = 0f

    /** Commander: between ranging rounds, move our own dot on the map by the steps we took (live arrow while walking). */
    private fun moveOwnDotByWalking() {
        if (gpsLayoutActive) return   // A's dot comes from its own GPS fix every 5 s
        val dr = deadReckoning ?: return
        val rot = mapRotationDeg ?: return
        val scale = mapMetresPerUnit ?: return
        val dE = dr.east - lastDrEast; val dN = dr.north - lastDrNorth
        lastDrEast = dr.east; lastDrNorth = dr.north
        val len = kotlin.math.hypot(dE, dN)
        if (len < 0.3f) return
        val a = mapDots["A"] ?: return
        // World bearing of the step → map bearing (clockwise from map-up) → unit-square delta.
        val worldB = Math.toDegrees(kotlin.math.atan2(dE.toDouble(), dN.toDouble()))
        val mapB = Math.toRadians(worldB - rot)
        val u = len / scale
        mapDots["A"] = (a.first + (kotlin.math.sin(mapB) * u).toFloat()) to (a.second - (kotlin.math.cos(mapB) * u).toFloat())
        syncLocatorPositions()
        listener?.onPeers(peers.values.toList())
    }

    /** Called once per second from the audio window on the commander: accumulate walking heading. */
    private fun trackWalking(acc: AccelChannel.Result) {
        if (role != ROLE_COMMANDER) return
        moveOwnDotByWalking()
        // Walking = the phone shakes AND the step detector counted a step in the last 2 s. Shaking alone is not
        // walking: knuckle knocks on the table the commander lies on shake it too (27 Sep: that re-ranged every 8 s).
        val stepMs = deadReckoning?.lastStepMs ?: 0L
        val stepped = stepMs > 0L && SystemClock.elapsedRealtime() - stepMs < 2000L
        if (acc.rmsHp > 0.25f && !stepped && deadReckoning?.available == true) {
            if (!shakeNotWalkLogged) { HLog.d("Walk: commander shaking without steps (knocks on its table?), not counted as walking"); shakeNotWalkLogged = true }
        }
        if (acc.rmsHp > 0.25f && (stepped || deadReckoning?.available != true)) {
            movedSinceRanging += 1f
            val h = Math.toRadians(headingDeg.toDouble())
            walkHeadingSinX += kotlin.math.cos(h); walkHeadingSinY += kotlin.math.sin(h); walkSamples++
        }
        // Re-range on its own after walking a few seconds, outside a window.
        if (!inHush && !rangingInProgress && movedSinceRanging >= 3f && peers.size >= 2 &&
            SystemClock.elapsedRealtime() - lastAFrameMs > 8000) {
            HLog.d("Auto re-ranging after %.0f s of walking".format(movedSinceRanging))
            if (!chirpCalibration) {
                HLog.d("Walk: commander walked %.0f s; chirp calibration off, no re-ranging".format(movedSinceRanging))
                movedSinceRanging = 0f
            } else {
                if (pointedHeading.isNotEmpty()) { HLog.d("ALIGN: the commander walked, pointing at ${pointedHeading.keys} forgotten"); pointedHeading.clear() }
                autoPlace()
            }
        }
    }

    private fun autoAlign(aPos: Pair<Double, Double>) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastAInFrame
        val walkBearing = if (walkSamples > 0) ((Math.toDegrees(kotlin.math.atan2(walkHeadingSinY, walkHeadingSinX)) + 360.0) % 360.0).toFloat() else null
        if (prev != null && walkBearing != null) {
            val dx = aPos.first - prev.first; val dy = aPos.second - prev.second
            val moved = kotlin.math.hypot(dx, dy)
            if (moved > 10.0) HLog.d("Auto-align: implausible move %.1f m between rounds, ignored".format(moved))
            if (moved in 0.5..10.0) {
                // Bearing of the move in the map frame (x right, y up): clockwise from up.
                val mapBearingNoMirror = ((Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0).toFloat()
                val mapBearingMirror = ((Math.toDegrees(kotlin.math.atan2(dx, -dy)) + 360.0) % 360.0).toFloat()
                val rotNo = ((walkBearing - mapBearingNoMirror) + 720f) % 360f
                val rotMi = ((walkBearing - mapBearingMirror) + 720f) % 360f
                alignSolutions.add(rotNo to rotMi)
                // Pick the mirror hypothesis whose rotation estimates agree across walks.
                fun spread(sel: (Pair<Float, Float>) -> Float): Float {
                    val xs = alignSolutions.map { Math.toRadians(sel(it).toDouble()) }
                    val mx = xs.map { kotlin.math.cos(it) }.average(); val my = xs.map { kotlin.math.sin(it) }.average()
                    return (1.0 - kotlin.math.hypot(mx, my)).toFloat()
                }
                fun mean(sel: (Pair<Float, Float>) -> Float): Float {
                    val xs = alignSolutions.map { Math.toRadians(sel(it).toDouble()) }
                    return ((Math.toDegrees(kotlin.math.atan2(xs.map { kotlin.math.sin(it) }.average(), xs.map { kotlin.math.cos(it) }.average())) + 360.0) % 360.0).toFloat()
                }
                val useMirror = alignSolutions.size >= 2 && spread { it.second } < spread { it.first }
                if (pointedHeading.isNotEmpty()) {
                    HLog.d("Auto-align: walk estimate %.0f° not used, north comes from pointing".format(if (useMirror) mean { it.second } else mean { it.first }))
                    return
                }
                if (useMirror != mirror && alignSolutions.size >= 2) {
                    HLog.d("Auto-align: switching mirror to $useMirror")
                    mirror = useMirror
                    // Re-place A with the other mirror sign next round; dots for this round stay.
                }
                mapRotationDeg = if (useMirror) mean { it.second } else mean { it.first }
                alignSource = "your walk (${alignSolutions.size})"
                HLog.d("Auto-align: moved %.2f m, walked bearing %.0f°, map rotation now %.0f° (mirror=%b, walks=%d)".format(moved, walkBearing, mapRotationDeg, mirror, alignSolutions.size))
                setRangingStatus("Aligned to north from your walk (%.1f m).".format(moved))
            }
        }
        lastAInFrame = aPos
        lastAFrameMs = now
        walkHeadingSinX = 0.0; walkHeadingSinY = 0.0; walkSamples = 0
        movedSinceRanging = 0f
        deadReckoning?.let { lastDrEast = it.east; lastDrNorth = it.north }   // ranging just fixed A; restart step tracking from here
    }

    private fun setRangingStatus(s: String) {
        rangingStatus = s
        HLog.d(s)
        listener?.onLinkStatus(lastStatus)   // nudges the screen to redraw; it reads rangingStatus
    }

    /** Commander: write the session log to Downloads. Returns the file name or null. */
    fun exportLog(): String? {
        val ctx = appContext ?: return null
        val sensors = org.json.JSONObject().apply { lettersByName.forEach { (name, letter) -> put(letter, name) } }
        val dots = org.json.JSONObject().apply { mapDots.forEach { (l, p) -> put(l, org.json.JSONObject().put("x", p.first.toDouble()).put("y", p.second.toDouble())) } }
        return sessionLog.export(ctx, mapOf("commander" to localName, "sensors" to sensors, "dots" to dots, "mode" to mode.name, "lastBrief" to lastBrief))
    }
    private var hushStartMs = 0L
    var lastRanking: List<Rank> = emptyList()
        private set
    var lastBrief: String = ""
        private set

    private fun evidence(e: SensorEvent): Float = when (mode) {
        Mode.TAPPING -> e.rhythmScore
        Mode.VOICE -> e.human
        Mode.ANY -> maxOf(e.rhythmScore, e.human)
    }

    /** Commander: every event, own or received, lands here. */
    private fun recordEvent(e: SensorEvent) {
        sessionLog.add(e.toJson())
        updateLive(e)
        val nowB = SystemClock.elapsedRealtime()
        val hadBearing = peerBearing.containsKey(e.sensorId)
        if (e.bearing != null) peerBearing[e.sensorId] = PeerBearing(e.bearing, e.bearingTwin, e.bearingQ ?: 0f, nowB, e.rhythmScore) else peerBearing.remove(e.sensorId)
        recordGps(e, nowB)
        if (e.sensorId == "A") { applyGpsLayout(nowB); fuseBearings(nowB, "A"); crossBearings(nowB) }
        else if (e.bearing != null || hadBearing) {
            // Relay latency (27 Sep 03:05, team: "the relay is not that fast"): a sensor's bearing used to wait for the
            // commander's own next second before it was fused and sent on. Fuse and send the moment it arrives.
            fuseBearings(nowB, e.sensorId)
            sendFixDown(false, nowB)
        }
        if (e.human >= 0.3f) {
            locator.addVoice(e.sensorId, maxOf(0f, e.rms - e.floor) / gainOf(e.sensorId), e.micDelay, e.micQ,
                if (e.sensorId == "A") headingDeg else (peerHeading[e.sensorId] ?: 0f), e.moving, SystemClock.elapsedRealtime())
        }
        if (e.sensorId == "A") runLocator()
        emitLive()
        val nowMs = SystemClock.elapsedRealtime()
        // A sensor's event for the window's last second arrives after the window ended: keep it (grace).
        if (!inHush && nowMs > hushGraceUntilMs) return
        // Skip the start buzz and beep: they rattle the phone and were once scored as tapping.
        if (nowMs < hushStartMs + SELF_NOISE_MS) return
        hushEvents.getOrPut(e.sensorId) { ArrayList() }.add(e)
    }

    private fun computeRanking() {
        val ranks = hushEvents.map { (letter, events) ->
            val gain = gainOf(letter)
            val scores = events.map { maxOf(0f, it.rms - it.floor) / gain * evidence(it) }.sortedDescending()
            val best = scores.take(5)
            val rhythm = events.mapNotNull { it.rhythm }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val tempos = events.filter { it.tempoMs > 0 && it.rhythmScore >= 0.9f }.map { it.tempoMs }
            Rank(
                letter = letter,
                score = if (best.isEmpty()) 0f else best.average().toFloat(),
                evidence = events.maxOfOrNull { evidence(it) } ?: 0f,
                rhythm = rhythm,
                windows = events.size,
                moving = events.count { it.moving } > events.size / 3,
                tempoMs = if (tempos.isEmpty()) 0 else tempos.sorted()[tempos.size / 2]
            )
        }.sortedByDescending { it.score }
        val sources = if (mode != Mode.VOICE) assignSources(ranks) else 0
        lastRanking = ranks
        lastBrief = "Window · " + (if (ranks.isEmpty()) "No sensor data in this window" else briefFor(ranks, sources))
        HLog.d("RANKING ($mode): " + ranks.joinToString(" | ") { "%s score=%.5f ev=%.2f rh=%s n=%d gain=%.2f".format(it.letter, it.score, it.evidence, it.rhythm, it.windows, gainOf(it.letter)) })
        HLog.d("BRIEF: $lastBrief")
        sessionLog.addRecord("ranking", mapOf(
            "mode" to mode.name,
            "brief" to lastBrief,
            "ranks" to org.json.JSONArray(ranks.map { r ->
                org.json.JSONObject().put("id", r.letter).put("score", r.score.toDouble()).put("evidence", r.evidence.toDouble())
                    .put("rhythm", r.rhythm ?: org.json.JSONObject.NULL).put("windows", r.windows).put("moving", r.moving)
            })
        ))
        listener?.onRanking(ranks, lastBrief)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile var role: String? = null
        private set
    @Volatile var letter: String = "?"
        private set
    @Volatile var listener: Listener? = null
        set(value) {
            field = value
            // Give a fresh screen the current state straight away.
            value?.let { l ->
                main.post {
                    l.onLinkStatus(lastStatus)
                    l.onCountdown(if (inHush) secondsLeft() else -1)
                    if (role == ROLE_COMMANDER) {
                        l.onPeers(peers.values.toList())
                        if (lastRanking.isNotEmpty()) l.onRanking(lastRanking, lastBrief)
                    } else if (boardNames.isNotEmpty()) {
                        l.onPeers(boardPeers())
                        boardEvents.values.forEach { l.onEvent(it, boardNames[it.sensorId] ?: "") }
                        if (lastBrief.isNotEmpty()) l.onRanking(lastRanking, lastBrief)
                    }
                }
            }
        }

    private var appContext: Context? = null
    private var capture: AudioCapture? = null
    private var classifier: Classifier? = null
    private val tapDetector = TapDetector(AudioCapture.SAMPLE_RATE)
    private val rhythmTracker = RhythmTracker()
    private var accel: AccelChannel? = null
    private var lastStructureMs = 0L
    private var link: NearbyLink? = null
    private var localName = "?"
    private var lastStatus = "Not started"

    // Noise floor: rolling MEDIAN of this phone's last FLOOR_SECONDS quiet, still seconds.
    // A median cannot be dragged by a knock, a chirp or a buzz the way a mean over 3 s was.
    private val recentRms = ArrayDeque<Float>()
    private const val FLOOR_SECONDS = 30
    @Volatile private var floor = 0f
    private fun currentFloor(): Float = if (recentRms.isEmpty()) 0f else recentRms.sorted()[recentRms.size / 2]

    // ---- Live scoring (continuous, no need for anything to stay still) ----

    data class Live(
        var score: Float = 0f,       // decayed sum of quality × loudness-above-floor × evidence
        var evidence: Float = 0f,    // decayed best evidence
        var rhythm: String? = null,
        var tempoMs: Int = 0,
        var lastMs: Long = 0L,
        var moving: Boolean = false,
        var quietSeconds: Int = 0
    )
    private val live = HashMap<String, Live>()
    private const val LIVE_TAU_MS = 15_000f
    private var lastLiveEmitMs = 0L
    private var lastWindowEndMs = -100_000L

    private fun decay(dtMs: Long): Float = kotlin.math.exp(-dtMs / LIVE_TAU_MS).toFloat()

    /** Commander: fold one event into the live picture. Moving or self-noise seconds carry zero weight. */
    private fun updateLive(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val l = live.getOrPut(e.sensorId) { Live() }
        if (l.lastMs > 0) { val k = decay(now - l.lastMs); l.score *= k; l.evidence *= k }
        l.lastMs = now
        l.moving = e.moving
        val selfNoise = (inHush && now - hushStartMs < SELF_NOISE_MS) || now - lastChirpMs < CHIRP_QUIET_MS
        val quality = if (e.moving || selfNoise) 0f else 1f
        if (quality > 0f) {
            val gain = gainOf(e.sensorId)
            val ev = evidence(e)
            l.score += maxOf(0f, e.rms - e.floor) / gain * ev
            if (ev > l.evidence) l.evidence = ev
            if (e.rhythm != null) { l.rhythm = e.rhythm; l.tempoMs = e.tempoMs }
            l.quietSeconds++
        } else if (l.evidence < 0.3f) {
            l.rhythm = null
        }
    }

    /** Commander, once a second: publish the live ranking when no window is running. */
    private fun emitLive() {
        val now = SystemClock.elapsedRealtime()
        if (inHush || now - lastWindowEndMs < 8000 || now - lastLiveEmitMs < 1000) return
        lastLiveEmitMs = now
        val ranks = live.map { (letter, l) ->
            val k = decay(now - l.lastMs)
            Rank(letter, l.score * k, l.evidence * k, l.rhythm, l.quietSeconds, l.moving, l.tempoMs)
        }.sortedByDescending { it.score }
        if (ranks.isEmpty()) return
        val sources = if (mode != Mode.VOICE) assignSources(ranks) else 0
        lastRanking = ranks
        updateArrowTarget(ranks)
        lastBrief = "Live · " + briefFor(ranks, sources)
        listener?.onRanking(ranks, lastBrief)
    }

    /**
     * The phone the arrows point at when no source is located: the loudest one, with hysteresis so the arrows do not
     * flip between two similar phones. Another phone takes over only after being ≥ 3 dB (×1.41) above the current
     * target's score for 2 consecutive seconds. Needs some human evidence (≥ 0.3), else the target is dropped.
     */
    @Volatile var arrowTarget: String? = null
        private set
    private var challenger: String? = null
    private var challengerSeconds = 0

    private fun updateArrowTarget(ranks: List<Rank>) {
        val top = ranks.firstOrNull()?.takeIf { it.score > 0f && it.evidence >= 0.3f }
        val cur = arrowTarget
        if (top == null) {
            if (cur != null) HLog.d("TARGET: none (no human signal), was $cur")
            arrowTarget = null; challenger = null; challengerSeconds = 0; return
        }
        if (cur == null) { arrowTarget = top.letter; HLog.d("TARGET: ${top.letter} (score %.5f)".format(top.score)); return }
        if (top.letter == cur) { challenger = null; challengerSeconds = 0; return }
        val curScore = ranks.firstOrNull { it.letter == cur }?.score ?: 0f
        if (top.score >= 1.41f * curScore) {
            challengerSeconds = if (challenger == top.letter) challengerSeconds + 1 else 1
            challenger = top.letter
            if (challengerSeconds >= 2) {
                HLog.d("TARGET: $cur -> ${top.letter} (%.5f vs %.5f, %.1f dB, %d s)".format(top.score, curScore,
                    20f * kotlin.math.log10(top.score / curScore.coerceAtLeast(1e-9f)), challengerSeconds))
                arrowTarget = top.letter; challenger = null; challengerSeconds = 0
            }
        } else { challenger = null; challengerSeconds = 0 }
    }

    private fun briefFor(ranks: List<Rank>, sources: Int): String {
        val top = ranks.firstOrNull() ?: return "No sensor data"
        return briefCore(ranks, sources, top) + sourceText()
    }

    private fun briefCore(ranks: List<Rank>, sources: Int, top: Rank): String {
        return when {
            sources >= 2 -> (1..sources).joinToString("  |  ") { s ->
                val members = ranks.filter { it.source == s }
                val lead = members.first()
                val others = members.drop(1).joinToString(",") { it.letter }
                "Source $s: tapping ${lead.rhythm} %.1f/s · strongest at Sensor ${lead.letter}".format(1000f / lead.tempoMs.coerceAtLeast(1)) +
                    (if (others.isNotEmpty()) " (also $others)" else "")
            }
            top.evidence >= 0.9f && mode != Mode.VOICE && top.rhythm != null ->
                "Human tapping · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter} · rhythm ${top.rhythm}"
            top.evidence >= 0.9f && mode != Mode.VOICE ->
                "Human tapping · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter}"
            top.evidence >= 0.3f && mode != Mode.TAPPING ->
                "Human voice · ${(top.evidence * 100).toInt()}% · strongest at Sensor ${top.letter}"
            top.score > 0f -> "Weak signal · ${(top.evidence * 100).toInt()}% · loudest at Sensor ${top.letter}"
            else -> "No human signal detected"
        }
    }

    // Hush window
    @Volatile var inHush = false
        private set
    private var hushEndMs = 0L

    // Commander bookkeeping
    private val peers = LinkedHashMap<String, Peer>()         // endpointId → peer
    private val lettersByName = LinkedHashMap<String, String>() // advertised name → letter, survives reconnects

    val isRunning get() = role != null

    fun start(context: Context, newRole: String) {
        if (role != null) { HLog.d("Engine.start ignored, already running as $role"); return }
        appContext = context.applicationContext
        role = newRole
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "0000"
        localName = "${Build.MODEL}-${id.takeLast(4)}"
        val household = isHousehold(newRole)
        letter = if (newRole == ROLE_COMMANDER) "A" else if (household) "H" else "?"
        sessionLog = com.hush.log.SessionLog()
        HLog.d("Engine start role=$newRole name=$localName" + if (household) " (household: no radios, classifier on loud seconds only)" else "")
        if (newRole == ROLE_COMMANDER) try { loadPrefs(context) } catch (e: Exception) { HLog.d("ERROR loading prefs: $e") }
        // Positions come from the chirps now (team, 27 Sep: "stop caring about where the phones are set"): a stored tape
        // layout is not applied (a stale 0.40 m one on ef39 shrank the 1.2 m triangle to a third). --es layout still works.
        if (newRole == ROLE_COMMANDER && layoutByName.isNotEmpty()) { HLog.d("LAYOUT: stored tape layout $layoutByName ignored, positions come from the chirps"); layoutByName.clear() }
        loadMicGeometry(context)

        try {
            classifier = Classifier(context)
        } catch (e: Exception) {
            HLog.d("ERROR loading classifier: $e")
        }
        accel = AccelChannel(context).also { it.start() }
        if (newRole != ROLE_ALERT) {
            // The compass and step counter serve the arrow and the map; ALERT has neither. HOME (walk-to-triangulate) needs both.
            val cmp = com.hush.audio.Compass(context).also { it.start() }
            // No stored correction: the gyroscope heading starts from a fresh magnetic reading each time, so SYNC once per session.
            compassSynced = false
            compass = cmp
            deadReckoning = com.hush.audio.DeadReckoning(context, cmp).also { it.start() }
        }
        if (!household) gps = com.hush.audio.Gps(context).also { it.start() }
        // Probe the mics once (blocks ~0.6 s) before the main capture takes the microphone.
        HLog.d(com.hush.audio.MicProbe.run())
        if (!household && com.hush.net.BleRanging.available) {
            try { ble = com.hush.net.BleRanging(context, bleListener).also { it.start() } } catch (e: Throwable) { HLog.d("BleRanging init failed: $e") }
        }
        capture = AudioCapture(context, this).also {
            if (!household) it.debugWav = File(context.filesDir, "debug.wav")   // debug capture, see CLAUDE.md; not at home (all-day listening, private)
            it.start()
        }
        if (household) {
            classifierSkipped = 0; classifierRun = 0
            soundAlerts.reset(); homeAlerts.reset(); whistles.reset()
            if (newRole == ROLE_HOME) { setWalkTuning(); walk.reset(); walkState = walk.state }
            Alerting.init(context)
            Alerting.loadLibrary(library)
            soundAlerts.library = library
            return   // no mesh, no Bluetooth tag, no chirps: one phone on its own
        }
        // Build the chirp search tables now, so the first ranging round does not pay for it (4 s on 27 Sep).
        Thread({ try { com.hush.audio.Chirp.warmUp() } catch (e: Exception) { HLog.d("ERROR in chirp warm-up: $e") } }, "hush-chirp-warmup").start()
        link = NearbyLink(context, localName, this).also {
            if (newRole == ROLE_COMMANDER) it.startCommander() else it.startSensor()
        }
        if (newRole == ROLE_COMMANDER) ble?.scanForTags() else main.postDelayed(tagRefresh, 500)
        // First round 15 s after the role starts (sensors join within seconds), then back to back (AutoLocate) or every minute.
        if (newRole == ROLE_COMMANDER) main.postDelayed(clockTimer, 15_000L)
    }

    /** ALERT screen's TEST row and the laptop hook `--es alerttest DOORBELL`: the flash, buzz and notification without a sound. */
    fun alertTest(categoryName: String): String {
        val c = com.hush.audio.SoundAlerts.Category.values().firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
            ?: return "unknown category '$categoryName'; one of " + com.hush.audio.SoundAlerts.Category.values().joinToString { it.name }
        val ctx = appContext ?: return "engine not running"
        Alerting.init(ctx)
        Alerting.test(c, listener != null)
        listener?.onAlert(com.hush.audio.SoundAlerts.Alert(c, c.word + " (test)", "test", 1f, c.pattern, c.repeats, SystemClock.elapsedRealtime(), false))
        return "alert test $c fired"
    }

    // ---- Live captions (persona C): the phone's speech recogniser takes the microphone, our capture pauses ----

    private var captions: Captions? = null
    val captionsOn: Boolean get() = captions?.running == true

    fun setCaptions(on: Boolean): Boolean {
        val ctx = appContext ?: return false
        if (on) {
            if (captionsOn) return true
            // One microphone user at a time: stop our capture (the recogniser would get silence, or we would).
            capture?.stop(); capture = null
            HLog.d("CAPTIONS: own capture paused")
            val c = captions ?: Captions(ctx, { t, f -> listener?.onCaption(t, f) }, { s -> HLog.d("CAPTIONS status: $s"); listener?.onCaptionStatus(s) },
                { resumeCapture(ctx) }).also { captions = it }   // captions that stop on their own give the microphone back
            val ok = c.start()
            if (!ok) resumeCapture(ctx)
            return ok
        } else {
            captions?.stop()
            resumeCapture(ctx)
            return false
        }
    }

    private fun resumeCapture(ctx: Context) {
        if (capture != null || role == null) return
        capture = AudioCapture(ctx, this).also { it.start() }
        HLog.d("CAPTIONS: own capture resumed")
    }

    /** Which side of the phone a knock came from, for the KNOCK word ("· left"). Needs the own arrow to have an estimate. */
    private fun knockSide(now: Long): String? {
        val e = com.hush.audio.KnockBearing.estimate(now, headingDeg) ?: return null
        fun side(deg: Float) = when (((deg % 360f) + 360f) % 360f) {
            in 45f..135f -> "right"
            in 135f..225f -> "below"
            in 225f..315f -> "left"
            else -> "above"
        }
        val a = side(e.screenDeg)
        val t = e.twinDeg?.let { side(it) }
        return if (t == null || t == a) a else "$a or $t"
    }

    /** Main thread, ALERT role: one second → maybe one household alert. */
    private fun alertSecond(w: Window, selfNoiseIn: Boolean, now: Long) {
        // The phone reading out a typed message is not a visitor, and its own vibration motor is heard as knocks.
        val selfNoise = selfNoiseIn || Speak.speaking || now - 1000 < com.hush.audio.Haptics.busyUntilMs
        val tap = w.tap
        val knocks = ArrayList<com.hush.audio.SoundAlerts.Knock>(tap.onsets.size)
        for (i in tap.onsets.indices) {
            val at = tap.onsetsAbsMs.getOrNull(i) ?: continue
            knocks.add(com.hush.audio.SoundAlerts.Knock(at, tap.onsets[i].ratio))
        }
        val second = com.hush.audio.SoundAlerts.Second(
            nowMs = now, rms = w.rms, floor = w.floor, moving = w.accel.moving, selfNoise = selfNoise,
            scores = w.cls?.scores ?: emptyMap(), topClass = w.cls?.topClass ?: "-", knocks = knocks)
        // Teaching a sound: the loud seconds go into the fingerprint instead of being judged.
        if (library.session != null) {
            val loud = if (w.floor > 0f) w.rms / w.floor else 0f
            val taught = library.feed(now, second.scores, loud)
            when {
                taught != null -> { Alerting.saveLibrary(library); listener?.onTeaching("Saved '${taught.name}'", true) }
                library.session == null -> { library.timedOut; listener?.onTeaching("Nothing loud enough heard in 30 s: not saved. Try again closer to the sound.", true) }
                else -> listener?.onTeaching(library.session!!.status, false)
            }
            return
        }
        var a = soundAlerts.onSecond(second) ?: return
        if (a.category == com.hush.audio.SoundAlerts.Category.KNOCK) knockSide(now)?.let { a = a.copy(word = a.word + " · $it") }
        Alerting.fire(a, listener != null)
        listener?.onAlert(a)
    }

    /** Main thread, HOME role: the whistle counter, plus kitchen timers and alarms. [tonal] is null on quiet seconds. */
    private fun homeSecond(w: Window, tonal: com.hush.audio.Tonality.Result?, selfNoiseIn: Boolean, now: Long) {
        val ctx = appContext ?: return
        val selfNoise = selfNoiseIn || Speak.speaking || now - 1000 < com.hush.audio.Haptics.busyUntilMs
        val loud = if (w.floor > 0f) w.rms / w.floor else 0f
        val ws = com.hush.audio.WhistleCounter.Second(now, w.cls?.sum(com.hush.audio.WhistleCounter.CLASSES) ?: 0f, loud,
            tonal?.peakToMedian ?: 0f, tonal?.peakHz ?: 0f, selfNoise)
        whistles.onSecond(ws)?.let { e ->
            HLog.d("WHISTLE ${e.kind} #${e.count} of ${e.target}: ${e.detail}")
            when (e.kind) {
                com.hush.audio.WhistleCounter.Kind.WHISTLE -> com.hush.audio.Haptics.vibrate(ctx, longArrayOf(0, 250), "Whistle buzz")
                com.hush.audio.WhistleCounter.Kind.DONE -> {
                    val c = com.hush.audio.SoundAlerts.Category.COOKER
                    Alerting.fire(com.hush.audio.SoundAlerts.Alert(c, "${e.count} WHISTLES · DONE", e.detail, 1f, c.pattern, false, now, false), listener != null)
                    // Beeps too: unlike rescue, noise is the point here. Three double beeps at a level the next room hears.
                    com.hush.audio.Ping.play(ctx, listOf(2000 to 250, 0 to 150, 2000 to 250, 0 to 500, 2000 to 250, 0 to 150, 2000 to 250, 0 to 500, 2000 to 250, 0 to 150, 2000 to 250), DONE_BEEP_FRACTION)
                }
                com.hush.audio.WhistleCounter.Kind.STALE -> {
                    val c = com.hush.audio.SoundAlerts.Category.COOKER
                    Alerting.fire(com.hush.audio.SoundAlerts.Alert(c, "CHECK THE COOKER", e.detail, 1f, longArrayOf(0, 400, 200, 400), false, now, false), listener != null)
                }
            }
            listener?.onWhistle(e)
        }
        // Timers and alarms, with the HOME switch set.
        val second = com.hush.audio.SoundAlerts.Second(now, w.rms, w.floor, w.accel.moving, selfNoise, w.cls?.scores ?: emptyMap(), w.cls?.topClass ?: "-", emptyList())
        homeAlerts.onSecond(second)?.let { a -> Alerting.fire(a, listener != null); listener?.onAlert(a) }
        // FIND: where the walker stands (steps + compass since FIND started), what the own arrow says here, how loud it is.
        val dr = deadReckoning
        val levelDb = if (w.floor > 0f && w.rms > w.floor) (20.0 * Math.log10((w.rms / w.floor).toDouble())).toFloat() else null
        walkState = walk.onSecond(now, dr?.east?.toDouble() ?: 0.0, dr?.north?.toDouble() ?: 0.0, w.accel.moving, homeArrow(), levelDb)
    }

    fun stop() {
        if (role == null) { HLog.d("Engine stop: already stopped"); return }
        HLog.d("Engine stop")
        captions?.stop(); captions = null; Speak.shutdown(); Torch.stop()
        capture?.stop(); capture = null
        accel?.stop(); accel = null
        compass?.stop(); compass = null
        deadReckoning?.stop(); deadReckoning = null
        gps?.stop(); gps = null
        placements.clear(); alignSource = ""; lastPlacementSentSteps = -1; stillSeconds = 0
        mapRotationDeg = null
        pointedHeading.clear()
        receivedFix = null; receivedFixMs = 0L; lastFixSentMs = 0L; lastFixKey = null
        arrowTarget = null; challenger = null; challengerSeconds = 0
        frameB = null; frameC = null; lastAInFrame = null; alignSolutions.clear(); mapMetresPerUnit = null
        pendingHushSeconds = 0
        classifier?.close(); classifier = null
        link?.stop(); link = null
        ble?.stop(); ble = null
        radioDistance.clear(); peerBleAddress.clear(); radioStatus = ""
        locator.reset(); peerHeading.clear(); doaAll.clear(); closest.reset(); closestText = ""; closestHeardUntil.clear(); closestHeardAt.clear(); wherePoint = null; wherePointKey = ""; warmth.reset(); aim.reset(); closestVoice.reset(); warmthVoice.reset(); synchronized(chirpTimesMs) { chirpTimesMs.clear() }; knockDir.reset(); knockDirKey = ""; knockDirResult = null; warmTrendLogged.clear(); lastWarmKey = ""; warmthByLetter = emptyMap()
        com.hush.audio.KnockBearing.reset(); ownArrowUntilMs = 0L
        soundAlerts.reset(); homeAlerts.reset(); whistles.reset(); classifierRun = 0; classifierSkipped = 0
        walk.reset(); walkState = walk.state; com.hush.audio.KnockBearing.rescueTuning()
        peerBearing.clear(); crossFix = null; crossFixMs = 0L; crossNoneLoggedMs = 0L
        sharedBearing = null; sharedBearingMs = 0L; sharedLogged = ""; sharedHist.fill(0.0); sharedHistMs = 0L; synchronized(loudOnsetMs) { loudOnsetMs.clear() }
        gpsSamples.clear(); gpsLayoutActive = false; gpsLayoutCheckMs = 0L; gpsLayoutReason = ""; gpsPending = null
        sightings.clear(); probeStatus = ""; activatedByProbe = false
        main.removeCallbacks(passiveWatchdog); main.removeCallbacks(tagRefresh); main.removeCallbacksAndMessages(probeToken)
        com.hush.net.Probe.stop()
        // Ranging and map: nothing from this session may leak into the next one.
        main.removeCallbacksAndMessages(chirpToken)
        rangingInProgress = false; retriedThisRound = false
        roundQueue = emptyList(); roundAll = emptyList(); roundIndex = 0; reportsForCurrent = 0; lastChirpDetectedSample = -1L
        heard.clear(); heardTrig.clear(); chirpTriggerMs.clear(); chirpLevel.clear(); doaThisRound.clear(); lastRoundDist = null
        micGain.clear(); gainHistory.clear(); micSpacingHistory.clear(); rotationHistory.clear(); radioShown.clear()
        mapDots.clear(); rangingStatus = ""; mirror = false
        walkHeadingSinX = 0.0; walkHeadingSinY = 0.0; walkSamples = 0; movedSinceRanging = 0f; lastAFrameMs = 0L
        lastDrEast = 0f; lastDrNorth = 0f
        lastWindowEndMs = -100_000L; lastLiveEmitMs = 0L; lastChirpMs = -10_000L; lastStructureMs = 0L; hushGraceUntilMs = 0L
        warnedUnassigned = false
        peers.clear()
        synchronized(audioLock) { recentRms.clear(); rhythmTracker.reset() }
        hushEvents.clear()
        live.clear()
        boardEvents.clear(); boardEventMs.clear(); boardNames.clear(); boardStatus = ""; boardDiscovered = ""; lastBoardMs = 0L; boardLoggedT.clear()
        boardPos.clear(); boardScale = null; boardRot = null; placedByHand = false; clockRoundsDone = 0; lastSyncRef = null
        rangeBias = null; lastBiasRound = null; autoLocateRounds = 0
        main.removeCallbacks(clockTimer)
        lastRanking = emptyList()
        lastBrief = ""
        inHush = false
        role = null
        letter = "?"
        lastStatus = "Not started"
    }

    // ---- Hush window ----

    /** Commander: start a Hush window on every phone including this one. */
    fun hush(seconds: Int = 20): Boolean {
        if (role == ROLE_SENSOR) return requestUp(if (allowSoloHush) com.hush.model.Request.HUSH_SOLO else com.hush.model.Request.HUSH)
        if (role != ROLE_COMMANDER) return false
        if (peers.isEmpty() && !allowSoloHush) {
            HLog.d("HUSH refused: no sensors connected")
            setRangingStatus("No sensors connected yet. Wait for them, or long-press HUSH to run alone.")
            return false
        }
        HLog.d("HUSH pressed: $seconds s to ${peers.size} sensors")
        sessionLog.addRecord("hush", mapOf("seconds" to seconds, "sensors" to peers.size + 1, "mode" to mode.name))
        // One button: range first (chirps, ~8 s) so the map is fresh, then run the window.
        if (chirpCalibration && peers.size >= 2 && !rangingInProgress) {
            pendingHushSeconds = seconds
            listener?.onCountdown(99)   // screen shows "ranging…" until the real countdown starts
            autoPlace()
        } else {
            broadcastHush(seconds)
        }
        return true
    }

    private var pendingHushSeconds = 0
    private val chirpToken = Any()

    /** Commander: stop everything running — the window on every phone and any chirp sequence. */
    fun stopAll() {
        if (role == ROLE_SENSOR) { requestUp(com.hush.model.Request.STOP); return }
        if (role != ROLE_COMMANDER) return
        HLog.d("STOP pressed")
        main.removeCallbacksAndMessages(chirpToken)
        rangingInProgress = false
        pendingHushSeconds = 0
        inHush = false
        link?.sendDown(Command(Command.STOP).toJson())
        listener?.onCountdown(-1)
        setRangingStatus("Stopped.")
        sessionLog.addRecord("stop", emptyMap())
    }

    private fun broadcastHush(seconds: Int) {
        link?.sendDown(Command(Command.HUSH, seconds).toJson())
        startHushLocal(seconds)
    }

    /** Set by a long press on HUSH: run a window with only this phone (single-phone testing). */
    @Volatile var allowSoloHush = false

    /** The advertised name of this phone, e.g. "I2501-6a46". Shown next to the letter so people can match phones. */
    val name: String get() = localName

    /** Start buzz (3 pulses ≈ 2.6 s) + beep: nothing heard in this period counts as tapping or is scored. */
    private const val SELF_NOISE_MS = 3500L
    @Volatile private var lastChirpMs = -10_000L

    private fun startHushLocal(seconds: Int) {
        synchronized(audioLock) {
            floor = currentFloor()
            rhythmTracker.reset()   // forget the chirps and anything before the window
        }
        inHush = true
        hushGraceUntilMs = 0L
        hushStartMs = SystemClock.elapsedRealtime()
        hushEndMs = hushStartMs + seconds * 1000L
        hushEvents.clear()
        HLog.d("Hush window started: $seconds s, noise floor %.5f".format(floor))
        vibrate(longArrayOf(0, 700, 150, 700, 150, 900))   // three long full-strength pulses at the start
        appContext?.let { Ping.play(it, listOf(2500 to 250, 0 to 100, 2500 to 250)) }
        tick()
    }

    private fun secondsLeft(): Int = ceil((hushEndMs - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)

    private fun tick() {
        if (!inHush) return
        val left = secondsLeft()
        if (left <= 0) {
            inHush = false
            HLog.d("Hush window ended")
            lastWindowEndMs = SystemClock.elapsedRealtime()
            hushGraceUntilMs = lastWindowEndMs + 1500   // recordEvent still accepts the last second's events
            vibrate(longArrayOf(0, 400, 120, 400))            // two pulses at the end
            appContext?.let { Ping.play(it, listOf(1800 to 400)) }
            listener?.onCountdown(-1)
            // Give the last sensor events a moment to arrive, then rank.
            if (role == ROLE_COMMANDER) main.postDelayed({ computeRanking() }, 1500)
        } else {
            listener?.onCountdown(left)
            main.postDelayed({ tick() }, 250)
        }
    }

    /** Hush window start/end pattern, see [com.hush.audio.Haptics]. */
    private fun vibrate(pattern: LongArray) {
        appContext?.let { com.hush.audio.Haptics.vibrate(it, pattern) }
    }

    // ---- Own microphone ----

    /** Consecutive seconds in which the microphone delivered exact digital zeros (Android silenced it). */
    private var silentSeconds = 0

    override fun onWindow(pcm48k: ShortArray, n48: Int, startSample: Long, pcm16k: FloatArray, n16: Int, rms: Float) {
        val now = SystemClock.elapsedRealtime()
        // A real microphone is never exactly zero. Android hands an app pure zeros when it may not record: its
        // screen was off when the role started, so the listening service never became a foreground service,
        // and a few seconds after the screen went away the recording was silenced (ef39, 27 Sep 23:38:12,
        // every chirp then "ratio=0.0"). Say so loudly instead of listening to nothing.
        var nonZero = false
        for (i in 0 until n48) if (pcm48k[i].toInt() != 0) { nonZero = true; break }
        if (!nonZero) {
            silentSeconds++
            if (silentSeconds == 3 || silentSeconds % 60 == 0) {
                HLog.d("ERROR: microphone delivers digital silence for $silentSeconds s: Android has silenced Hush (was the screen off when the role started?). Open Hush on this phone.")
                main.post { listener?.onLinkStatus("MICROPHONE SILENCED by Android: open Hush on this phone") }
            }
        } else {
            if (silentSeconds >= 3) HLog.d("Microphone back after $silentSeconds s of digital silence")
            silentSeconds = 0
        }
        val tap = tapDetector.analyse(pcm48k, n48, now - 1000)
        // Phantom knocks (27 Sep 02:50): in a quiet room a third of all onsets were −38 dBFS clicks IDENTICAL in both
        // channels (two-mic delay 0.0, correlation 0.97, no accelerometer jolt, random moment in the second):
        // electrical, not sound. Two mics 17 cm apart never agree like that, so such onsets are dropped before the
        // rhythm tracker and the arrow see them (they looked like steady tapping beside the phone on Sensor C).
        val phantom = phantomFlags(tap, startSample)
        val phantoms = phantom.count { it }
        val acc = accel?.drain() ?: AccelChannel.Result(0, 0f, 0f, emptyList(), false)
        // Rhythm is AUDIO ONLY. Accelerometer jolts were tried as onsets and flooded the history (6–8 per
        // second on a handled phone), which killed every detection. Jolts now only confirm a heard knock.
        // The app's own sounds are not taps: the start buzz (three regular pulses that rattle the phone),
        // the beeps and the ranging chirps all produced "steady tapping" once. Ignore onsets while they play.
        val selfNoise = (inHush && now - hushStartMs < SELF_NOISE_MS) || (now - lastChirpMs < CHIRP_QUIET_MS)
        synchronized(audioLock) { if (!selfNoise) rhythmTracker.add(tap.onsetsAbsMs.filterIndexed { i, _ -> !phantom[i] }) }
        val agreed = tap.onsetsAbsMs.any { a -> acc.spikeTimesMs.any { kotlin.math.abs(it - a) <= RhythmTracker.MERGE_MS } }
        if (agreed) lastStructureMs = now
        val structure = now - lastStructureMs < RhythmTracker.HISTORY_MS
        val rhythm = synchronized(audioLock) { rhythmTracker.evaluate(now) }
        // At home the model runs only on seconds worth it (loud against the floor, or a sharp onset, or no floor
        // yet): most of a day is quiet and the rescue roles' every-second voice score is not needed there.
        val household = isHousehold(role)
        val worthClassifying = !household || floor <= 0f || recentRms.size < 10 || rms >= floor * 1.5f || tap.taps > 0
        val cls = if (worthClassifying) classifier?.classify(pcm16k, n16) else null
        if (household) {
            if (worthClassifying) classifierRun++ else classifierSkipped++
            if ((classifierRun + classifierSkipped) % 600 == 0L) HLog.d("Household classifier: ran $classifierRun s, skipped $classifierSkipped s (quiet)")
        }
        val label = fuseLabel(rhythm, cls)
        // HOME: how tonal is this second (a whistle is one strong line; frying is not)? Only for loud seconds.
        val tonal = if (role == ROLE_HOME && (floor <= 0f || rms >= floor * 2f)) try { com.hush.audio.Tonality.measure(pcm16k, n16) } catch (e: Exception) { HLog.d("ERROR in tonality: $e"); null } else null
        main.post { trackWalking(acc); trackPlacement(acc) }
        // Source location, part 1 (every phone): each knock timed to the sample, its loudness, and the
        // two-mic delay that says which side of this phone it came from. Sent to the commander.
        val onsetReport = if (!selfNoise && phantoms < tap.onsets.size) buildOnsetReport(tap, phantom, startSample, now - 1000, acc, rhythm.score) else null
        // The own arrow is shown only while this phone hears deliberate tapping: steady/patterned rhythm, a Hush
        // window, or a few LOUD knocks lately. The knock votes accumulate regardless, so it is ready at once.
        val loudCount = synchronized(loudOnsetMs) {
            while (loudOnsetMs.isNotEmpty() && now - loudOnsetMs.first() > 8000L) loudOnsetMs.removeFirst()
            loudOnsetMs.size
        }
        if (inHush || rhythm.score >= OWN_ARROW_RHYTHM || label == LABEL_TAPPING || loudCount >= LOUD_KNOCKS) ownArrowUntilMs = now + OWN_ARROW_HOLD_MS
        com.hush.audio.KnockBearing.estimate(now, headingDeg)?.let { a ->
            HLog.d("KNOCK ARROW shown=%b loud=%d screen=%.0f° twin=%s bearing=%.0f° conf=%.2f knocks=%d felt=%d resolved=%b turned=%.0f° heading=%.0f°".format(
                now <= ownArrowUntilMs, loudCount, a.screenDeg, a.twinDeg?.let { "%.0f°".format(it) } ?: "-", a.bearingDeg, a.confidence, a.knocks, a.felt, a.resolved, a.turnedDeg, headingDeg))
        }
        val own = ownArrowRaw()   // this phone's own estimate (not the shared choice); goes to the commander in the event
        // HOME → FIND, hums: a fridge or fan has no onsets, so the whole second's two-mic delay in a low band votes
        // for a direction, weighted by how loud it is against the room. Skipped in TICKS mode and on seconds with knocks.
        if (role == ROLE_HOME && findMode != FindMode.TICKS && tap.onsets.isEmpty() && !selfNoise && !acc.moving && floor > 0f && rms >= floor * 2f) {
            val cap = capture
            val m1 = if (cap != null && cap.stereo) cap.snapshot(startSample, n48, 1) else null
            if (m1 != null) {
                val d = com.hush.audio.Doa.delayBandPassed(pcm48k, m1, n48, AudioCapture.SAMPLE_RATE, HUM_LOW_HZ, HUM_HIGH_HZ)
                val used = d != null && d.quality >= HUM_MIN_Q && com.hush.audio.KnockBearing.add(d.delay, d.quality, rms / floor, rhythm.score, false, headingDeg, now)
                humSeconds++
                if (used || humSeconds % 10 == 0L) HLog.d("HUM DoA: delay=%s q=%s loud=x%.1f heading=%.0f used=%b".format(
                    d?.let { "%.2f".format(it.delay) } ?: "-", d?.let { "%.2f".format(it.quality) } ?: "-", rms / floor, headingDeg, used))
            }
        }
        // Part 2, voices: the two-mic delay over the whole second (no sharp onset to time).
        var voiceDelay: Float? = null; var voiceQ: Float? = null
        if (!selfNoise && !acc.moving && (cls?.human ?: 0f) >= 0.3f && rms > floor * 1.5f) {
            val cap = capture
            val m1 = if (cap != null && cap.stereo) cap.snapshot(startSample, n48, 1) else null
            if (m1 != null) {
                val d = com.hush.audio.Doa.delayBandPassed(pcm48k, m1, n48, AudioCapture.SAMPLE_RATE)
                if (d != null) { voiceDelay = d.delay; voiceQ = d.quality }
                HLog.d("Voice DoA: delay=%s q=%s heading=%.0f".format(d?.let { "%.2f".format(it.delay) } ?: "-", d?.let { "%.2f".format(it.quality) } ?: "-", headingDeg))
            }
        }
        val battery = batteryPercent()
        // Noise floor: only quiet, still seconds count, and it is a median (see FLOOR_SECONDS).
        synchronized(audioLock) {
            if (!rangingInProgress && now - lastChirpMs > 1500 && !acc.moving && !(inHush && now - hushStartMs < SELF_NOISE_MS)) {
                recentRms.addLast(rms)
                while (recentRms.size > FLOOR_SECONDS) recentRms.removeFirst()
            }
            if (!inHush) floor = currentFloor()
        }
        val event = SensorEvent(
            sensorId = letter,
            tMs = now,
            rms = rms,
            floor = floor,
            human = cls?.human ?: 0f,
            machine = cls?.machine ?: 0f,
            topClass = cls?.topClass ?: "-",
            taps = tap.taps - phantoms,
            tapScore = tap.score,
            impact = cls?.impact ?: 0f,
            rhythmScore = rhythm.score,
            rhythm = rhythm.rhythm,
            label = label,
            accel = acc.spikes,
            accelMax = acc.maxHp,
            moving = acc.moving && !agreed,   // a jolt within 100 ms of a heard onset is the knock (full weight); shaking without onsets stays "moving"
            battery = battery,
            tempoMs = rhythm.tempoMs,
            lat = gps?.fix?.latitude,
            lon = gps?.fix?.longitude,
            gpsAcc = gps?.fix?.accuracy,
            micDelay = voiceDelay,
            micQ = voiceQ,
            bearing = own?.bearingDeg,
            bearingQ = own?.confidence,
            bearingTwin = own?.twinBearingDeg,
            heading = compass?.takeIf { it.available }?.headingDeg,
            windowAgeMs = SystemClock.elapsedRealtime() - now
        )
        val w = Window(rms, event.floor, classifier?.lastGain ?: 1f, tap, rhythm, acc, structure, cls, event)
        HLog.d("window id=$letter hush=$inHush label=$label rms=%.4f floor=%.4f taps=%d phantoms=%d rej=%d peak=x%.0f acc=%d accMax=%.3f accRms=%.3f struct=%b rhythm=%.1f/%s(%d) voice=%.2f impact=%.2f machine=%.2f | %s".format(
            rms, event.floor, tap.taps, phantoms, tap.rejectedSustained, tap.peakRatio, acc.spikes, acc.maxHp, acc.rmsHp, structure,
            rhythm.score, rhythm.rhythm, rhythm.count, event.human, event.impact, event.machine,
            cls?.top5?.joinToString(", ") { (name, s) -> "%s %.2f".format(name, s) } ?: "-"))
        main.post {
            listener?.onOwnWindow(w)
            when (role) {
                ROLE_SENSOR -> if (letter == "?") {
                    // Until ASSIGN arrives our events would land on the commander as a phantom "Sensor ?".
                    if (!warnedUnassigned) { warnedUnassigned = true; HLog.d("No letter assigned yet: events held back") }
                } else {
                    onsetReport?.let { link?.sendUp(it.toJson()) }; link?.sendUp(event.toJson())
                    runLocatorHere()
                }
                ROLE_COMMANDER -> {
                    closestHeardUntil["A"] = now; closestHeardAt["A"] = now
                    feedVoice(event, now)
                    onsetReport?.let { locator.addReport(it); feedClosest(it, remote = false); if (peers.isNotEmpty() && it.onsets.isNotEmpty()) link?.sendDown(it.toJson()) }; recordEvent(event); boardEvents["A"] = event; boardEventMs["A"] = now
                    closestTick()
                    listener?.onEvent(event, localName); sendBoardDown()
                }
                ROLE_ALERT -> try { alertSecond(w, selfNoise, now) } catch (e: Exception) { HLog.d("ERROR in household alerts: $e") }
                ROLE_HOME -> try { homeSecond(w, tonal, selfNoise, now) } catch (e: Exception) { HLog.d("ERROR in whistle counter: $e") }
            }
        }
    }
    private var warnedUnassigned = false

    /**
     * Audio thread. For each knock this second: absolute sample of its first arrival, peak, and the delay
     * between the two mics over the first 12 ms (before the room's echoes arrive). "felt" marks knocks
     * that also shook the phone (accelerometer): those travelled through the floor, faster than through
     * air, so the commander trusts their timing less.
     */
    /** Both channels' difference energy below this share of the signal: the same signal in both, not a sound. */
    private const val PHANTOM_DIFF_RATIO = 0.05
    /** A weak click with a near-perfect zero-delay correlation is a phantom too (a mono copy with different gain). */
    private const val PHANTOM_MAX_PEAK = 0.03f
    private const val PHANTOM_MIN_Q = 0.90f   // measured 27 Sep 02:50 on ef39's raw audio: phantoms 0.89–0.97 at lag 0, real weak sounds ≤ 0.95 at lags 2–3

    /** Per onset of [tap]: true when both microphone channels carry the same signal (electrical click, not a sound). Audio thread. */
    private fun phantomFlags(tap: TapDetector.Result, startSample: Long): BooleanArray {
        val out = BooleanArray(tap.onsets.size)
        val cap = capture ?: return out
        if (!cap.stereo) return out
        for ((i, o) in tap.onsets.withIndex()) {
            val from = startSample + o.sampleInWindow - 48; val count = 48 + 576
            val x0 = cap.snapshot(from, count, 0) ?: continue
            if (isUltrasonic(x0, count)) {
                out[i] = true
                HLog.d("Onset @%d (+%d ms) peak=%.3f x%.0f PHANTOM (ultrasonic: a chirp)".format(startSample + o.sampleInWindow, o.sampleInWindow / 48, o.peak, o.ratio))
                continue
            }
            val x1 = cap.snapshot(from, count, 1) ?: continue
            var e0 = 0.0; var ed = 0.0
            for (k in 0 until count) { val a = x0[k].toDouble(); val d = a - x1[k]; e0 += a * a; ed += d * d }
            val identical = e0 > 0.0 && ed / e0 < PHANTOM_DIFF_RATIO
            var weakMono = false
            if (!identical && o.peak < PHANTOM_MAX_PEAK) {
                val d = com.hush.audio.Doa.delay(x0, x1, count)
                weakMono = d != null && d.quality >= PHANTOM_MIN_Q && kotlin.math.abs(d.delay) < 1.0f
            }
            out[i] = identical || weakMono
            if (out[i]) HLog.d("Onset @%d (+%d ms) peak=%.3f x%.0f PHANTOM (channels %s, diff %.3f)".format(
                startSample + o.sampleInWindow, o.sampleInWindow / 48, o.peak, o.ratio, if (identical) "identical" else "mono-like", ed / e0.coerceAtLeast(1e-12)))
        }
        return out
    }

    private fun buildOnsetReport(tap: TapDetector.Result, phantom: BooleanArray, startSample: Long, windowStartMs: Long, acc: AccelChannel.Result, rhythmNow: Float): com.hush.model.OnsetReport {
        val cap = capture
        val out = ArrayList<com.hush.model.Onset>()
        for ((i, o) in tap.onsets.withIndex()) {
            if (phantom[i]) continue
            val abs = startSample + o.sampleInWindow
            var delay: Float? = null; var q: Float? = null
            if (cap != null && cap.stereo) {
                val from = abs - 48; val count = 48 + 576
                val x0 = cap.snapshot(from, count, 0); val x1 = cap.snapshot(from, count, 1)
                if (x0 != null && x1 != null) {
                    val d = com.hush.audio.Doa.delay(x0, x1, count)
                    if (d != null) { delay = d.delay; q = d.quality }
                }
            }
            val ms = windowStartMs + o.sampleInWindow / 48
            val felt = acc.spikeTimesMs.any { kotlin.math.abs(it - ms) <= RhythmTracker.MERGE_MS }
            // The phone's own arrow (compass plan step 1): this knock's two-mic delay votes for a direction.
            // (In the HOME role's HUM mode sharp onsets are not the noise being looked for, so they do not vote.)
            val used = if (role == ROLE_HOME && findMode == FindMode.HUM) false else com.hush.audio.KnockBearing.add(delay, q, o.ratio, rhythmNow, felt, headingDeg, ms)
            if (used && o.ratio >= LOUD_KNOCK_RATIO) synchronized(loudOnsetMs) { loudOnsetMs.addLast(ms) }
            out.add(com.hush.model.Onset(abs, o.peak, o.ratio, delay, q, felt, SystemClock.elapsedRealtime() - ms))
            HLog.d("Onset @%d (+%d ms) peak=%.3f x%.0f rise=%d dl=%s q=%s%s heading=%.0f%s".format(abs, o.sampleInWindow / 48, o.peak, o.ratio, o.riseSamples,
                delay?.let { "%.2f".format(it) } ?: "-", q?.let { "%.2f".format(it) } ?: "-", if (felt) " felt" else "", headingDeg, if (used) " arrow" else ""))
        }
        return com.hush.model.OnsetReport(letter, headingDeg, acc.moving, out)
    }

    // ---- Timing locator on every phone, clocks from inaudible chirps (27 Sep, steps 2 and 3) ----

    /** True while the map comes from tape positions (hand layout): chirp rounds then only sync the clocks. */
    @Volatile var placedByHand = false
        private set
    private fun handPositions() = placedByHand || gpsLayoutActive
    private var clockRoundsDone = 0
    /** The heading every phone was set to read at the last SYNC COMPASS (commander); orients a taped layout. */
    private var lastSyncRef: Float? = null
    /** Knocks are ignored this long after a chirp command. Was 1.5 s for the audible chirp; the ultrasonic
     *  check in [phantomFlags] now drops the chirp itself, so only the moment of our own playback is blanked. */
    private const val CHIRP_QUIET_MS = 300L
    /** An inaudible clock round this often, so the audio clocks' drift (parts per million) is tracked. */
    private const val CLOCK_ROUND_MS = 60_000L
    /** Onset energy ratio (second difference / signal) above which it is ultrasound: 13–14 for 19–21.5 kHz, ~6 for white noise, < 2 for knocks. */
    private const val ULTRA_RATIO = 8.0

    private val clockTimer = object : Runnable {
        override fun run() {
            if (role != ROLE_COMMANDER) return
            clockRound("every ${CLOCK_ROUND_MS / 1000} s")
            main.postDelayed(this, CLOCK_ROUND_MS)
        }
    }

    /** Commander: an inaudible chirp round for the clocks, when positions are known and nothing else runs. */
    private fun clockRound(why: String) {
        // Continuous (team, 27 Sep: "the chirping needs to keep running"): with or without a tape layout; the map needs three phones.
        if (role != ROLE_COMMANDER || peers.size < 2 || rangingInProgress || inHush) return
        HLog.d("Clock round ($why, inaudible 19–21.5 kHz)")
        autoPlace()
    }

    // ---- Auto-locate: phone positions from the chirps every round (AutoLocate.kt) ----

    /** Every phone's fixed chirp-range error (metres, by name suffix), measured on the tape layout; null = not calibrated. */
    @Volatile var rangeBias: Map<String, Double>? = null
        private set
    private var lastBiasRound: Map<String, Double>? = null
    private var autoLocateRounds = 0
    /** Pause between auto-locate rounds; a round of three serial chirps itself takes ~7 s. */
    private const val AUTO_GAP_MS = 2000L

    /**
     * Commander, after each tape-layout chirp round. Calibrating: the round's chirp distances against the tape give every
     * phone's error; two rounds agreeing within [AutoLocate.BIAS_AGREE_M] switch auto-locate on. Then every round: the
     * corrected distances rebuild the triangle (turned onto the previous positions) and, when a phone moved, the
     * positions replace the layout (in memory; the tape in the preferences stays). The next round starts [AUTO_GAP_MS] later.
     */
    private fun autoLocateStep(letters: List<String>, dist: Map<String, Double>) {
        val three = letters.take(3)
        if (three.size < 3) return
        val names = three.associateWith { l -> (phoneName(l) ?: return).takeLast(4).lowercase() }
        fun key(k: String) = names.getValue(k.substring(0, 1)) to names.getValue(k.substring(1, 2))
        val chirp = dist.filterKeys { it.length == 2 && it.substring(0, 1) in three && it.substring(1, 2) in three }.mapKeys { key(it.key) }
        autoLocateRounds++
        val bias = rangeBias
        if (bias == null) {
            val tape = tapeDistances(three).mapKeys { key(it.key) }
            val b = AutoLocate.bias(chirp, tape)
            if (b == null) { HLog.d("AUTOLOCATE: calibration round incomplete (pairs ${chirp.keys})"); main.postDelayed({ clockRound("auto-locate calibration") }, AUTO_GAP_MS); return }
            HLog.d("AUTOLOCATE calibration round: " + b.entries.joinToString(" ") { "%s %+.2f m".format(it.key, it.value) } +
                " (chirp " + chirp.entries.joinToString(" ") { "%s-%s %.2f".format(it.key.first, it.key.second, it.value) } + ")")
            val prev = lastBiasRound
            lastBiasRound = b
            if (prev != null && b.keys.all { k -> prev[k] != null && kotlin.math.abs(prev.getValue(k) - b.getValue(k)) <= AutoLocate.BIAS_AGREE_M }) {
                rangeBias = b.keys.associateWith { k -> (prev.getValue(k) + b.getValue(k)) / 2 }
                HLog.d("AUTOLOCATE on: range errors " + rangeBias!!.entries.joinToString(" ") { "%s %+.2f m".format(it.key, it.value) } + "; positions now follow the chirps")
                sessionLog.addRecord("autolocate_on", rangeBias!!.mapValues { it.value })
            }
        } else {
            val corrected = chirp.mapValues { (p, d) -> d - (bias[p.first] ?: 0.0) - (bias[p.second] ?: 0.0) }
            val aName = names.getValue("A")
            val prev = names.values.associateWith { n -> layoutByName[n] ?: if (n == aName) (0.0 to 0.0) else null }
            if (prev.values.any { it == null }) { HLog.d("AUTOLOCATE: a phone has no previous position ($prev)"); return }
            @Suppress("UNCHECKED_CAST") val now = AutoLocate.place(corrected, prev as Map<String, Pair<Double, Double>>)
            val dText = corrected.entries.joinToString(" ") { "%s-%s %.2f".format(it.key.first, it.key.second, it.value) }
            if (now == null) { HLog.d("AUTOLOCATE round: corrected $dText m do not form a triangle, positions kept") }
            else {
                val moved = now.keys.maxOf { k -> kotlin.math.hypot(now.getValue(k).first - prev.getValue(k).first, now.getValue(k).second - prev.getValue(k).second) }
                HLog.d("AUTOLOCATE round: corrected $dText m -> " + now.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) } +
                    " moved %.2f m%s".format(moved, if (moved > AutoLocate.MOVE_M) ", map updated" else ""))
                if (moved > AutoLocate.MOVE_M) {
                    layoutByName.putAll(now)
                    sessionLog.addRecord("autolocate", now.mapValues { "%.2f,%.2f".format(it.value.first, it.value.second) })
                    applyLayout()
                }
            }
        }
        main.postDelayed({ clockRound("auto-locate") }, AUTO_GAP_MS)
    }

    /** Chirp pair distances ("AB" → metres) minus both phones' fixed range errors ([rangeBias], else the built-in ones). */
    private fun correctedChirp(letters: List<String>, dist: Map<String, Double>): HashMap<String, Double> {
        val out = HashMap<String, Double>()
        for ((k, d) in dist) {
            if (k.length != 2) continue
            val a = phoneName(k.substring(0, 1))?.takeLast(4)?.lowercase(); val b = phoneName(k.substring(1, 2))?.takeLast(4)?.lowercase()
            fun bias(n: String?) = n?.let { rangeBias?.get(it) ?: AutoLocate.defaultBias(it) } ?: AutoLocate.DEFAULT_OTHER_M
            out[k] = (d - bias(a) - bias(b)).coerceAtLeast(0.05)
        }
        return out
    }

    /**
     * Commander, a chirp round with no positions yet: place A, B, C from the corrected distances (A at 0,0, B along +x)
     * and hand over to [autoLocateStep] for every round after. Returns true when the round was handled here.
     */
    private fun chirpFirstPlacement(letters: List<String>, dist: Map<String, Double>): Boolean {
        val three = letters.take(3)
        if (three.size < 3) return false
        val names = three.map { l -> phoneName(l)?.takeLast(4)?.lowercase() ?: return false }
        val corr = correctedChirp(three, dist)
        fun d(x: Int, y: Int) = corr["${three[x]}${three[y]}"] ?: corr["${three[y]}${three[x]}"]
        val pd = HashMap<Pair<String, String>, Double>()
        for ((x, y) in listOf(0 to 1, 0 to 2, 1 to 2)) d(x, y)?.let { pd[names[x] to names[y]] = it }
        val raw = dist.entries.joinToString(" ") { "%s %.2f".format(it.key, it.value) }
        val pos = AutoLocate.first(names[0], names[1], names[2], pd)
        if (pos == null) {
            HLog.d("AUTOLOCATE: first placement failed (raw $raw, corrected ${pd.entries.joinToString(" ") { "%s-%s %.2f".format(it.key.first, it.key.second, it.value) }}): next round in ${AUTO_GAP_MS / 1000} s")
            main.postDelayed({ clockRound("auto-locate") }, AUTO_GAP_MS)
            return true
        }
        rangeBias = names.associateWith { rangeBias?.get(it) ?: AutoLocate.defaultBias(it) }
        layoutByName.clear(); layoutByName.putAll(pos)
        HLog.d("AUTOLOCATE start from the chirps: raw $raw -> corrected " + pd.entries.joinToString(" ") { "%s-%s %.2f".format(it.key.first, it.key.second, it.value) } +
            " m (range errors " + rangeBias!!.entries.joinToString(" ") { "%s %+.2f".format(it.key, it.value) } + ") -> " +
            pos.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) })
        sessionLog.addRecord("autolocate", pos.mapValues { "%.2f,%.2f".format(it.value.first, it.value.second) })
        applyLayout()   // positions onto the map and into the locator; the next round starts 2 s later
        return true
    }

    /** Pair distances from the tape positions, keyed like the ranging ("AB"). */
    private fun tapeDistances(letters: List<String>): HashMap<String, Double> {
        val out = HashMap<String, Double>()
        for (i in letters.indices) for (j in i + 1 until letters.size) {
            val a = posMetres(letters[i]) ?: continue; val b = posMetres(letters[j]) ?: continue
            out["${letters[i]}${letters[j]}"] = kotlin.math.hypot(a.first - b.first, a.second - b.second)
        }
        return out
    }

    /** Commander: the round's detections and distances, so every phone computes the same clock offsets. */
    private fun sendClockRoundDown(letters: List<String>, dist: Map<String, Double>) {
        if (peers.isEmpty()) return
        val copy = LinkedHashMap<String, Map<String, Long>>().apply { heard.forEach { (h, m) -> put(h, HashMap(m)) } }
        link?.sendDown(com.hush.model.ClockRound(letters, copy, dist).toJson())
    }

    /** Sensor: the commander's chirp round, fed to this phone's own locator. */
    private fun onClockRound(r: com.hush.model.ClockRound) {
        try { locator.updateClocks(r.heard, r.dist, r.letters) } catch (e: Exception) { HLog.d("ERROR in clock update: $e") }
        HLog.d("Clock round received: this phone ${if (locator.hasClock(letter)) "is timed" else "has no clock yet"}")
    }

    /** True when an onset's audio is mostly above ~17 kHz: one of our chirps, never a knock. */
    private fun isUltrasonic(x: ShortArray, n: Int): Boolean {
        var e = 0.0; var h = 0.0
        for (k in 2 until n) { val a = x[k].toDouble(); val d = a - 2.0 * x[k - 1] + x[k - 2]; e += a * a; h += d * d }
        return e > 0.0 && h / e > ULTRA_RATIO
    }

    /** Sensor, once a second: the same locator the commander runs, on the relayed knock timings. */
    private fun runLocatorHere() {
        val cap = capture ?: return
        if (!locator.hasClock(letter)) return
        val nowA = locator.toA(letter, cap.samplesCaptured) ?: return
        val now = SystemClock.elapsedRealtime()
        val tapping = inHush || lastRanking.any { it.evidence >= 0.9f }
        try {
            if (locator.process(nowA.toLong(), now, false, { 1f }, mode != Mode.VOICE && tapping, false))
                locator.fix?.let { f -> HLog.d("LOCATE here: (%.2f, %.2f) m ±%.2f from %d knocks".format(f.x, f.y, f.radius, f.knocks)) }
        } catch (e: Exception) { HLog.d("ERROR in locator: $e") }
    }

    /** Sensor: every phone's position in metres, the map's scale and orientation, from the commander's board. */
    private val boardPos = LinkedHashMap<String, Pair<Double, Double>>()
    @Volatile private var boardScale: Float? = null
    /** Sensor: the commander's loudness point (x, y, radius in metres) from the Board. */
    @Volatile private var boardWhere: Triple<Double, Double, Double>? = null

    /** The loudness point on this phone's map: (x, y) as map fractions and the radius as a map fraction, or null. */
    fun whereOnMap(): Pair<Pair<Float, Float>, Float>? {
        if (role == ROLE_COMMANDER) {
            val p = wherePoint ?: return null; val s = mapMetresPerUnit ?: return null
            return (metresToMap(p.x, p.y) ?: return null) to (p.radiusM / s).toFloat()
        }
        val w = boardWhere ?: return null; val s = boardScale ?: return null
        return ((0.5 + w.first / s).toFloat() to (0.5 - w.second / s).toFloat()) to (w.third / s).toFloat()
    }
    @Volatile private var boardRot: Float? = null

    data class LocalArrow(val screenDeg: Float?, val metres: Float, val radius: Float, val knocks: Int, val edge: Boolean)

    /**
     * The arrow from THIS phone's position to the source its own locator found (timing across all phones), or null.
     * screenDeg is null until the map is oriented (SYNC COMPASS with a taped layout, or pointing).
     */
    fun localSourceArrow(): LocalArrow? {
        if (!locator.hasAnyClock()) return null
        val f = locator.fix ?: return null
        if (SystemClock.elapsedRealtime() - f.atMs > Locator.MAX_AGE_MS) return null
        val me = if (role == ROLE_COMMANDER) posMetres("A") else boardPos[letter]
        me ?: return null
        val rot = if (role == ROLE_COMMANDER) mapRotationDeg else boardRot
        val dx = f.x - me.first; val dy = f.y - me.second
        val bearing = ((Math.toDegrees(kotlin.math.atan2(dx, dy)).toFloat()) + 360f) % 360f
        val screen = rot?.let { ((bearing + it - headingDeg) + 720f) % 360f }
        return LocalArrow(screen, kotlin.math.hypot(dx, dy).toFloat(), f.radius.toFloat(), f.knocks, f.edge)
    }


    // ---- Same screen on every phone (27 Sep, team: "they all need to have the same UI") ----

    /** Commander: every phone's latest event (letter → event, when it arrived). Sensor: the latest board's events. */
    private val boardEvents = LinkedHashMap<String, SensorEvent>()
    private val boardEventMs = HashMap<String, Long>()
    /** Sensor: letter → phone name, the commander's status lines and discovered-phone lines from the latest board. */
    private val boardNames = LinkedHashMap<String, String>()
    @Volatile var boardStatus: String = ""
        private set
    @Volatile var boardDiscovered: String = ""
        private set
    // ---- Which phone is closest to the knocking (27 Sep 05:30, after the RCA) ----
    // The phone that hears a knock loudest is the nearest one. No compass, no clocks, no mirror. See Closest.kt.

    private val closest = Closest()
    /** What the big panel shows on every phone; "" while nothing is judged. */
    @Volatile var closestText = ""
        private set
    /** A remote report reaches the commander about this long after it was built (measured 0.04–0.15 s). */
    private const val RELAY_MS = 80L

    /** Commander: every knock of a report on the commander's clock. [remote] = arrived over the mesh. */
    private fun feedClosest(r: com.hush.model.OnsetReport, remote: Boolean) {
        // A moving phone (handled or carried) still reports: its levels feed WARMER/COLDER, never the closest-phone vote.
        val now = SystemClock.elapsedRealtime()
        for (o in r.onsets) {
            val age = o.ageMs ?: continue   // older build on that phone
            val t = now - age - (if (remote) RELAY_MS else 0L)
            if (!closest.add(r.letter, t, o.peak, o.ratio, r.moving, Closest.Dir(o.micDelay, o.micQ, r.heading, o.felt))) HLog.d("CLOSEST late: ${r.letter}'s knock (peak %.4f) arrived after that knock was judged: dropped".format(o.peak))
        }
        closestTick()
    }

    /** Commander, once a second and on every report: judge the knocks whose reports are all in, update the panel. */
    private fun closestTick() {
        if (role != ROLE_COMMANDER) return
        val now = SystemClock.elapsedRealtime()
        for (k in closest.tick(now, closestReadyUntil(now))) {
            val levels = k.all.entries.sortedByDescending { it.value }.joinToString(" ") { (l, p) -> "%s=%.4f%s".format(l, p, if (l in k.peaks) "" else "(moving)") }
            HLog.d(if (k.winner.isEmpty()) "CLOSEST knock: $levels -> no vote (only moving phones heard it)" else
                "CLOSEST knock: %s -> %s%s%s".format(levels, k.winner, k.leadDb?.let { " +%.1f dB over %s".format(it, k.runnerUp) } ?: " (only still phone that heard it)",
                if (k.decisive) "" else " (tie, not counted)") + " · judged ${now - k.tMs} ms after the knock")
            sessionLog.addRecord("closest_knock", mapOf("peaks" to k.all.toString(), "moving" to (k.all.keys - k.peaks.keys).toString(), "winner" to k.winner, "lead_db" to k.leadDb, "counted" to k.decisive))
            warmth.add(k.tMs, k.all, k.peaks.keys)
            val votes = knockDir.add(k)
            if (k.dirs.isNotEmpty()) HLog.d("KNOCKDIR knock: " + k.dirs.entries.joinToString("  ") { (l, d) ->
                val v = votes.firstOrNull { it.letter == l }
                "%s dl=%s q=%s hd=%s%s -> %s".format(l, d.delay?.let { "%.1f".format(it) } ?: "-", d.q?.let { "%.2f".format(it) } ?: "-",
                    d.heading?.let { "%.0f".format(it) } ?: "-", if (d.felt) " felt" else "",
                    v?.let { "θ%.0f° votes %.0f°/%.0f° w%.2f".format(it.thetaDeg, (it.headingDeg + it.thetaDeg + 360) % 360, (it.headingDeg - it.thetaDeg + 360) % 360, it.weight) } ?: "no vote")
            })
        }
        val kd = knockDir.result(now)
        val kdKey = kd?.let { "%d%b%s".format((it.bearingDeg / 5).toInt(), it.resolved, it.phones) } ?: ""
        if (kdKey != knockDirKey) {
            knockDirKey = kdKey
            HLog.d(if (kd == null) "KNOCKDIR fused: none" else "KNOCKDIR fused: %.0f°%s conf %.2f from %s over %d knocks".format(kd.bearingDeg,
                if (kd.resolved) " resolved" else " or %.0f° (unresolved: lay the phones at different angles)".format(kd.twinDeg ?: 0f), kd.confidence, kd.phones.joinToString(","), kd.knocks))
            sendBoardDown(force = true)
            kd?.let { sessionLog.addRecord("knockdir", mapOf("bearing" to it.bearingDeg, "twin" to it.twinDeg, "resolved" to it.resolved, "conf" to it.confidence, "phones" to it.phones.joinToString(","))) }
        }
        knockDirResult = kd
        // Voice seconds (shouting, talking): judged like knocks by their own judge; used when there are no recent knocks.
        for (k in closestVoice.tick(now, closestReadyUntil(now))) {
            val levels = k.all.entries.sortedByDescending { it.value }.joinToString(" ") { (l, p) -> "%s=%.4f%s".format(l, p, if (l in k.peaks) "" else "(moving)") }
            HLog.d(if (k.winner.isEmpty()) "VOICE second: $levels -> no vote (only moving phones)" else
                "VOICE second: %s -> %s%s%s".format(levels, k.winner, k.leadDb?.let { " +%.1f dB over %s".format(it, k.runnerUp) } ?: " (only still phone)",
                if (k.decisive) "" else " (tie, not counted)"))
            sessionLog.addRecord("closest_voice", mapOf("levels" to k.all.toString(), "winner" to k.winner, "lead_db" to k.leadDb, "counted" to k.decisive))
            warmthVoice.add(k.tMs, k.all, k.peaks.keys)
        }
        val sKnock = closest.summary(now); val sVoice = closestVoice.summary(now)
        val useVoice = sKnock == null && sVoice != null
        val warmSrc = if (useVoice) warmthVoice else warmth
        // WARMER / COLDER per phone (Warmth.kt): logged when a phone's trend changes, carried to every phone in the Board.
        val warm = LinkedHashMap<String, Warmth.Status>()
        for (l in warmSrc.letters().sorted()) warmSrc.status(l, now)?.let { warm[l] = it }
        for ((l, st) in warm) if (warmTrendLogged[l] != st.trend) {
            warmTrendLogged[l] = st.trend
            HLog.d("WARMTH %s: %s %+.1f dB (level vs the still phones %+.1f dB, %d knocks)%s".format(l, st.trend, st.deltaDb, st.relDb, st.knocks,
                if (boardEvents[l]?.moving == true) " moving" else ""))
        }
        warmthByLetter = warm
        val s = sKnock ?: sVoice
        // WHERE between the phones (LoudnessLocator): the same clear knocks (or voice seconds), the phones' positions.
        val srcClosest = if (useVoice) closestVoice else closest
        val placed = (listOf("A") + peers.values.map { it.letter }).mapNotNull { l -> posMetres(l)?.let { l to it } }.toMap()
        val point = if (s == null) null else try { LoudnessLocator.locate(srcClosest.recent(now).map { it.peaks }, placed) } catch (e: Exception) { HLog.d("ERROR in loudness locator: $e"); null }
        if (point != null) {
            val key = "%.1f,%.1f,%.1f".format(point.x, point.y, point.radiusM)
            if (key != wherePointKey) {
                wherePointKey = key
                HLog.d("WHERE: (%.2f, %.2f) m ±%.2f from %d knocks heard by ≥ 3 phones, nearest %s %.2f m (positions %s)".format(
                    point.x, point.y, point.radiusM, point.knocks, point.nearest, point.nearestM,
                    placed.entries.joinToString(" ") { "%s(%.2f,%.2f)".format(it.key, it.value.first, it.value.second) }))
                sessionLog.addRecord("where", mapOf("x" to point.x, "y" to point.y, "radius" to point.radiusM, "knocks" to point.knocks, "nearest" to point.nearest, "nearest_m" to point.nearestM))
            }
        }
        wherePoint = point
        val text = if (s == null) "" else buildString {
            val sure = s.wins * 10 >= s.knocks * 6
            append(if (sure) "CLOSEST" else "LEANING").append(if (useVoice) " (voice): " else ": ").append(if (s.leader == "A") "Commander A" else "Sensor ${s.leader}")
            phoneName(s.leader)?.let { append(" · ").append(it.takeLast(4)) }
            append("\nloudest on ${s.wins} of ${s.knocks} clear ${if (useVoice) "seconds of voice" else "knocks"}")
            if (s.leadDb != null && s.runnerUp != null) append(" · %.0f dB louder than %s".format(s.leadDb, s.runnerUp))
            append("\n").append(s.winsBy.entries.joinToString("  ") { "${it.key} ${it.value}" })
            if (s.lastWinner != s.leader) append("  · last knock: ${s.lastWinner}")
            if (point != null) {
                val who = if (point.nearest == "A") "Commander A" else "Sensor ${point.nearest}"
                append("\nSound ≈ %.1f m from %s (±%.1f m) · red circle on the map".format(point.nearestM, who, point.radiusM))
            }
            val carried = warm.filter { (l, st) -> st.trend != Warmth.Trend.SAME && boardEvents[l]?.moving == true }
            if (carried.isNotEmpty()) append("\nCarried: ").append(carried.entries.joinToString("  ") { (l, st) -> "%s %s %+.0f dB".format(l, st.trend.name.lowercase(), st.deltaDb) })
        }
        val warmKey = warm.entries.joinToString { (l, st) -> "$l${st.trend}%.0f".format(st.deltaDb) }
        if (text != closestText || warmKey != lastWarmKey) {
            lastWarmKey = warmKey
            closestText = text
            HLog.d("CLOSEST panel: ${text.replace('\n', '|').ifEmpty { "(none)" }}")
            listener?.onClosest(text)
            sendBoardDown(force = true)
        }
    }

    private val knockDir = KnockDirection()
    private var knockDirKey = ""
    /** The fused knock direction (commander computes it; sensors get it in the Board). */
    @Volatile var knockDirResult: KnockDirection.Result? = null
        private set
    @Volatile private var knockDirBoardMs = 0L

    /** The arrow every phone draws: the fused knock direction through THIS phone's synced heading, or null. */
    fun knockArrow(): Pair<KnockDirection.Result, Float>? {
        val r = knockDirResult ?: return null
        if (role != ROLE_COMMANDER && SystemClock.elapsedRealtime() - knockDirBoardMs > 5000L) return null
        return r to ((r.bearingDeg - headingDeg + 720f) % 360f)
    }

    // ---- POINT & TAP arrow (Aim.kt): each phone's own orientation from pointing at other phones ----

    private val aim = Aim()

    /** A phone's position in metres (the map frame every phone shares), from this phone's point of view. */
    fun posOf(l: String): Pair<Double, Double>? = if (role == ROLE_COMMANDER) posMetres(l) else boardPos[l]

    /** The other placed phones this phone can point at, the ones not yet pointed at first. */
    fun aimCandidates(): List<String> {
        val placed = (if (role == ROLE_COMMANDER) mapDots.keys else boardPos.keys).filter { it != letter }.sorted()
        return placed.filter { it !in aim.targets() } + placed.filter { it in aim.targets() }
    }

    fun aimTap(target: String): String {
        val me = posOf(letter) ?: return "This phone has no position yet: wait for the chirps to place the phones"
        val t = posOf(target) ?: return "$target has no position yet"
        val b = Aim.bearing(me, t)
        aim.tap(target, b, headingDeg.toDouble())
        val f = aim.frame()
        HLog.d("AIM: this phone ($letter) pointed at $target: map bearing %.0f°, heading %.0f° -> rotation %.0f°%s".format(b, headingDeg, f?.rotationDeg ?: 0.0,
            when (f?.mirrored) { null -> ", left/right not checked yet"; true -> ", map is MIRRORED (taps agree ±%.0f°)".format(f.spreadDeg ?: 0.0); false -> ", not mirrored (taps agree ±%.0f°)".format(f.spreadDeg ?: 0.0) }))
        return if (f?.refs == 1) "Aimed at $target. Aim at a second phone too to confirm left/right." else "Aimed at ${aim.targets().joinToString(" and ")}: taps agree within %.0f°".format(f?.spreadDeg ?: 0.0)
    }

    fun aimFrame(): Aim.Frame? = aim.frame()

    /** The phones this phone has been pointed at (at most two are kept). */
    fun aimedTargets(): List<String> = aim.targets()

    /** "Clear pointing" on the screen: forget this phone's orientation; the arrow is off until it is pointed again. */
    fun aimClear(): String {
        HLog.d("AIM: cleared on this phone ($letter), was pointed at ${aim.targets().ifEmpty { listOf("nothing") }.joinToString(", ")}")
        aim.reset()
        return "Pointing cleared. Point this phone at another phone and tap its button."
    }

    /** What the arrow shows: [screenDeg] null = arrow off; [label] short (drawn under the arrow); [hint] one or two lines below. */
    data class AimArrow(val screenDeg: Float?, val label: String, val hint: String)

    fun aimArrow(): AimArrow {
        val me = posOf(letter) ?: return AimArrow(null, "Arrow off", "Measuring where the phones are with inaudible chirps: needs three phones connected, then about 20 s")
        val f = aim.frame() ?: return AimArrow(null, "Arrow off", "One-time setup: point the top of this phone at another phone and tap that phone's button below")
        val side = if (f.mirrored == null) " · aim at a 2nd phone to confirm left/right" else ""
        // Target: the loudness spot (between the phones, LoudnessLocator), else the closest phone's position.
        val spot = (if (role == ROLE_COMMANDER) wherePoint?.let { it.x to it.y } else boardWhere?.let { it.first to it.second })
        val leader = Regex("(?:Sensor|Commander) ([A-Z])").find(closestText)?.groupValues?.get(1)
        val (target, what) = when {
            spot != null -> spot to "knock"
            leader != null && leader != letter -> (posOf(leader) ?: return AimArrow(null, "Arrow off", "Closest is $leader, position unknown yet")) to "Sensor $leader (closest)".replace("Sensor A", "Commander A")
            leader == letter -> return AimArrow(null, "THIS phone is closest", "The knocking is nearest to this phone$side")
            else -> return AimArrow(null, "Arrow off", "Knock: the arrow points once the phones hear it$side")
        }
        val d = kotlin.math.hypot(target.first - me.first, target.second - me.second)
        if (d < 0.25) return AimArrow(null, "HERE", "The knock is at this phone (within 25 cm)$side")
        val screen = aim.screenDeg(Aim.bearing(me, target), headingDeg.toDouble())?.toFloat()
        return AimArrow(screen, "→ %s · %.1f m".format(if (what == "knock") "KNOCK" else what, d),
            "Aimed at ${aim.targets().joinToString(", ")}" + (f.spreadDeg?.let { " (agree ±%.0f°)".format(it) } ?: "") + side)
    }

    private val warmth = Warmth()

    // ---- Voice (27 Sep, team: "yes build it" for shouting as well as knocking) ----
    // Every phone's once-a-second loudness (200–3000 Hz, the speech band) is compared across phones like a knock's peak,
    // in seconds where some phone's classifier hears speech. Seconds of different phones are not aligned, hence the wider
    // match (600 ms). The phone that chirps hears its own inaudible chirp ~25x louder than the others and the one-pole
    // band-pass lets some of it through, so seconds within [CHIRP_GUARD_MS] of a chirp are skipped.
    private val closestVoice = Closest(matchMs = 600L, readyMarginMs = 600L).apply { minLoudRatio = VOICE_MIN_RATIO }
    private val warmthVoice = Warmth()
    private val chirpTimesMs = ArrayDeque<Long>()
    private const val VOICE_MIN_CLASS = 0.5f     // YAMNet voice bucket
    private const val VOICE_MIN_RATIO = 3f       // that phone's second at least 3x its background (1 s loudness, not a knock peak)
    private const val CHIRP_GUARD_MS = 1500L

    /** Commander: one phone's second ([windowEndMs] on the commander's clock) as a voice detection. */
    private fun feedVoice(e: SensorEvent, windowEndMs: Long) {
        val centre = windowEndMs - 500L
        if (synchronized(chirpTimesMs) { chirpTimesMs.any { kotlin.math.abs(it - centre) <= CHIRP_GUARD_MS } }) return
        if (e.rms <= 0f) return
        // Only a phone that hears speech can make the second count; every phone's level takes part in the comparison.
        val ratio = if (e.human >= VOICE_MIN_CLASS && e.floor > 0f) e.rms / e.floor else 0f
        closestVoice.add(e.sensorId, centre, e.rms, ratio, e.moving)
    }
    private val warmTrendLogged = HashMap<String, Warmth.Trend>()
    private var lastWarmKey = ""
    /** Every phone's WARMER/COLDER status (commander computes it; sensors get it in the Board). */
    @Volatile var warmthByLetter: Map<String, Warmth.Status> = emptyMap()
        private set

    /** Where the knocking is between the phones (metres, posMetres frame), or null. Commander computes it; sensors get it in the Board. */
    @Volatile var wherePoint: LoudnessLocator.Point? = null
        private set
    private var wherePointKey = ""

    /** Per phone, on the commander's clock: every knock up to this time has been reported (its second's audio has ended and arrived). */
    private val closestHeardUntil = HashMap<String, Long>()
    private val closestHeardAt = HashMap<String, Long>()

    /** Every knock up to the returned time has been reported by every phone that is talking (heard from within 3 s);
     *  null when some talking phone cannot say (an older build): then the fixed wait applies. */
    private fun closestReadyUntil(now: Long): Long? {
        val talking = (listOf("A") + peers.values.map { it.letter }).filter { now - (closestHeardAt[it] ?: boardEventMs[it] ?: 0L) <= 3000L }
        if (talking.isEmpty()) return null
        return talking.minOf { closestHeardUntil[it] ?: return null }
    }

    private fun phoneName(l: String): String? = if (l == "A") localName else peers.values.firstOrNull { it.letter == l }?.name

    private var lastBoardMs = 0L
    private val boardLoggedT = HashMap<String, Long>()

    private fun boardPeers(): List<Peer> = boardNames.filter { it.key != letter }.map { (l, n) -> Peer("", n, l) }

    /** Sensor: a button on this screen, run by the commander. */
    private fun requestUp(type: String, arg: String? = null): Boolean {
        val l = link
        if (l == null || !l.isRouted || letter == "?") {
            HLog.d("REQUEST $type not sent: no route to the commander yet")
            onLinkStatus("$lastStatus · not connected to the commander yet")
            return false
        }
        l.sendUp(com.hush.model.Request(type, letter, arg).toJson())
        HLog.d("REQUEST sent: $type${arg?.let { " $it" } ?: ""}")
        return true
    }

    /** Commander: a sensor pressed a button. */
    private fun onRequest(r: com.hush.model.Request) {
        if (role != ROLE_COMMANDER) return
        HLog.d("REQUEST from ${r.from}: ${r.type}${r.arg?.let { " $it" } ?: ""}")
        when (r.type) {
            com.hush.model.Request.HUSH -> { allowSoloHush = false; hush(20) }
            com.hush.model.Request.HUSH_SOLO -> { allowSoloHush = true; hush(20) }
            com.hush.model.Request.STOP -> stopAll()
            com.hush.model.Request.SWEEP -> activateSensors()
            com.hush.model.Request.MODE -> try { mode = Mode.valueOf(r.arg ?: "") } catch (e: Exception) { HLog.d("REQUEST: bad mode ${r.arg}: $e") }
            com.hush.model.Request.SYNC -> syncCompasses()
            com.hush.model.Request.SENS -> setSensitivity(r.arg == "HIGH")
        }
        listener?.onLinkStatus(lastStatus)
        sendBoardDown(force = true)
    }

    /** Commander, after its own second (and on a request): what its screen shows, down to every phone. */
    private fun sendBoardDown(force: Boolean = false) {
        if (role != ROLE_COMMANDER) return
        val l = link ?: return
        if (peers.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBoardMs < 900L) return
        lastBoardMs = now
        val names = LinkedHashMap<String, String>().apply { put("A", localName); peers.values.sortedBy { it.letter }.forEach { put(it.letter, it.name) } }
        val fresh = boardEvents.filter { (k, _) -> now - (boardEventMs[k] ?: 0L) <= 10_000L && names.containsKey(k) }.values.toList()
        val status = listOf(probeStatus, rangingStatus).filter { it.isNotEmpty() }.joinToString("\n")
        val board = com.hush.model.Board(lastBrief, mode.name, lastRanking.map { com.hush.model.Board.Rank(it.letter, it.score, it.evidence, it.source) },
            names, fresh, status, discoveredText(discoveredPhones()),
            LinkedHashMap<String, Pair<Double, Double>>().apply { for (k in mapDots.keys) posMetres(k)?.let { put(k, it) } },
            mapMetresPerUnit, mapRotationDeg, closestText, wherePoint?.let { Triple(it.x, it.y, it.radiusM) }, warmthByLetter, knockDirResult, sensitiveHigh)
        l.sendDown(board.toJson())
    }

    /** Sensor: the commander's screen, once a second. */
    private fun onBoard(b: com.hush.model.Board) {
        try { mode = Mode.valueOf(b.mode) } catch (e: Exception) { HLog.d("Board: bad mode ${b.mode}: $e") }
        val peersChanged = b.names != boardNames
        boardNames.clear(); boardNames.putAll(b.names)
        boardStatus = b.status; boardDiscovered = b.discovered
        boardPos.clear(); boardPos.putAll(b.pos); boardScale = b.scale; boardRot = b.rotation
        if (b.pos.size >= 2) try { locator.setPositions(b.pos) } catch (e: Exception) { HLog.d("ERROR in locator positions: $e") }
        boardEvents.clear()
        for (e in b.events) {
            boardEvents[e.sensorId] = e
            // The sensor's export holds every phone's seconds too, once each.
            if (boardLoggedT[e.sensorId] != e.tMs) { boardLoggedT[e.sensorId] = e.tMs; sessionLog.add(e.toJson()) }
        }
        lastRanking = b.ranks.map { Rank(it.letter, it.score, it.evidence, null, 0, false, 0, it.source) }
        if (b.brief != lastBrief && b.brief.isNotEmpty()) HLog.d("BOARD brief: ${b.brief}")
        lastBrief = b.brief
        boardWhere = b.where
        knockDirResult = b.knockDir; knockDirBoardMs = SystemClock.elapsedRealtime()
        if (b.sensitiveHigh != sensitiveHigh) { sensitiveHigh = b.sensitiveHigh; HLog.d("SENSITIVITY from the commander: ${if (sensitiveHigh) "HIGH" else "NORMAL"}"); listener?.onClosest(closestText) }
        val warmChanged = b.warmth != warmthByLetter
        warmthByLetter = b.warmth
        if (b.closest != closestText || warmChanged) { closestText = b.closest; HLog.d("BOARD closest: ${b.closest.replace('\n', '|').ifEmpty { "(none)" }}"); listener?.onClosest(b.closest) }
        val l = listener ?: return
        if (peersChanged) l.onPeers(boardPeers())
        b.events.forEach { l.onEvent(it, b.names[it.sensorId] ?: "") }
        l.onRanking(lastRanking, lastBrief)
        l.onDiscovered(emptyList())
        l.onLinkStatus(lastStatus)
    }

    /** The discovered-phones list as the screen shows it (shared so the board can carry it to sensors). */
    fun discoveredText(phones: List<Discovered>): String {
        if (phones.isEmpty()) return ""
        val now = SystemClock.elapsedRealtime()
        return phones.joinToString("\n") { p ->
            val age = (now - p.lastSeenMs) / 1000
            buildString {
                append(p.suffix).append("  ").append(p.medianRssi).append(" dBm  ~").append("%.0f".format(p.roughMetres)).append(" m?  ").append(p.trend)
                if (p.awakeByProbe) append("  · woken by probe")
                if (p.battery >= 0) append("  · ").append(p.battery).append(" %")
                append(if (p.letter != null) "  → Sensor ${p.letter}" else "  · not joined yet")
                if (age > 5) append("  (${age} s ago)")
            }
        }
    }

    /** Map dots for the screen: the commander's own map, or the ones the commander sent down in the Fix. */
    fun screenDots(): Map<String, Pair<Float, Float>> {
        if (role == ROLE_COMMANDER) return mapDots
        val s = boardScale
        if (s != null && boardPos.isNotEmpty()) return boardPos.mapValues { (_, p) -> (0.5 + p.first / s).toFloat() to (0.5 - p.second / s).toFloat() }
        return receivedFix?.dots ?: emptyMap()
    }

    /** Sensor: the commander's map point (located source or crossing) from the Fix, or null. */
    fun screenSource(): Pair<Float, Float>? {
        if (role == ROLE_COMMANDER) return null
        // This phone's own locator first (step 3), in the commander's map frame.
        val lf = locator.fix; val s = boardScale
        if (lf != null && s != null && locator.hasClock(letter)) return (0.5 + lf.x / s).toFloat() to (0.5 - lf.y / s).toFloat()
        val f = receivedFix ?: return null
        if (!f.hasPoint || f.near != null || SystemClock.elapsedRealtime() - receivedFixMs > 60_000L) return null
        return f.x to f.y
    }

    // ---- Compass sync (27 Sep: three parallel phones read 98°, 238°, 245°; the odd one drew the fused arrow backwards) ----

    /** True after SYNC COMPASS in this session: the phones' headings agree with each other. */
    @Volatile var compassSynced = false
        private set

    /** This phone's heading as the screen shows it. */
    fun compassText(): String {
        val c = compass ?: return "Heading: off"
        if (!c.available) return "Heading: no rotation sensor on this phone"
        val how = if (c.gyro) "gyroscope" else "magnetic only"
        val sync = if (compassSynced) "synced %+.0f°".format(c.offsetDeg) else "NOT SYNCED: lay the phones parallel and tap SYNC"
        return "Heading %.0f° (%s)  ·  %s".format(c.headingDeg, how, sync)
    }

    /**
     * This phone hears the knocking itself well enough to point from where it lies: its own two-mic estimate, left/right
     * settled by turning or by the other phones, from ≥ 3 knocks. Drawn before the fused direction because the fused
     * one is a single compass direction for everyone, which is wrong for a phone off to the side of a nearby knock
     * (27 Sep 03:38: knocks beside A; B and C drew A's direction as parallel arrows instead of pointing at the knock).
     */
    fun ownHeardWell(own: com.hush.audio.KnockBearing.Estimate?): Boolean = own != null && own.resolved && own.knocks >= 3

    /**
     * SYNC COMPASS, pressed on any phone while all phones lie parallel (tops pointing the same way). The commander
     * takes every phone's current heading (its own and the latest from each sensor's event), picks the one closest
     * to all the others (so one disturbed compass is outvoted) and tells every phone to read that from now on.
     */
    fun syncCompasses(): String {
        if (role == ROLE_SENSOR) return if (requestUp(com.hush.model.Request.SYNC)) "Asked the commander to sync all compasses" else "Not connected to the commander yet"
        if (role != ROLE_COMMANDER) return "Not running"
        val c = compass
        if (c == null || !c.available) return "No compass on this phone"
        val now = SystemClock.elapsedRealtime()
        val headings = LinkedHashMap<String, Float>().apply { put("A", c.headingDeg) }
        for (p in peers.values) {
            val e = boardEvents[p.letter] ?: continue
            if (now - (boardEventMs[p.letter] ?: 0L) > 3000L) continue
            e.heading?.let { headings[p.letter] = it }
        }
        // Circular median: the heading with the smallest summed angular distance to all the others.
        val ref = headings.values.minByOrNull { h -> headings.values.sumOf { angDiffF(h, it).toDouble() } } ?: c.headingDeg
        val text = headings.entries.joinToString(" ") { "%s=%.0f°".format(it.key, it.value) }
        HLog.d("COMPASS SYNC: headings $text -> every phone reads %.0f°".format(ref))
        sessionLog.addRecord("compass_sync", mapOf("headings" to text, "reference" to ref))
        link?.sendDown(Command(Command.SYNC, heading = ref).toJson())
        applyCompassSync(ref, "own sync")
        lastSyncRef = ref
        if (placedByHand && pointedHeading.isEmpty()) {
            mapRotationDeg = ref; alignSource = "layout +y = phones' tops at SYNC"
            HLog.d("ALIGN: taped layout oriented by SYNC: map +y = heading %.0f°".format(ref))
        }
        val msg = if (headings.size < 2) "Only this phone's heading is known: no sensor reported one in the last 3 s"
                  else "Compasses synced to %.0f° (%s)".format(ref, text) + if (headings.size == 2) ". With two phones the commander's wins; three outvote a bad one." else ""
        setRangingStatus(msg)
        return msg
    }

    /** Every phone: from now on read [target] at the current orientation. Stored, so it survives a restart. */
    private fun applyCompassSync(target: Float, why: String) {
        val c = compass ?: return
        val before = c.headingDeg
        var d = target - before
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        c.offsetDeg = ((c.offsetDeg + d + 540f) % 360f) - 180f
        compassSynced = true
        // Bearing votes collected in the old frame would now point wrong: start them afresh.
        com.hush.audio.KnockBearing.reset(); ownArrowUntilMs = 0L
        if (role == ROLE_COMMANDER) { sharedBearing = null; sharedBearingMs = 0L; sharedHist.fill(0.0); peerBearing.clear() }
        HLog.d("COMPASS SYNC ($why): raw %.0f°, read %.0f°, now %.0f° (correction %+.0f°)".format(c.rawHeadingDeg, before, c.headingDeg, c.offsetDeg))
    }

    /** Long-press on SYNC COMPASS: drop this phone's correction. */
    fun clearCompassSync(): String {
        val c = compass ?: return "No compass"
        c.offsetDeg = 0f
        compassSynced = false
        com.hush.audio.KnockBearing.reset(); ownArrowUntilMs = 0L
        HLog.d("COMPASS SYNC cleared on this phone: raw %.0f°".format(c.rawHeadingDeg))
        return "Compass correction removed on this phone"
    }

    // ---- Nearby link ----

    override fun onLinkStatus(text: String) {
        lastStatus = if (role == ROLE_SENSOR && letter != "?") "$text · Sensor $letter" else text
        listener?.onLinkStatus(lastStatus)
    }

    /** Sensor: our route to the commander came up. Announce ourselves; the tree forwards it up. */
    override fun onRouteUp(hops: Int) {
        lastRoutedMs = SystemClock.elapsedRealtime()
        HLog.d("Route up with $hops hops, announcing (ble=$ownBleAddress)")
        link?.sendUp(com.hush.model.Join(localName, hops, ble = ownBleAddress).toJson())
    }

    override fun onRouteDown() {
        letter = "?"
        inHush = false
        listener?.onCountdown(-1)
    }

    override fun onDownstreamLost(endpointId: String) {
        // Sensors below the lost neighbour will re-route and re-join by themselves; the commander
        // drops anything it learned through that link.
        if (role == ROLE_COMMANDER) {
            val gone = peers.values.filter { it.endpointId == endpointId }.map { it.name }
            gone.forEach { peers.remove(it) }
            if (gone.isNotEmpty()) { HLog.d("Commander: lost $gone via $endpointId"); listener?.onPeers(peers.values.toList()) }
        }
    }

    /** Commander: a sensor announced itself (directly or relayed). Key is the sensor's name. */
    private fun onJoin(j: com.hush.model.Join, viaEndpoint: String) {
        if (role != ROLE_COMMANDER) return
        if (j.leaving) {
            peers.remove(j.name)
            listener?.onPeers(peers.values.toList())
            return
        }
        val assigned = lettersByName.getOrPut(j.name) { nextFreeLetter() }
        peers[j.name] = Peer(viaEndpoint, j.name, assigned)
        HLog.d("Commander: ${j.name} is Sensor $assigned, ${j.hops} hop(s) via $viaEndpoint (${peers.size} sensors) ble=${j.ble}")
        link?.sendDown(Command(Command.ASSIGN, letter = assigned, to = j.name, ble = ownBleAddress).toJson())
        // Give the sensor 3 s to start its responder side before we initiate.
        if (j.ble != null) { peerBleAddress[j.name] = j.ble; main.postDelayed({ startRadioTo(j.name, assigned) }, 3000) }
        if (inHush) link?.sendDown(Command(Command.HUSH, secondsLeft(), to = j.name).toJson())
        if (layoutByName.containsKey(j.name.takeLast(4))) HLog.d("LAYOUT: " + applyLayout())
        // Enough phones for positions: start the chirps now instead of at the next 60 s tick (a phone joining 16 s
        // after the commander started waited until ~75 s). 4 s lets the new sensor take its letter and open its mic.
        if (peers.size >= 2 && !rangingInProgress) main.postDelayed({ clockRound("${peers.size + 1} phones connected") }, 4000)
        listener?.onPeers(peers.values.toList())
    }

    private fun nextFreeLetter(): String {
        val used = lettersByName.values.toSet() + "A"
        return ('B'..'Z').map { it.toString() }.first { it !in used }
    }

    override fun onMessage(endpointId: String, text: String, fromUpstream: Boolean) {
        if (role == ROLE_SENSOR && !fromUpstream) {
            // From a sensor below us: not ours to read, forward toward the commander.
            link?.sendUp(text)
            return
        }
        when (val msg = Messages.parse(text)) {
            is Command -> {
                // Commands flow down the tree: forward first, then apply if addressed to us.
                if (role == ROLE_SENSOR) link?.sendDown(text)
                if (msg.to != null && msg.to != localName) return
                HLog.d("Command received: ${msg.type} sec=${msg.seconds} letter=${msg.letter} to=${msg.to}")
                when (msg.type) {
                    Command.ASSIGN -> {
                        letter = msg.letter ?: "?"
                        warnedUnassigned = false
                        onLinkStatus(lastStatus)
                        msg.ble?.let { addr -> HLog.d("Commander radio address $addr, answering as responder"); ble?.rangeAsResponder(addr, localName) }
                        main.removeCallbacks(tagRefresh); main.postDelayed(tagRefresh, 1000)   // re-advertise with flags + battery after the responder's plain tag
                    }
                    Command.HUSH -> startHushLocal(msg.seconds)
                    Command.STOP -> { inHush = false; main.removeCallbacksAndMessages(chirpToken); listener?.onCountdown(-1); onLinkStatus("Stopped by commander") }
                    Command.CHIRP -> msg.letter?.let { onChirpCommand(it) }
                    Command.SYNC -> msg.heading?.let { applyCompassSync(it, "commander's sync") }
                }
            }
            is com.hush.model.Join -> onJoin(msg, endpointId)
            is com.hush.model.Request -> onRequest(msg)
            is com.hush.model.Board -> if (role == ROLE_SENSOR) { link?.sendDown(text); onBoard(msg) }
            is com.hush.model.ChirpReport -> onChirpReport(msg)
            is com.hush.model.Placement -> onPlacement(msg)
            is com.hush.model.Fix -> {
                if (role == ROLE_SENSOR) { link?.sendDown(text); onFix(msg) }
            }
            is com.hush.model.OnsetReport -> {
                if (role == ROLE_SENSOR) {
                    // Every phone locates (27 Sep, step 3): the commander relays everyone's knock timings down the tree.
                    link?.sendDown(text)
                    locator.addReport(msg)
                    return
                }
                if (peers.values.none { it.letter == msg.letter }) { HLog.d("Onsets from unassigned sensor '${msg.letter}' ignored"); return }
                peerHeading[msg.letter] = msg.heading; locator.addReport(msg); feedClosest(msg, remote = true)
                if (msg.onsets.isNotEmpty()) link?.sendDown(text)
            }
            is com.hush.model.ClockRound -> if (role == ROLE_SENSOR) { link?.sendDown(text); onClockRound(msg) }
            is SensorEvent -> {
                val peer = peers.values.firstOrNull { it.letter == msg.sensorId }
                if (peer == null) { HLog.d("Event from unassigned sensor '${msg.sensorId}' ignored"); return }
                recordEvent(msg)
                boardEvents[msg.sensorId] = msg; boardEventMs[msg.sensorId] = SystemClock.elapsedRealtime()
                msg.windowAgeMs?.let { wa ->
                    val arrived = SystemClock.elapsedRealtime()
                    closestHeardUntil[msg.sensorId] = arrived - wa - RELAY_MS; closestHeardAt[msg.sensorId] = arrived
                    feedVoice(msg, arrived - wa - RELAY_MS)
                    closestTick()
                }
                listener?.onEvent(msg, peer.name)
            }
        }
    }
}
