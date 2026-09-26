# Hush — CLAUDE.md

Native Android app built at the iQOO × Reskilll Hackathon (Hyderabad, 26 Sep 2026).
**Deadline: demo-ready on the phones by 18:00 today (26 Sep).** Plan for hours, not days.
The team has **no prior Android experience**. You are writing essentially all of the code. Explain
what you are doing in one or two plain sentences before each change, and never assume we know
Android vocabulary — say "the file that declares permissions (AndroidManifest.xml)", not just "the manifest".

## What Hush is

Turns every phone on a building-collapse site into a listening sensor.

1. **Hush** — commander phone broadcasts a 20-second silence window to nearby phones (offline, Nearby Connections). All phones buzz once and show a countdown.
2. **Listen** — every phone (commander included) records raw mic audio continuously and runs an on-device sound classifier (YAMNet) to tell human sounds (voice, shout, tap, knock, whistle) from machinery (engine, power tool). Only the events inside the Hush window are scored.
3. **Rank** — commander lists every sensor by (loudness above its own noise floor × human-confidence), shows "strongest at Sensor B", highlights that row, and, once the map is built, draws an arrow from the commander's dot to it. This mirrors how professional USAR teams search.
4. **Locate (stretch, open-air only)** — chirp-based clock sync + TDOA to place a dot with an error circle. **Only if everything above ships early.** The go/no-go in Priorities still applies.

Everything runs on the phones. No internet, no cloud, no raw audio leaves the device. Only tiny event
messages (class, confidence, RMS, noise floor, rhythm, timestamps) are shared.

## Devices and environment (settled 26 Sep, 13:00)

- **Phones in hand:** two iQOO phones (commander + one sensor) and a third phone that only plays the recorded tapping. The app must work with **a minimum of 2 devices and any number above that**. The commander must also run a Hush window alone with zero sensors connected (used for single-phone testing).
- **Both iQOOs are connected to this laptop by USB cable.** `./gradlew installDebug` installs on both.
- **This laptop had no Android SDK, no JDK, and no Android Studio.** Decision: install **JDK 17 + Android SDK command-line tools + platform-tools only**, build from the terminal. No Android Studio.
- **GitHub Actions** builds `app-debug.apk` on every push to `main` and attaches it as a downloadable artifact. No signing, no Releases page. This is the fallback if the laptop breaks.
- **Green Light / Red Light:** Red Light (phone-only time) is **testing only**. Never write code from a phone. Every laptop window must end with a working install on both phones and a push.
- **Demo radio setup (3 taps per phone):** airplane mode ON, then Bluetooth ON, then Wi-Fi radio ON without joining any network. Nearby Connections uses Bluetooth + Wi-Fi Direct; the Wi-Fi radio must be on for a reliable link even though there is no internet.

## Hard rules

- **Ask before touching build files**: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle-wrapper.properties`, `AndroidManifest.xml`. Propose the exact diff, then wait. **Exception, agreed 26 Sep:** the initial project scaffold (all Gradle files, the permissions file, and the skeleton app) is proposed and approved **once, as a single block**. After that the rule applies per change.
- **Never add a dependency without saying why and which version.** Prefer what is already in the project.
- **Never send raw audio over the network.** Only the event data class below.
- **No cloud, no HTTP, no analytics, no Firebase.** The demo runs with cellular and Wi-Fi internet off.
- **Keep it simple over correct-in-general.** One module, one Activity, a few Kotlin files. No clean-architecture layers, no DI frameworks, no Compose unless already set up.
- **Small steps.** One feature per session; each step must compile and run before the next. If something is uncertain, add a log line and let us test on the device rather than guessing.
- **Do not refactor working code** unless asked. Working and ugly beats broken and tidy.
- When you finish a step, tell us exactly: what to tap on the phone to test it, and what we should see.

## Tech decisions (already made — do not re-open)

| Concern | Decision |
|---|---|
| Language / UI | Kotlin, XML layouts (View system), single `MainActivity` + a `SensorService` foreground service |
| Min / target SDK | minSdk 29, targetSdk 35 |
| Networking | Google **Nearby Connections API** (`com.google.android.gms:play-services-nearby`), strategy `P2P_STAR`. Commander advertises, sensors discover and connect. |
| Roles | **The commander is also a sensor** (it is always Sensor A). It runs the same mic + classifier code and appears in its own ranked list. |
| Sensor letters | Assigned by the commander on connect, **keyed by the phone's advertised device name**. A sensor that drops and reconnects gets the **same letter and the same map dot**. New phones get the next free letter (A, B, C, D, …). |
| Audio capture | `AudioRecord`, source `MediaRecorder.AudioSource.UNPROCESSED` (fallback `VOICE_RECOGNITION`), 48 000 Hz, mono, PCM 16-bit. Check `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`. Keep the 48 kHz stream for TDOA; downsample to 16 kHz for the classifier. |
| Listening | **Always on while the app is in a role.** The RMS bar is live before any Hush. The 20 s Hush window only marks which events are scored. |
| Noise floor | Each phone measures its **own noise floor as the mean RMS over the 3 s before the Hush window starts** and sends it with every event. Ranking uses `max(0, rms - floor)`. This is what makes loudness comparable across phones. |
| Classifier | Stock **YAMNet** TFLite (`yamnet.tflite`, 16 kHz, 0.975 s window / 15 600 samples) via LiteRT / TensorFlow Lite. CPU first. GPU/NNAPI delegate is a stretch, added as a flag. **No fine-tuning.** |
| Class buckets | **Starting list, to be tuned on the phone** (see Debug view). HUMAN = Speech, Shout, Yell, Screaming, Children shouting, Whistling, Whistle, Tap, Knock, Thump/thud, Wood, Bang, Slap/smack, Tick, Tick-tock, Clapping, Finger snapping. MACHINE = Engine, Power tool, Tools, Machine, Vehicle, Motor vehicle (road), Drill, Hammer. Everything else = OTHER. Bucket score = sum of member class scores. Match names against `yamnet_class_map.csv` exactly and log any name that does not match. |
| Debug view | The sensor screen shows the **top 5 raw YAMNet classes with scores** every second. We use it in the first test window to decide which classes the recorded tapping actually triggers, then fix the HUMAN list. |
| Rank score | Per window: `max(0, rms - floor) * human`. Per sensor per Hush: **mean of the 5 best windows**. Highest wins. |
| Haptics | `Vibrator` / `VibratorManager`. **One long buzz at Hush start and one at Hush end. No per-second ticks** — the buzz goes into the phone's own mic and "Tick" is a HUMAN class. The countdown is on screen only. |
| Map / arrow | Commander has a **blank square canvas**. Tap a sensor row, then tap where that phone sits; a dot with its letter appears. The arrow is drawn **from the commander's own dot toward the strongest sensor**. Until the map exists (or if the arrow ever looks wrong on stage), the strongest row is simply highlighted large and coloured. Built **after Export**. |
| Rhythm | Built **after the map**. Simplest thing: onsets from the RMS envelope, group taps by the gaps between them, report "3-2" style. Shown in the brief only if detected. |
| Accelerometer | Stretch. `SensorManager`, `TYPE_ACCELEROMETER`, high-pass filtered magnitude, separate "seismic" channel. |
| Rescuer brief | Template string: `"Human tapping · 92% · strongest at Sensor B"` plus `" · rhythm 3-2"` when rhythm is detected. On-device LLM only if Locate is done and time remains (it will not be). |
| Export log | **One JSON-lines file per session** in `Downloads/`, named `hush-<date>-<time>.jsonl`. First line is a header with sensor letters, device names and map dots; every following line is one `SensorEvent` exactly as it crossed the network. Laptop shows it via Office Kit. |
| Persistence | None beyond the session log file. No database. |

## Event contract (the only thing that crosses the network)

```kotlin
// Serialised as one JSON line per message. Keep it under 200 bytes.
data class SensorEvent(
    val sensorId: String,      // "A", "B", "C" — assigned by commander on connect; "A" is always the commander
    val tMs: Long,             // sender's SystemClock.elapsedRealtime()
    val rms: Float,            // 0..1 raw RMS of the last 1 s window, band-passed 200–3000 Hz
    val floor: Float,          // 0..1 this phone's noise floor, measured over the 3 s before the Hush window
    val human: Float,          // 0..1 HUMAN bucket score
    val machine: Float,        // 0..1 MACHINE bucket score
    val topClass: String,      // e.g. "Knock"
    val rhythm: String? = null,// e.g. "3-2" if a repeating tap pattern is detected
    val chirpTs: Long? = null  // when a sync chirp was heard (Locate only)
)

// Commander → sensors
data class Command(
    val type: String,          // "ASSIGN" | "HUSH" | "STOP" | "CHIRP"
    val seconds: Int = 20,     // HUSH only
    val letter: String? = null // ASSIGN only: the sensor's letter
)
```

The top-5 debug classes stay on the sensor screen and are **not** sent over the network.
Rank score on the commander = mean of the 5 best `max(0, rms - floor) * human` per sensor per Hush window.

## Planned file layout (single module `app`)

```
app/src/main/java/com/hush/
  MainActivity.kt          // role picker (Commander / Sensor), then the right screen
  ui/CommanderScreen.kt    // sensor list ranked, big HUSH button, countdown, map canvas + arrow, Export log
  ui/SensorScreen.kt       // RMS meter, top class, top-5 debug list, countdown
  net/NearbyLink.kt        // advertise / discover / connect / send / receive; callbacks to UI
  audio/AudioCapture.kt    // AudioRecord loop → 1 s windows → callbacks with 48k and 16k buffers
  audio/Classifier.kt      // loads yamnet.tflite, returns bucket scores + top class + top 5
  audio/Dsp.kt             // RMS, band-pass, downsample, noise floor, (later) onsets, chirp, GCC-PHAT
  model/Events.kt          // SensorEvent, Command, JSON encode/decode
  log/SessionLog.kt        // append events, export JSONL to Downloads
app/src/main/assets/yamnet.tflite
app/src/main/assets/yamnet_class_map.csv
.github/workflows/build.yml  // debug APK artifact on every push to main
```

## Permissions needed (declare and request at runtime)

`RECORD_AUDIO`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`
(API 33+), `ACCESS_FINE_LOCATION` (API ≤ 32 for Nearby), `VIBRATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE`. Nearby also needs `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`BLUETOOTH`, `BLUETOOTH_ADMIN` on older APIs.

## Priorities and go/no-go (deadline 18:00 today)

Build strictly in this order. Each block must run on both phones before the next starts.

0. **Toolchain** — JDK 17, SDK command-line tools, platform-tools, `adb devices` lists both iQOOs. Project scaffold approved as one block. GitHub Actions workflow pushed.
1. **Hello mic** — one screen, mic permission, live RMS bar, top-5 debug list. Installed on both phones.
2. **Hush** — Nearby link, ASSIGN + HUSH commands, one buzz at start and end, on-screen countdown on all phones. Commander works alone with zero sensors.
3. **Listen + Rank** — YAMNet on every phone, events to commander, noise floor, ranked list with the strongest row highlighted, template brief. **This is the shippable product.**
4. **Export log** — JSONL to Downloads, shown on the laptop via Office Kit.
5. **Map + arrow** — tap-to-place canvas, arrow from the commander's dot to the strongest sensor.
6. **Rhythm** — onset grouping, "rhythm 3-2" appended to the brief.
7. **Only if 1–6 are done and rehearsed with time to spare:** chirp go/no-go. Sensor plays a 4 kHz chirp, others cross-correlate to get clock offsets. Residual < 2 ms in two consecutive tries → GCC-PHAT TDOA + least-squares dot on the map canvas. Otherwise Locate is cut.
8. Not today: NPU delegate, on-device LLM brief, accelerometer.

## Build & run

```bash
# laptop (Green Light)
./gradlew installDebug          # builds and installs on every connected phone
adb devices                     # should list both iQOOs
adb logcat -s Hush              # all our logs use tag "Hush"

# Red Light = test on the phones only. Do not edit code from a phone.
# Fallback if the laptop dies: download app-debug.apk from the latest GitHub Actions run and sideload it.
```

Use `Log.d("Hush", ...)` for every event received, every state change, and every error. Never
swallow an exception silently.

## Demo script the code must support (3 minutes)

0:00 two (or more) phones on taped spots on the floor, airplane mode + Bluetooth + Wi-Fi radio on, a third phone under a box plays recorded tapping. Commander has already tapped each phone's spot on the map.
0:20 tap HUSH on the commander → all phones buzz once, 20 s countdown on screen, room quiet.
0:40 commander shows `Human tapping · 92% · strongest at Sensor B`, Sensor B row highlighted, arrow points at the box.
1:10 (if rhythm shipped) brief gains `· rhythm 3-2`.
1:40 move the box, repeat; laptop mirrors the screen via Office Kit and shows the exported log.
2:30 one line of rescuer brief, then the limits slide.

The app must survive: a sensor disconnecting and reconnecting (same letter, same dot), the screen turning off, and being
backgrounded for 30 s. Test these before every rehearsal.

## What we will not claim

No "dot on the map" under rubble. Not a replacement for seismic kits or search dogs. Never overrides
emergency communications. If asked to add anything that implies these, push back.
