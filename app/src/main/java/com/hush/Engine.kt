package com.hush

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.hush.audio.AudioCapture
import com.hush.audio.Classifier
import com.hush.audio.RhythmTracker
import com.hush.audio.TapDetector
import android.os.VibrationAttributes
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

    /** Everything one second of this phone's own hearing produced. */
    data class Window(
        val rms: Float,
        val floor: Float,
        val gain: Float,
        val tap: TapDetector.Result,
        val rhythm: RhythmTracker.Result,
        val cls: Classifier.Result?,
        val event: SensorEvent
    )

    const val LABEL_TAPPING = "HUMAN TAPPING"
    const val LABEL_TAPPING_MAYBE = "TAPPING?"
    const val LABEL_VOICE = "HUMAN VOICE"
    const val LABEL_MACHINE = "MACHINERY"
    const val LABEL_QUIET = "quiet"

    /**
     * The headline for one second, most trusted evidence first:
     * rhythm over 8 s, then a single tap corroborated by YAMNet's impact bucket, then voice, then machinery.
     */
    fun fuseLabel(tap: TapDetector.Result, rhythm: RhythmTracker.Result, cls: Classifier.Result?): String {
        val voice = cls?.human ?: 0f
        val impact = cls?.impact ?: 0f
        val machine = cls?.machine ?: 0f
        return when {
            rhythm.score >= 0.9f -> LABEL_TAPPING
            tap.taps > 0 && (rhythm.score >= 0.5f || impact >= 0.10f) -> LABEL_TAPPING_MAYBE
            voice >= 0.30f -> LABEL_VOICE
            machine >= 0.35f -> LABEL_MACHINE
            tap.taps > 0 -> LABEL_TAPPING_MAYBE
            else -> LABEL_QUIET
        }
    }

    data class Peer(val endpointId: String, val name: String, val letter: String)

    interface Listener {
        fun onOwnWindow(w: Window)
        fun onLinkStatus(text: String)
        /** Seconds left in the Hush window, or -1 when no window is running. */
        fun onCountdown(secondsLeft: Int)
        /** Commander only: an event from any sensor, including itself. */
        fun onEvent(event: SensorEvent, peerName: String)
        /** Commander only: the connected sensor list changed. */
        fun onPeers(peers: List<Peer>)
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
                    if (role == ROLE_COMMANDER) l.onPeers(peers.values.toList())
                }
            }
        }

    private var appContext: Context? = null
    private var capture: AudioCapture? = null
    private var classifier: Classifier? = null
    private val tapDetector = TapDetector(AudioCapture.SAMPLE_RATE)
    private val rhythmTracker = RhythmTracker()
    private var link: NearbyLink? = null
    private var localName = "?"
    private var lastStatus = "Not started"

    // Noise floor: mean RMS of the last few seconds before a Hush window starts.
    private val recentRms = ArrayDeque<Float>()
    private const val FLOOR_SECONDS = 3
    @Volatile private var floor = 0f

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
        letter = if (newRole == ROLE_COMMANDER) "A" else "?"
        HLog.d("Engine start role=$newRole name=$localName")

        try {
            classifier = Classifier(context)
        } catch (e: Exception) {
            HLog.d("ERROR loading classifier: $e")
        }
        capture = AudioCapture(context, this).also {
            it.debugWav = File(context.filesDir, "debug.wav")   // debug capture, see CLAUDE.md
            it.start()
        }
        link = NearbyLink(context, localName, this).also {
            if (newRole == ROLE_COMMANDER) it.startCommander() else it.startSensor()
        }
    }

    fun stop() {
        HLog.d("Engine stop")
        capture?.stop(); capture = null
        classifier?.close(); classifier = null
        link?.stop(); link = null
        peers.clear()
        recentRms.clear()
        rhythmTracker.reset()
        inHush = false
        role = null
        letter = "?"
        lastStatus = "Not started"
    }

    // ---- Hush window ----

    /** Commander: start a Hush window on every phone including this one. */
    fun hush(seconds: Int = 20) {
        if (role != ROLE_COMMANDER) return
        HLog.d("HUSH pressed: $seconds s to ${peers.size} sensors")
        link?.broadcast(Command(Command.HUSH, seconds).toJson())
        startHushLocal(seconds)
    }

    private fun startHushLocal(seconds: Int) {
        floor = if (recentRms.isEmpty()) 0f else recentRms.average().toFloat()
        inHush = true
        hushEndMs = SystemClock.elapsedRealtime() + seconds * 1000L
        HLog.d("Hush window started: $seconds s, noise floor %.5f".format(floor))
        vibrate(longArrayOf(0, 400, 120, 400, 120, 600))   // three strong pulses at the start
        tick()
    }

    private fun secondsLeft(): Int = ceil((hushEndMs - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)

    private fun tick() {
        if (!inHush) return
        val left = secondsLeft()
        if (left <= 0) {
            inHush = false
            HLog.d("Hush window ended")
            vibrate(longArrayOf(0, 250, 100, 250))            // two short pulses at the end
            listener?.onCountdown(-1)
        } else {
            listener?.onCountdown(left)
            main.postDelayed({ tick() }, 250)
        }
    }

    /**
     * [pattern] is off/on/off/on... in ms. Full amplitude, and tagged as an alarm so the phone does not
     * scale it down the way it does for ordinary app haptics (the first version was barely noticeable).
     */
    private fun vibrate(pattern: LongArray) {
        val ctx = appContext ?: return
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val amplitudes = IntArray(pattern.size) { if (it % 2 == 1) 255 else 0 }
            val effect = if (vib.hasAmplitudeControl()) VibrationEffect.createWaveform(pattern, amplitudes, -1)
                         else VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vib.vibrate(effect)
            }
            HLog.d("vibrate ${pattern.contentToString()} amplitudeControl=${vib.hasAmplitudeControl()}")
        } catch (e: Exception) {
            HLog.d("vibrate failed: $e")
        }
    }

    // ---- Own microphone ----

    override fun onWindow(pcm48k: ShortArray, n48: Int, pcm16k: FloatArray, n16: Int, rms: Float) {
        val now = SystemClock.elapsedRealtime()
        val tap = tapDetector.analyse(pcm48k, n48, now - 1000)
        rhythmTracker.add(tap.onsetsAbsMs)
        val rhythm = rhythmTracker.evaluate(now)
        val cls = classifier?.classify(pcm16k, n16)
        val label = fuseLabel(tap, rhythm, cls)
        if (!inHush) {
            recentRms.addLast(rms)
            while (recentRms.size > FLOOR_SECONDS) recentRms.removeFirst()
        }
        val event = SensorEvent(
            sensorId = letter,
            tMs = now,
            rms = rms,
            floor = if (inHush) floor else (if (recentRms.isEmpty()) 0f else recentRms.average().toFloat()),
            human = cls?.human ?: 0f,
            machine = cls?.machine ?: 0f,
            topClass = cls?.topClass ?: "-",
            taps = tap.taps,
            tapScore = tap.score,
            impact = cls?.impact ?: 0f,
            rhythmScore = rhythm.score,
            rhythm = rhythm.rhythm,
            label = label
        )
        val w = Window(rms, event.floor, classifier?.lastGain ?: 1f, tap, rhythm, cls, event)
        HLog.d("window id=$letter hush=$inHush label=$label rms=%.4f floor=%.4f taps=%d peak=x%.0f rhythm=%.1f/%s voice=%.2f impact=%.2f machine=%.2f | %s".format(
            rms, event.floor, tap.taps, tap.peakRatio, rhythm.score, rhythm.rhythm, event.human, event.impact, event.machine,
            cls?.top5?.joinToString(", ") { (name, s) -> "%s %.2f".format(name, s) } ?: "-"))
        main.post {
            listener?.onOwnWindow(w)
            when (role) {
                ROLE_SENSOR -> link?.broadcast(event.toJson())
                ROLE_COMMANDER -> listener?.onEvent(event, localName)
            }
        }
    }

    // ---- Nearby link ----

    override fun onLinkStatus(text: String) {
        lastStatus = text
        listener?.onLinkStatus(text)
    }

    override fun onPeerConnected(endpointId: String, name: String) {
        if (role != ROLE_COMMANDER) return
        val assigned = lettersByName.getOrPut(name) { nextFreeLetter() }
        peers[endpointId] = Peer(endpointId, name, assigned)
        HLog.d("Commander: $name is Sensor $assigned (${peers.size} connected)")
        link?.send(endpointId, Command(Command.ASSIGN, letter = assigned).toJson())
        if (inHush) link?.send(endpointId, Command(Command.HUSH, secondsLeft()).toJson())
        listener?.onPeers(peers.values.toList())
    }

    override fun onPeerDisconnected(endpointId: String) {
        if (role != ROLE_COMMANDER) return
        peers.remove(endpointId)
        listener?.onPeers(peers.values.toList())
    }

    private fun nextFreeLetter(): String {
        val used = lettersByName.values.toSet() + "A"
        return ('B'..'Z').map { it.toString() }.first { it !in used }
    }

    override fun onMessage(endpointId: String, text: String) {
        when (val msg = Messages.parse(text)) {
            is Command -> {
                HLog.d("Command received: ${msg.type} sec=${msg.seconds} letter=${msg.letter}")
                when (msg.type) {
                    Command.ASSIGN -> {
                        letter = msg.letter ?: "?"
                        onLinkStatus("Connected as Sensor $letter")
                    }
                    Command.HUSH -> startHushLocal(msg.seconds)
                    Command.STOP -> { inHush = false; listener?.onCountdown(-1) }
                }
            }
            is SensorEvent -> {
                val name = peers[endpointId]?.name ?: endpointId
                listener?.onEvent(msg, name)
            }
        }
    }
}
