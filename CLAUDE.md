# Hush — CLAUDE.md

Native Android app built at the iQOO × Reskilll Hackathon (Hyderabad, 26 Sep 2026). The 18:00 demo deadline has
passed; the team decided to keep building ("forget about the time schedule"). This file is the single source of truth
for what the app does, what was measured on the real phones, what failed and why, and what is next.
The team has **no prior Android experience**. You are writing essentially all of the code. Explain what you are doing in
one or two plain sentences before each change, and never assume we know Android vocabulary — say "the file that declares
permissions (AndroidManifest.xml)", not just "the manifest".

## What Hush is (current behaviour)

Turns every phone on a building-collapse site into a listening sensor. Everything runs on the phones, offline. No raw
audio ever crosses the network; only small JSON events (see contract below).

1. **Roles.** One phone is COMMANDER (it is also Sensor A). Every other phone is a SENSOR. Sensors are lettered B, C, D…
   by the commander, keyed by the phone's name (`I2501-<last 4 of ANDROID_ID>`), so a reconnecting phone keeps its letter.
   Every screen shows the letter **and** the name suffix so a letter can be matched to a physical phone.
2. **Mesh link.** Nearby Connections, cluster mode, tree rooted at the commander. A sensor keeps one upstream link
   (commander if visible, else a sensor that already has a route) and relays for anyone below it. No internet needed.
3. **Listening, always on.** Each phone captures 48 kHz stereo (two real mics), computes per second: band-passed RMS,
   YAMNet buckets (voice / impact / machinery), knock onsets (`TapDetector`), an 8 s rhythm score (`RhythmTracker`),
   accelerometer movement, battery, compass heading, GPS fix if any. It fuses those into one headline:
   **HUMAN TAPPING → HUMAN VOICE → MACHINERY → quiet**, with a ⚠moving flag and a "✓felt" tag.
4. **Live ranking.** The commander scores every sensor continuously: quality-gated, decayed (15 s) loudness above a
   rolling-median noise floor, divided by a chirp-calibrated mic gain, times the evidence for the chosen listen mode
   (TAPPING / VOICE / ANY). The brief ("Live · Human tapping · 90% · strongest at Sensor B · rhythm steady") and the
   starred row update every second. Multiple knock sources are separated by tempo/pattern ("Source 1 … | Source 2 …").
5. **HUSH = silence call.** One button: chirp ranging (~8 s, "chirps…" on every screen) → 20 s window with a strong
   three-pulse buzz and a quiet double beep at start, two pulses and a beep at the end → "Window ·" brief. STOP ends the
   window on every phone and cancels chirps. HUSH refuses with zero sensors unless long-pressed.
6. **Map + arrow.** Sensors are placed on a square map automatically by acoustic ranging (or by hand as a fallback).
   North comes, in order of preference, from the two-mic direction of the chirps, from the commander's own walk, or from
   how sensors were carried out. A compass arrow points at the strongest sensor with the distance in metres; the
   commander's dot walks between rangings by step counting.
7. **Radio ranging.** Bluetooth sessions run silently to every sensor. Channel Sounding is refused by the phones so far
   (see Status); signal-strength ranging works but is far too coarse and is shown with "?" and never trusted.
8. **Export.** One JSON-lines file per session to `Downloads/` (header with sensors, dots, mode; every event; hush,
   ranging, ranking and stop records).

## Devices and environment

- **Phones:** three iQOO I2501 (vivo), Android 16 / API 36, identical. A fourth phone can play a tapping recording but is
  not needed: knuckle knocks on a table are the test signal. The app works with 2 phones and scales to any number; the
  commander can run alone (long-press HUSH).
- **All three phones on USB.** Serials: `10BFCG0ZHP00204`, `10BFAU14Q6000XR`, `10BFC41SUJ001UZ` (letters change with
  connection order; read the name suffix on screen).
- **Laptop toolchain (installed 26 Sep, no Android Studio):** JDK 17 at `C:\Android\jdk17`, SDK at `C:\Android\Sdk`
  (cmdline-tools, platform-tools, platforms 35 and 36, build-tools 35). `JAVA_HOME`, `ANDROID_HOME` and PATH are set for
  the Windows user; older shells need `export JAVA_HOME=/c/Android/jdk17 ANDROID_HOME=/c/Android/Sdk`.
- **GitHub Actions** builds `app-debug.apk` on every push to `main` (artifact, no signing). Fallback if the laptop dies.
- **The phones drop app logs** (`persist.sys.log.ctrl=no`, not changeable over adb; the dialer code did not take either).
  All logging goes through `HLog` to logcat **and** to the app's private file. Read it with
  `adb -s <serial> shell "run-as com.hush cat files/hush.log"`. Every window, command, chirp, ranging round, ranking and
  radio event is in there.
- **vivo remote-control app** (Office Kit, used to control the laptop) sits on top of Hush on the phones. Force Hush to
  the front with `adb shell am start -n com.hush/.MainActivity` before tapping by coordinates. Role picker button centres
  at 1440-wide: COMMANDER ≈ (540, 646), SENSOR ≈ (720, 1241). `uiautomator dump` is flaky on these phones.
- **Demo radio setup (3 taps per phone):** airplane mode ON, then Bluetooth ON, then Wi-Fi radio ON without joining a
  network. Nearby needs both radios even with no internet.

## Rules

- **Build files no longer need approval (lifted 26 Sep 17:45 by the team).** Change `build.gradle.kts`,
  `settings.gradle.kts`, `gradle/libs.versions.toml`, wrapper files and `AndroidManifest.xml` directly; say what changed
  and why in the commit and the reply. Dependencies still get a stated reason and version.
- **Never send raw audio over the network.** Only the JSON messages below. Debug exception: each phone writes its first
  90 s of raw 48 kHz audio to private `files/debug.wav` for laptop analysis over USB (`WavStats.java`, `Cadence2.java`,
  `TapGrid.java` in the session scratchpad replay the detector offline). Never leaves the phone otherwise.
- **No cloud, no HTTP, no analytics, no Firebase.**
- **Keep it simple.** One module, one Activity, one foreground service, one `Engine` object. No DI, no Compose.
- **Small steps; every build installs on all three phones and is committed and pushed before the next step.**
- **Do not refactor working code** unless asked. Log every event, state change and error; never swallow exceptions.
- **Report what to tap and what to expect** after every step.

## Tech decisions and measured facts

| Concern | Current decision (with what was measured) |
|---|---|
| Language / UI | Kotlin, XML Views, `MainActivity` (role picker + screens) + `SensorService` (foreground, microphone type) + `Engine` singleton owning everything. |
| SDKs | minSdk 29, targetSdk 35, **compileSdk 36** (Android 16 Ranging API). |
| Libraries | AGP 8.13.2, Kotlin 2.4.20 (Nearby 19.5.0 needs ≥ 2.4), Gradle 8.14.3, appcompat 1.7.1, core-ktx 1.13.1, play-services-nearby 19.5.0, tensorflow-lite 2.17.0. JSON via `org.json`. No location library (framework `LocationManager`). |
| Link | `NearbyLink` v2, `Strategy.P2P_CLUSTER`, service id `com.hush.v2`. Advertised name `C|<name>` / `S|<name>|<hops>`; a sensor advertises only once routed (no loops). Up: events, chirp/placement reports, `join`/`leave`. Down: commands (`ASSIGN` carries `to=<name>` and the commander's Bluetooth address). The commander keys sensors by **name**. One-hop verified from the laptop; multi-hop needs a hall test. |
| Audio capture | `AudioRecord` **stereo** (both mics are distinct: 78 % channel difference), 48 kHz, 16-bit. Source order VOICE_RECOGNITION → MIC → UNPROCESSED (UNPROCESSED measured ~10 dB quieter). Room level is ≈ −60 dB RMS on these phones. Mic 0 feeds everything; mic 1 only the chirp ring buffer. 8 s ring buffer per mic with an absolute sample counter. |
| Classifier | Stock YAMNet TFLite (mediapipe float32, input [15600], output [1,521], **no embedding output**). Input peak-normalised with gain capped at ×20 (×1000 turned room rumble into "Vehicle"). Buckets (exact display names): VOICE = Speech, Child speech, Conversation, Narration, Shout, Yell, Children shouting, Screaming, Whistling, Whistle. IMPACT (corroboration only) = Tap, Knock, Hammer, Hands, Thump/thud, Wood, Bang, Slap/smack, Clapping, Finger snapping, Tick, Tick-tock, Dishes, Cutlery, Chop, Chopping, Percussion, Drum, Wood block, Basketball bounce, Bouncing. MACHINE = Engine (+ light/medium/heavy/starting), Idling, Power tool, Tools, Drill, Jackhammer, Sawing, Chainsaw, Vehicle, Motor vehicle (road), Motorcycle, Aircraft, Helicopter. YAMNet is reliable for voice and useless for knocks (calls them Dishes/Stir/Hammer at random). |
| Tap detector | 10 ms frame energies on mic 0. Onset = frame ≥ **×3** the window median AND ≥ ×2 the loudest of the previous 3 frames; 80 ms refractory; ≤ 8/s; **ring-down**: energy 100 ms later ≤ 60 % of the onset frame (knocks measured 3–27 %, coughs/syllables stay loud). Tuned from two 90 s recordings: quiet ×2–6, speech ×2–5, knuckle knocks ×11–28, soft fingertip ×3–14. Onset times are sample-accurate for later TDOA. |
| Rhythm | `RhythmTracker`, 8 s history, audio only. Steady = ≥ 3 onsets with gap CV < 0.45 at any tempo (people knock at 1/s or 2–3/s; 600 ms grouping once merged fast knocking into one endless group). Patterns ("3-2") from group sizes repeating ≥ 2×, rotated to start with the largest group. Cap 40 onsets/8 s. Reports `tempoMs` (signature). **Single impacts never make a headline.** |
| Self-noise | The app's own start buzz (3 × 700 ms pulses rattle the phone), beeps and chirps once produced phantom "Source 1 / Source 2". Now: rhythm reset at window start; onsets ignored 3.5 s after start and 1.5 s after any chirp; those seconds carry zero weight in scoring; the noise floor never samples them. |
| Noise floor | Per phone, **rolling median of the last 30 quiet, still, chirp-free seconds**. (A 3 s mean sampled during chirps once zeroed two sensors' scores.) |
| Mic gain | Chirp-calibrated: received chirp RMS × distance must match across phones for the same chirp; the ratio to the commander is the sensor's gain (median of up to 12 samples, clamped 0.2–5). Ranking divides loudness by it. Recomputed every ranging round; logged as `Mic gain: sensor B = 1.3×`. |
| Live score | Per event: quality (0 if moving or self-noise, else 1) × max(0, rms − floor) / gain × evidence; decayed with τ = 15 s. Evidence: TAPPING = rhythm score, VOICE = YAMNet voice, ANY = max. Window score (HUSH) = mean of the 5 best seconds after the first 3.5 s. |
| Sources | Sensors with rhythm ≥ 0.9 grouped by same pattern name or tempo within 25 %; numbered strongest first. |
| Accelerometer | `AccelChannel`, 200 Hz (fastest rate needs a permission and crashed the service), high-passed magnitude. **Jolts are never onsets** (they flooded the tracker on a handled phone). Jolts only tag heard tapping "✓felt" (within 100 ms of an audio onset) and set ⚠moving (rms > 0.25 m/s²; at rest 0.007, handled 0.4–3). Movement never hides a label: in a collapse everything trembles. |
| Hush signal | Start: 3 × 700 ms pulses at full amplitude, alarm-class vibration, + double 2.5 kHz beep at **10 %** alarm volume. End: 2 × 400 ms + 1.8 kHz beep. Vibration strength is capped by the phone; Settings → Sound & vibration is the last lever. |
| Acoustic ranging | `Chirp.kt` 80 ms 2–6 kHz Hann sweep at 90 % alarm volume; matched filter with parabolic sub-sample peak (≈ 200 ms per search). Chirps A, B, C 1.8 s apart. Pair distance `D = c/2·[(t_i(j)−t_i(i)) − (t_j(j)−t_j(i))]/fs + 0.12 m` (clock offsets cancel). Measured: AB 0.91 (real ≈ 1.0), AC 0.56 (real ≈ 0.5), later rounds 0.54–0.58 for the same layout. Detection strength 95–1780× vs threshold 5. Self-checks: timing filter ±0.35 s (a wrong peak once produced 59.7 m), triangle inequality and ≤ 30 m, one retry of lost pairs, **two rounds within 20 % before the map moves**. Only the first three letters form the map (N > 3 solver not built). |
| Map frame | B origin, C on +x, A (commander) moves inside; scale fixed at first ranging (1.6 × the largest side). A's dot moves by step counting between rangings. A settled sensor (moved, then still 3 s) triggers a re-ranging. |
| North | Ranging alone cannot know rotation. Sources, best first: (1) **two-mic direction of arrival** of B's and C's chirps at the commander (sub-sample inter-mic delay; mic spacing solved against the triangle's known angle, then held as a median; skipped when phones < 0.8 m apart; rotation smoothed over 5 rounds); (2) the commander's walk (A's shift on the map vs compass bearing walked; moves > 10 m ignored); (3) placement walk (step detector + compass on carried-out sensors); (4) manual Place buttons. First DoA run: spacing 0.10 m, angles 44°/8° vs true 38°, spread 1°. Physical direction test still failing at 50 cm spacing (near field) — needs ≥ 1 m. |
| Compass arrow | `ArrowView` + rotation-vector `Compass`; angle = mapBearing(A→target) + rotation − heading; label shows distance and which source aligned north. |
| Radio ranging | `BleRanging` (Android 16 `RangingManager`). Capabilities on the I2501: CS enabled, RSSI enabled, UWB/RTT absent; own address read from the capabilities object's `toString`. Sensors advertise a connectable BLE tag (service UUID `0000A5A5-…`, data = name suffix); the commander scans for the tag to learn the sensor's **live** (rotating) address, opens a GATT link, then initiates; the sensor learns the commander's live address from its GATT server and answers it. **Result so far: CS opens, starts and closes with reason 3 (UNSUPPORTED) within 1 ms every time**, even over an open link with the responder ready; RSSI ranging then runs continuously but reads 6–14 m for phones 0.5 m apart. RSSI is displayed with "?" and never used to drop a chirp round. Latest build requests a one-time pairing and retries CS once bonded (untested). |
| GPS | `Gps.kt`, framework `LocationManager`; `lat/lon/gacc` in events when a fix < 60 s old exists; in the export. Not yet used for alignment. |
| Export | `SessionLog`: header (commander, sensors, dots, mode, last brief) + every event line + `hush`/`ranging_start`/`ranging`/`ranking`/`stop` records → `Downloads/hush-<date>-<time>.jsonl` via MediaStore. |
| Permissions (declared, requested at role pick) | RECORD_AUDIO, BLUETOOTH_SCAN/ADVERTISE/CONNECT, NEARBY_WIFI_DEVICES, ACCESS_FINE/COARSE_LOCATION, ACTIVITY_RECOGNITION, RANGING (API 36), VIBRATE, FOREGROUND_SERVICE(+MICROPHONE), ACCESS/CHANGE_WIFI_STATE, legacy BLUETOOTH/ADMIN. |

## Status (26 Sep 18:30)

**Verified from the laptop (adb-driven, phones on the table):** roles → mesh join → HUSH → chirps → ranging →
two-mic north → window → ranking → brief, repeatedly; live brief; radio sessions open on both sides.

**Reported by the team:** tapping recognised at a distance after the ×3 threshold; buzz still weaker than wanted;
direction wrong in a test with phones 50–60 cm apart (inside the near-field skip; must be re-run at ≥ 1 m); "Sensor C
more sensitive than B" traced to the chirp-inflated noise floor (fixed) plus phones sharing one tabletop.

**Open tests (each independent):** live tracking without HUSH; direction at ≥ 1 m while turning the commander; quiet
window ("No human signal detected"); two-source window; B takes the star when knocked beside; multi-hop ("2 hops" on
the far sensor); pairing prompts and whether "Radio:" loses its "?".

**Known gaps / next steps:**
1. Channel Sounding refused by the stack — try bonding (in build), then security level 4, then give up on CS and keep
   RSSI as "nearer/farther" only.
2. Positions for more than three phones: least-squares from the full distance graph (mesh already relays reports).
3. Locate: TDOA solver from sample-accurate onsets (timestamps only) + error circle; open-air only, never under rubble.
4. Call-and-listen through the speaker; torch strobe on the strongest sensor; barometer is absent on this model.
5. VICTIM mode (trapped person's phone chirps a known pattern + SOS beacon) — biggest upside, not started.
6. Remove the debug WAV before any public build.

## Every sensor on the phone, judged for this job

| Sensor / output | Use | Status |
|---|---|---|
| Microphones (2) | loudness, YAMNet voice/machinery, knock onsets, rhythm, chirp ranging, direction of arrival | shipped |
| Accelerometer | ✓felt tag, ⚠moving flag, quality gating | shipped |
| Speaker | Hush beeps, ranging chirps; call-and-listen | beeps + chirps shipped |
| Vibrator | Hush start/end | shipped |
| Compass (rotation vector) | arrow, walk alignment, direction of arrival to north | shipped |
| Step detector | commander dot between rangings, placement walk alignment | shipped |
| Bluetooth LE | mesh link (Nearby), tag advertising + scan, GATT link, ranging sessions | shipped; CS refused, RSSI unusable |
| GPS | coordinates in events/export | shipped; alignment overlay not built |
| Battery | per-sensor % on the commander | shipped |
| Barometer / UWB / Wi-Fi RTT / Aware | — | not present on the I2501 |
| Torch, screen strobe, VICTIM mode | find the sensor in dust; trapped phone beacons | not built |

## Message contract (the only thing that crosses the network)

```kotlin
// One JSON line per message.
data class SensorEvent(          // every phone, once a second
    val sensorId: String,        // "A" = commander
    val tMs: Long,               // sender's elapsedRealtime
    val rms: Float,              // band-passed loudness 0..1
    val floor: Float,            // rolling-median noise floor
    val human: Float,            // YAMNet voice bucket
    val machine: Float,          // YAMNet machine bucket
    val topClass: String,
    val taps: Int, val tapScore: Float,
    val impact: Float,           // YAMNet impact bucket (corroboration)
    val rhythmScore: Float, val rhythm: String?, val label: String,
    val accel: Int, val accelMax: Float, val moving: Boolean,
    val battery: Int, val tempoMs: Int,
    val lat: Double?, val lon: Double?, val gpsAcc: Float?,
    val chirpTs: Long? = null
)
data class Command(val type: String /* ASSIGN|HUSH|STOP|CHIRP */, val seconds: Int = 20, val letter: String?, val to: String?, val ble: String?)
data class ChirpReport(val hearer: String, val from: String, val sample: Long, val ratio: Float, val micDelay: Float?, val heading: Float?, val level: Float?)
data class Placement(val letter: String, val east: Float, val north: Float, val steps: Int)
data class Join(val name: String, val hops: Int, val leaving: Boolean, val ble: String?)
```

## File layout (single module `app`)

```
app/src/main/java/com/hush/
  MainActivity.kt          // role picker, permissions, screens; back leaves the role
  SensorService.kt         // foreground service that keeps Engine alive
  Engine.kt                // everything: audio pipeline, fusion, hush window, live scoring, ranking, sources,
                           // chirp ranging, map frame, alignment sources, radio ranging glue, export
  HLog.kt                  // logcat + private file logger (the phones drop logcat)
  Ranging.kt               // two-way acoustic distance maths, triangle
  audio/AudioCapture.kt    // stereo AudioRecord loop, 1 s windows, ring buffers, debug WAV
  audio/Dsp.kt             // band-pass, RMS, downsample
  audio/Classifier.kt      // YAMNet + buckets
  audio/TapDetector.kt     // onsets with ring-down check
  audio/RhythmTracker.kt   // steady / pattern / tempo
  audio/AccelChannel.kt    // jolts, moving
  audio/Compass.kt, DeadReckoning.kt, Gps.kt, MicProbe.kt
  audio/Chirp.kt           // chirp template, playback, matched filter
  audio/Ping.kt            // beeps
  net/NearbyLink.kt        // mesh (cluster) link
  net/BleRanging.kt        // Android 16 ranging sessions, tag advertising/scan, GATT, pairing
  model/Events.kt          // all messages + JSON
  log/SessionLog.kt        // export
  ui/CommanderScreen.kt, SensorScreen.kt, MapView.kt, ArrowView.kt
app/src/main/assets/yamnet.tflite, yamnet_class_map.csv
.github/workflows/build.yml
```

## Build, run, inspect

```bash
export JAVA_HOME=/c/Android/jdk17 ANDROID_HOME=/c/Android/Sdk   # if the shell predates the install
./gradlew assembleDebug -q
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do adb -s $s install -r app/build/outputs/apk/debug/app-debug.apk; done
adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep -i 'RANKING\|BRIEF\|RANGING result\|DoA align\|BleRanging'
adb -s <serial> exec-out run-as com.hush cat files/debug.wav > debug.wav   # first 90 s of raw audio, laptop analysis only
```

## Demo flow the code supports

1. Three phones spread ≥ 1 m apart on the floor (cloth under them; a shared tabletop carries knocks to every mic).
   Airplane mode + Bluetooth + Wi-Fi radio on. Pick COMMANDER on one, SENSOR on the others; letters and name suffixes appear.
2. Tap HUSH. Chirps (~8 s) place the sensors on the map and align north; the buzz and beep call silence; 20 s window.
3. Someone knocks beside a sensor. The "Window ·" brief names it; the live brief keeps following the knocking afterwards.
4. Turn or walk with the commander: the arrow keeps pointing at the strongest sensor with the distance.
5. EXPORT LOG → `Downloads/hush-….jsonl`, shown on the laptop via Office Kit.

## What we will not claim

No "dot on the map" under rubble. Not a replacement for seismic kits or search dogs. Never overrides emergency
communications. Radio distances are coarse until Channel Sounding works. If asked to add anything that implies more,
push back.
