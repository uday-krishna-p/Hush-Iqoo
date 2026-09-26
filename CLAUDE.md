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
   how sensors were carried out. The commander's dot walks between rangings by step counting.
7. **Source location (added 26 Sep evening).** The commander no longer just points at the strongest sensor: it works
   out where the knocking (or voice) itself is and draws it as a red cross-hair with an uncertainty disc; the arrow
   points at that spot with the distance ("→ SOURCE · 3.4 m ±0.5 · 6 knocks"). Falls back to the strongest sensor
   until a fix exists. See "How the source is located" below.
8. **Radio ranging.** Bluetooth sessions run silently to every sensor. Channel Sounding is refused by the phones so far
   (see Status); signal-strength ranging works but is far too coarse and is shown with "?" and never trusted.
9. **Export.** One JSON-lines file per session to `Downloads/` (header with sensors, dots, mode; every event; hush,
   ranging, ranking and stop records).

## How the source is located (`Locator.kt`, commander only)

Three kinds of evidence about every knock, all scored on a grid of candidate positions (0.25 m cells, at least
±12 m around the phones) and added up knock after knock; the best cell is the source, the cells within 3 nats of it
are the "likely region" (its RMS radius, its nearest distance and its angular spread from A are what the screen shows).
Nothing but small JSON crosses the network.

1. **Time difference of arrival (the strong cue).** Every phone finds the exact sample at which each knock first
   arrived (`TapDetector.refine`: first sample above max(6 × window median, 25 % of the local peak), so the pick does
   not depend on how loud the knock is at that phone). Sound moves 7 mm per sample at 48 kHz. Phones' audio clocks are
   unrelated, so the chirp round measures them: chirp j is heard by sensor i and by A at a known distance each, so
   `offset_i = (t_i(j) − t_A(j)) − (d(i,j) − d(A,j)) · fs / c`, one estimate per chirp, median, drift rate fitted over
   rounds (`Clock:` log lines). A sensor's onsets are ignored until its clock is known. Onsets that reached the
   phones within (largest phone spacing / c + 4 ms) of each other are the same knock; the difference for each pair of
   phones is a hyperbola; σ = 0.5 ms (≈ 17 cm). Heavy-tailed loss, so one echo or a missed knock cannot drag the answer.
2. **Loudness ratios (weak, weight 0.5, σ 6 dB).** Onset peak ÷ chirp-calibrated mic gain, 1/r law; the unknown source
   level cancels in the mean. Tells front from back when timing alone cannot, and it is the only distance cue for voices.
3. **Two-mic direction per phone (medium, σ 12°).** Cross-correlation of the two mics over the first 12 ms of the
   knock (`Doa.kt`, same sign convention as the chirp code) gives the angle from the phone's mic line; a line cannot
   tell left from right, so each phone contributes two candidate bearings and the other cues decide. Which way each
   phone's mic line points on the map is learnt from the chirps it heard from the other phones (`Axis:` log lines,
   needs 2 chirps from ≥ 0.8 m); its compass only tracks turning after that, so a constant magnetic error does not
   matter and the top/bottom mic order does not matter either. Skipped for phones without a calibrated axis.
4. **Voices** have no sharp onset, so they use cues 2 and 3 only, once a second, over the whole second (band-passed
   300–3000 Hz): coarse. Mode TAPPING uses knocks, VOICE uses voice seconds, ANY both.

**Measured on synthetic data (`app/src/test/java/com/hush/LocatorTest.kt`, run with `./gradlew testDebugUnitTest`):**
phones 2.2 m apart, ±0.2 ms onset noise, 5 knocks: a source 3 m outside the triangle is found within 0.1–0.3 m, a
source 11 m away gets its bearing within 1° (±3° spread, flagged EDGE = "≥ N m away, direction ±3°"), a source 0.3 m from
sensor B lands on the source and not on B. **Physics to remember:** with phones 2 m apart, timing pins the direction
of anything beyond ~2 array-widths but not its distance; spread the phones wider for distance. Structure-borne
knocks (phones on the same slab) arrive faster than through air: onsets tagged "felt" (accelerometer jolt within
100 ms) get 3× the timing sigma; put cloth under the phones. Not tested on the real phones yet: see open tests.

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
- **The phones are lock-screen protected.** From adb, `am start` of MainActivity lands behind the lock screen (so its dialogs are invisible and screenshots are black); only BeaconActivity shows over the lock screen. Unlock the phone by hand before driving MainActivity from the laptop.
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
- **Keep it simple.** One module, one main Activity (plus `BeaconActivity`, the lock-screen rescue alert), one
  foreground service, one `Engine` object. No DI, no Compose.
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
| Acoustic ranging | `Chirp.kt` 80 ms 2–6 kHz Hann sweep at **40 %** alarm volume (team's request; raise for a hall). Matched filter (27 Sep): FFT correlation on a 16 kHz copy as an analytic signal (its magnitude = smooth envelope, so no 4 kHz carrier-cycle slips), first arrival = earliest envelope peak ≥ 0.3 × the strongest and ≥ 6 × the median within 30 ms before it (the direct path between phones on a table is often weaker than a reflection 3–20 ms later), then refined at 48 kHz ±12 samples with the chirp and its quadrature partner, parabolic sub-sample peak. **37–94 ms per 5 s search on the phones** (sample-by-sample: 1.2–5.6 s). `ChirpTest` covers it. SERIAL chirps: one letter at a time, each phone searches ±2.5 s around its command, commander moves on when all reported or after 7 s. Pair distance `D = c/2·[(t_i(j)−t_i(i)) − (t_j(j)−t_j(i))]/fs + 0.12 m` (clock offsets cancel). Measured: AB 0.91 (real ≈ 1.0), AC 0.56 (real ≈ 0.5), later rounds 0.54–0.58 for the same layout. Detection strength 95–1780× vs threshold 5. Self-checks: timing filter ±0.35 s (a wrong peak once produced 59.7 m), triangle inequality and ≤ 30 m, one retry of lost pairs, **two rounds within 20 % before the map moves**. Only the first three letters form the map (N > 3 solver not built). |
| Map frame | B origin, C on +x, A (commander) moves inside; scale fixed at first ranging (1.6 × the largest side). A's dot moves by step counting between rangings. A settled sensor (moved, then still 3 s) triggers a re-ranging. |
| North | Ranging alone cannot know rotation. Sources, best first: (1) **two-mic direction of arrival** of B's and C's chirps at the commander (sub-sample inter-mic delay; mic spacing solved against the triangle's known angle, then held as a median; skipped when phones < 0.8 m apart; rotation smoothed over 5 rounds); (2) the commander's walk (A's shift on the map vs compass bearing walked; moves > 10 m ignored); (3) placement walk (step detector + compass on carried-out sensors); (4) manual Place buttons. First DoA run: spacing 0.10 m, angles 44°/8° vs true 38°, spread 1°. Physical direction test still failing at 50 cm spacing (near field) — needs ≥ 1 m. |
| Compass arrow | `ArrowView` + rotation-vector `Compass`; angle = mapBearing(A→target) + rotation − heading. Target = the located source when there is a fix < 60 s old, else the strongest sensor. |
| Passive port / probe | `Probe.kt`: victim side = PendingIntent BLE scan, filter on 16-bit UUID 0xA5A7, `SCAN_MODE_LOW_POWER`, re-armed at boot (+10 s), app update (+3 s), every 15 min (inexact alarm) and whenever the app opens; a re-registration waits 2.5 s between stop and start. Commander side = 30 s non-connectable advertisement of 0xA5A7 with its name suffix, high power. Woken phone: notification (channel "rescue", full-screen intent) → `BeaconActivity` → `SensorService` (byProbe) → Nearby SENSOR; tag flags bit 0 = woken by probe; back to passive after 10 min without a commander. Commander shows "Discovered phones" from tag sightings: median RSSI, "~N m?" (−59 dBm at 1 m, exponent 2.7), warmer/colder trend, battery, letter once joined. |
| Source locator | `Locator.kt`, see "How the source is located". Constants: cell 0.25 m, σ_t 0.5 ms, σ_amp 6 dB (weight 0.5), σ_doa 12° (voice 20°), decay 25 s, hold 3.5 s for late reports, mic spacing = median solved by the chirp rounds else 0.10 m. `LOCATE knock #n heard by A,B,C: A:+0.0ms B:+3.1ms …` and `LOCATE fix: peak (x, y) region … radius … nearest … spread …` in the log. Export gets a `locate` record per update. |
| Radio ranging | `BleRanging` (Android 16 `RangingManager`). Capabilities on the I2501: CS enabled, RSSI enabled, UWB/RTT absent; own address read from the capabilities object's `toString`. Sensors advertise a connectable BLE tag (service UUID `0000A5A5-…`, data = name suffix); the commander scans for the tag to learn the sensor's **live** (rotating) address, opens a GATT link, then initiates; the sensor learns the commander's live address from its GATT server and answers it. **Result so far: CS opens, starts and closes with reason 3 (UNSUPPORTED) within 1 ms every time**, even over an open link with the responder ready; RSSI ranging then runs continuously but reads 6–14 m for phones 0.5 m apart. RSSI is displayed with "?" and never used to drop a chirp round. Latest build requests a one-time pairing and retries CS once bonded (untested). |
| GPS | `Gps.kt`, framework `LocationManager`; `lat/lon/gacc` in events when a fix < 60 s old exists; in the export. Not yet used for alignment. |
| Export | `SessionLog`: header (commander, sensors, dots, mode, last brief) + every event line + `hush`/`ranging_start`/`ranging`/`ranking`/`stop` records → `Downloads/hush-<date>-<time>.jsonl` via MediaStore. |
| Permissions (declared, requested at role pick) | RECORD_AUDIO, BLUETOOTH_SCAN/ADVERTISE/CONNECT, NEARBY_WIFI_DEVICES, ACCESS_FINE/COARSE_LOCATION, ACTIVITY_RECOGNITION, RANGING (API 36), VIBRATE, FOREGROUND_SERVICE(+MICROPHONE), ACCESS/CHANGE_WIFI_STATE, legacy BLUETOOTH/ADMIN. |

## Status (27 Sep 00:00)

**Phase 1 (PRD 2.1, passive probe port) is built and verified on two phones from the laptop, 26 Sep 20:50–21:15:**
probe → dead victim process started by the system → alert notification + haptic → red beacon screen over the lock
screen → microphone service → Nearby join as Sensor B → tag seen by the commander with the "woken by probe" flag.
Measured: wake 1.3–12 s after the probe starts (low-power scan listens 0.5 s in every 5 s; the PRD's 2 s is met
only sometimes); "I AM SAFE" stops the sensor and the port stays armed; app update and reboot re-arm the port
(delayed 3 s / 10 s; the boot broadcast arrived with the lock screen still showing); a force-stopped app cannot be
woken until it is opened again (platform). **The decisive finding:** a scan registered while the app is in the
background delivered nothing on OriginOS (Bluetooth's own statistics: minutes of scan time, 0 results) until the app
was exempt from battery optimisation or allowed to run in the background; either alone fixes it. The app now asks
for the exemption at first launch and shows an orange button until it is granted. The third phone (…000XR) dropped
off USB at 19:20 and still runs the build from before the locator.

**Code review 26 Sep 22:00 (ten findings, all fixed 23:00):** the ranging retry now judges every detection against
its own chirp's trigger time instead of "letter order × gap", so a retry can no longer wipe good pairs; the probe
receiver is not exported; `Engine.stop()` cancels pending chirp callbacks and clears all ranging/map/gain state;
one `SessionLog` per session; the last second of a window is kept (1.5 s grace after the window ends: the
commander's own count went from 16–17 to 18–19 windows); the noise-floor history and rhythm tracker are guarded by
one lock between the audio and main threads; our beeps and chirps put the alarm volume back afterwards
(`AlarmVolume`); sensors send nothing until they have a letter and the commander drops events from unassigned
ones; every catch logs; one `Haptics` object, one `batteryPercent()`, one tag UUID.

**Seen 26 Sep 22:59, not yet explained:** a HUSH command took 7.6 s to reach a sensor that had been woken by a
probe 50 s earlier, and its events came back just as late, so the window ranked it on 3 seconds of data. Same
mesh code as before; suspects are Nearby's Bluetooth→Wi-Fi upgrade attempt in the first minute of a link, or the
woken phone's screen being off. Measure: `Command received` time minus `Hush window started` on the commander, on
a sensor that has been connected for > 2 min, screen on and off.

**Calibration with played knocks, 26 Sep 23:00–23:25 (`tools/calibration/`, three phones, real ESC-50 door knocks
played by phone 991e as a sensor):** (1) Command latency over the mesh: 0.6 s constant to 991e, 1.4–20 s and
erratic to ef39, same code, so ranging was made SERIAL (one letter chirps, everyone reports or a timeout, then the
next) with a ±2.5 s search window; the matched filter over 5 s takes 1.2–5.6 s on the phone, longer than the 7 s
timeout allows once network latency is added, so the round still lost pairs: speed up `Chirp.detect` (coarse
search at 16 kHz, refine at 48 kHz) or lengthen the timeout. (2) `Chirp.detect` now takes the first strong
arrival, not the strongest: the strongest was an echo 44 ms late once (7.8 m instead of 1.2 m); `firstArrivalShift`
in the log shows how far it moved (11–2102 samples seen). (3) A cross-hearer sanity check replaced the
schedule-based one. (4) Knock clicks from a phone speaker reach the other phones at peak 0.007–0.017, the same
as this room's ambient transients (0.007–0.019, 60–140 onsets per 78 s on each phone with people around), so the
tap detector fired constantly; the rhythm tracker still labelled the 1/s phase "steady" on 8–15 of 17 s per phone,
never recognised the 3-2 pattern (too many of its knocks missed, suspect the ring-down check on real, ringing
knocks: watch `rej=` in the window lines), and the locator fused nothing because no ranging round completed.
(5) The locator now fuses knocks only while some phone reports rhythm ≥ 0.9 or a window runs, so room noises are
not located. (6) The chirp is at 40 % of alarm volume at the team's request; raise for a hall.

**Calibration continued, 26 Sep 23:30 → 27 Sep (second session, three phones on USB, `tools/calibration/`):**
(1) *Old detector was the reason rounds failed, not latency.* Replaying the 23:21 debug WAVs (same sample counter as
the log) reproduced every logged pick exactly and showed: every self-detection slipped 11–12 samples (one carrier
cycle) early; the first-arrival loop slid its window with each earlier peak and walked back up to 4407 samples; the
two mics landed up to 43 samples apart (impossible 26.7-sample "mic delay"). Between phones the direct sound is often
the weaker one: C hearing A had a flat envelope until −1004 samples, a direct arrival at 0.45–0.67 of the strongest
peak, and the strongest (a reflection) 20 ms later. With the envelope detector the B−A clock offset from the same chirp
in two rounds agrees to 0.1 sample (−243216.4 / −243216.3). (2) *An unexplained fourth chirp* was heard by all three
phones at similar strength 1.17 s after C's chirp at 23:21:42 (not any of our phones' self-level): another device
chirping in the room? A ±2.5 s search window can catch it. (3) *Latency is small; the phones' wall clocks are not.* Against
the laptop (`adb shell date +%s.%N`, round trip 0.1 s) the phones run A +0.44 s, ef39 +1.81 s, 991e +1.02 s, so
log timestamps from different phones differ by up to 1.4 s. Corrected (`latency.py A=0.44 B=1.81 C=1.02`): over 42
commands each in the 23:24–23:28 rounds, 991e (1 hop) 0.01–0.18 s (median 0.04), ef39 (2 hops via 991e) 0.02–0.25 s
(median 0.06); in the 23:46 rounds median 0.10–0.15 s with outliers of 0.5–1.55 s. The "0.6 s per hop" and "1.4 s"
of earlier notes were clock skew. The "1.4–20 s erratic" was ef39's direct link stalling from 23:21:06 until Nearby
dropped it at 23:22:03. `analyze.py` now finds the player's own delay: its knocks reach its own mic **234.7 ms after
the PLAY sample** (IQR 0.73 ms; playback latency), and it now matches 72/75 of its own knocks (was mostly
"unscheduled"); ef39 63/75 (steady 19/20, 3-2 24/30, fast 20/25); the commander 1.3 m away heard none above ambient.
Same knock on ef39 and 991e: timing difference spread 0.33 ms median abs deviation (11 cm), 90th pct 1.61 ms. (4) *Endless auto re-ranging:* the "walked 3 s" counter
was cleared only when a round placed the map, so after a failed round the commander chirped every 18 s while lying
still (13 rounds, 23:24–23:28). Now cleared when any round starts. (5) *Digital silence on ef39:* started from the
laptop with its screen off, its service never became a foreground service (`startForegroundCount=0`, no exception)
and Android silenced the recording 5 s later (every chirp "ratio=0.0"). Now: the service logs whether it really is
foreground, the activity re-asserts the foreground service whenever it comes on screen, and 3 s of exact zeros log
`ERROR: microphone delivers digital silence` and show it on screen. For laptop tests the screens are kept on over USB
(`adb shell svc power stayon usb`, restore with `svc power stayon false`). (6) *First round with the new detector
(27 Sep 23:46:44):* every chirp reported by all three phones within 4.3 s, AB 0.45, BC 0.49, AC 0.78 m, `Clock:`
spread 2.0 samples (was 87.5 on the old detector). The confirming round was spoiled because the commander was being
handled (accelerometer RMS 0.1–1.9 m/s², a 7-step "carried" placement) and both sensors' reports then stalled 10–15 s.

**Geometry, 27 Sep 00:05–00:25 (user's new plan: ef39 = COMMANDER in the centre, 6a46 and 991e sensors; tape
measure from the team: ef39–6a46 1.00 m, ef39–991e 0.65 m, 6a46–991e 1.20 m; letters B/C follow join order, check
"is Sensor" in the commander log):**
(1) *Ranging vs tape.* Every still session since 23:54 (three commanders/letterings) measures the same shape:
ef39–6a46 0.91–0.92 m with the old 0.12 m constant, ef39–991e 0.87–0.98, 991e–6a46 1.88–2.15 (one round 4.49).
Solving the two-way model with the tape distances: the ef39–6a46 pair gives a self-hearing delay of 29.5, 28 and
29 samples in three sessions (0.20–0.21 m), so `SPEAKER_MIC_OFFSET_M` is now **0.21** (was 0.12). With it:
ef39–6a46 1.00 (tape 1.00), ef39–991e 0.98–1.01 (tape 0.65), 991e–6a46 1.88–1.99 (tape 1.20): a straight line
with ef39 in the middle. The ef39–991e pair would need a self delay of −21 samples to match the tape, which is
impossible, so either the tape/labels are off or 991e's direct sound is blocked (sound around an obstacle reads
long). Asked the team to re-check 991e. Stereo audio shows the 991e pairs' direct arrival weak and ~600–700
samples before a much stronger reflection. `Clock:` spread 7.5–35.5 samples on these rounds (2.0 in the first
23:46 layout). (2) *Two-mic delay of the chirp is ambiguous by one 4 kHz cycle (12 samples, 8.6 cm of path).*
With stereo debug audio (both channels are distinct mics: 2.6–2.9 % identical samples): independent detections per
mic gave impossible delays (−29, −30); matched-filter cross-correlation with a ±12 window saw a pure tone (every
lag 0.99, whole-sample results were my clamp bug); the phase part repeats between rounds (e.g. 0.60/0.22,
25.0/24.7, 10.96/−1.27 ≡ 10.96/10.73 mod 12) but the cycle does not; envelope onsets per mic repeat within ~2
samples in 4 of 6 pair-directions but give 25–37 samples (beyond the ~22 a 16 cm phone allows) and disagree with
the phase method by exactly 3 cycles. Root cause: the 2–6 kHz chirp's envelope is as wide as one 4 kHz cycle, so
even a clean synthetic copy picks the right cycle by only 0.10. The app now gates chirp two-mic delays (cycle
margin ≥ 0.15 and |delay| ≤ 24 samples); nearly all are rejected, so DoA north and mic axes stay off rather than
wrong. Knock two-mic delays (broadband) are repeatable (991e's own knocks: IQR 0.27 samples). Next: a rotation
test (one phone turned in 45° steps, a chirp from 1 m) to learn the real delay-vs-angle curve and the mic
positions, and/or a wider chirp band (e.g. 1–8 kHz) so the envelope is narrower than a carrier cycle.
(3) *Near-field limit* for two-mic angles lowered from 0.8 to 0.5 m: the far-field formula is < 1° off at 0.65 m
for 0.15 m mic spacing; the speaker's offset from the phone centre (≤ 7° at 0.65 m) is the real error. With
0.8 m the commander ef39 (0.65 m from 991e) got no DoA alignment and two of three phones no mic axis.
(4) *Arrow log:* the commander writes `ARROW target=… mapBearing=… rotation=… heading=… screen=…` once a second;
with no north alignment it says `no arrow: map not aligned to north`. (5) *Mesh:* a sensor with no route now
refuses or drops anyone connecting through it (23:48: 991e attached to ef39 12 s after ef39 had silently lost the
commander and was never lettered); Nearby's bandwidth changes and > 2.5 s gaps from below are logged (links came
up at quality 3 = Wi-Fi; gaps of 2.8–6.3 s seen); explicit `Ranging: trigger CHIRP X` lines for latency.py.
(6) *Debug WAV is stereo* now (both mics, 90 s, private file).

**0.40 m triangle, 27 Sep 00:24–00:32 (team: all pairs 0.40 m ±3 cm, same table, nothing between; ef39 commander):**
(1) *Ranging does not match the tape.* Three still rounds (accelerometers ≤ 0.014 m/s²) read ef39–6a46
0.78/0.78/0.78, 6a46–991e 0.76/0.76/0.77, ef39–991e 0.95/0.95/0.94 m (repeatable to ±1 cm; `Clock:` spread 8.5–10.5
samples; "Placed by sound" after two rounds). Per-phone self-hearing delays solved from this layout: ef39 −49.5,
991e −44.5, 6a46 +2.5 samples, which do not reproduce the previous tape layout. In the stereo audio each phone hears
its own chirp on both mics within 1–3 samples (an in-air path from one speaker would differ by ~20), and ef39 and
991e (not 6a46) show a second self-peak 43–47 samples later: a phone may record an early copy of its own chirp
before the sound leaves the speaker. Open until a controlled distance series is run (asked the team: phones in one
line at 0 / 0.50 / 1.50 m, then 6a46 at 2.50 m). (2) *Two-mic chirp delays repeat on a still layout even when
ambiguous:* ef39←6a46 −15.8/−15.9/−16.0, ef39←991e −18.7/−19.0/−19.0, 6a46←991e −15.6/−15.8/−16.0 samples; 991e←ef39
14.1/24.6/25.6 and 991e←6a46 13.0/24.3/24.9 (one 12-sample cycle slip each); 6a46←ef39 −27.3/24.3/3.6 (useless).
Repeatable is not correct: only a rotation test can show which cycle is true. (3) *Knocks* (991e playing at full
alarm volume): 991e 74/75 (jitter 0.3 ms, its own knocks reach its mic 198.8 ms after PLAY this run), ef39 13/75
(jitter 1.2–1.8 ms), 6a46 0/75 — people talking beside it raised its window median (floor 0.0018→0.0030) and the
knocks never passed 2× it. Ring-down rejections: 0–1 per phone in this run, 10–11 per phone in the 23:20 run, against
75 knocks: **DECAY_MAX / DECAY_FRAMES are not the cause of missed knocks; the level of a phone speaker's knock at
another phone is.** Onset ratio does not separate knocks from other onsets (ef39: matched ×12, unscheduled ×12), so
no minimum ratio for the locator. Tap-detector thresholds left unchanged; real knuckle knocks (×11–28) are the true
test. (4) *Locator:* fused the 6 knocks heard by two phones and put the source 0.2 m from 991e (→ef39 1.2 m, →6a46
0.9 m, on the inflated map), `radius 3.4 m` after 6 knocks. (5) *Latency with ef39 as hub* (clock skew removed):
6a46 median 0.07 s (0.02–0.53), 991e 2 hops via 6a46 median 0.12 s (0.06–0.55).

**Earlier (18:30):**

**Verified from the laptop (adb-driven, phones on the table):** roles → mesh join → HUSH → chirps → ranging →
two-mic north → window → ranking → brief, repeatedly; live brief; radio sessions open on both sides.

**Reported by the team:** tapping recognised at a distance after the ×3 threshold; buzz still weaker than wanted;
direction wrong in a test with phones 50–60 cm apart (inside the near-field skip; must be re-run at ≥ 1 m); "Sensor C
more sensitive than B" traced to the chirp-inflated noise floor (fixed) plus phones sharing one tabletop.

**Open tests (each independent):** source locator on real knocks (after a HUSH/ranging: knock 2 m outside the
triangle, expect `LOCATE knock` lines with B/C a few ms apart, a red cross-hair, and the arrow on the knock, not on the
nearest sensor; then knock beside B and expect the cross-hair on the knock, still not on B's dot); `Clock:` spread
< 10 samples and `Axis:` spread < 15° in the log after a round; live tracking without HUSH; direction at ≥ 1 m while turning the commander; quiet
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

## Roadmap (revised 26 Sep 20:00 against PRD v2.2, `docs/PRD-v2.2.md`)

The PRD's Feature 2.1 is the priority: **passive victim phones (< 1 % battery/day) that a commander's probe wakes,
which then notify and buzz the victim and join the network as sensors.** Features 2.2–2.4 (Hush window, ranking,
radar canvas with arrow) already exist, and the arrow now points at the located sound rather than at a sensor pin.
Personas B and C (household, hearing-impaired) come later. What follows is what the hardware and Android actually
allow, the PRD's acceptance criteria against those facts, then the build order.

### Hardware facts (checked 26 Sep 19:30, `adb shell pm list features` on two I2501, OriginOS 6, API 36)

- **Present:** `bluetooth_le`, `bluetooth_le.channel_sounding`, `wifi`, `wifi.direct`, `wifi.passpoint`, `nfc`, telephony.
- **Absent:** `wifi.rtt`, `wifi.aware`, `uwb`. **Wi-Fi round-trip timing is not possible on these phones.** Where it
  exists it needs 802.11mc/az access points or Wi-Fi Aware peers, gives 1–2 m in the open and worse in rubble.
  Acoustic chirps (±5 cm) stay our precise ranging; Channel Sounding (0.5–1 m if the stack ever allows it) is the
  only radio ranging that could add to it, and only between two phones that both run Hush.
- Hush is **not** on the battery whitelist and OriginOS has no background-run allowance for it: today the system
  will kill it in the background. See phase 0.

### What a phone can find, honestly

| Target | What works | Accuracy | What does not work |
|---|---|---|---|
| A phone running Hush (cooperating) | chirp ranging; the source locator on its SOS chirps (a chirp is the ideal "knock": matched-filter timing); BLE tag RSSI; a local-only Wi-Fi hotspot as a stronger beacon (+15–20 dB over BLE, so 2–3× the range through rubble); GPS outdoors; battery and last fix over the mesh | ±5 cm distance, ±3° bearing, ±0.3 m position inside the array | Channel Sounding (refused by the stack so far), Wi-Fi RTT/UWB (absent) |
| A phone WITHOUT Hush | only what it already broadcasts: iPhones send Bluetooth beacons continuously when locked (Find My network, Continuity); Android phones only in some states (Quick Share sheet open, Find My Device offline finding, Fast Pair). Detection + signal strength; three or more Hush phones seeing the same address give a coarse blob | "there is a phone" and warmer/colder; ±3–6 m blob at best; addresses rotate every ~15 min so a device cannot be tracked across sweeps; never an identity | exact position; Wi-Fi (phones do not answer scans, only access points do; monitor mode needs root); cellular (phones cannot receive another phone's uplink; that is what professional USAR detectors do) |
| A person knocking or shouting | the source locator (built) | ±0.3 m inside the array, direction ±3° beyond it | distance beyond ~2 array-widths (spread the phones) |

**We will not claim** "finds every phone" or "exact location of any phone". We can claim: finds a Hush phone to
centimetres, finds a knocking person to a bearing, detects transmitting phones and says which way is warmer.

### Android rules that shape the background plan (platform behaviour, Android 14–16)

- A **microphone foreground service can only be started while the app is on screen**, never from the background
  and never at boot (Android 15 blocks `BOOT_COMPLETED` starts for the microphone type). Once started it keeps the
  mic with the screen off for as long as the process lives. Tapping a notification action counts as "on screen".
- A **Bluetooth (`connectedDevice`) or `location` foreground service may start at boot.** So after a reboot the
  phone can come back as a radio beacon and scanner on its own; it becomes a listener after one tap.
- **Unfiltered BLE scans stop when the screen is off** (since Android 8.1). Background sweeps must use scan filters
  (Apple manufacturer id 0x004C, Google service UUIDs, the Hush tag); a filtered scan keeps running.
- **Wi-Fi scans are throttled**: 4 per 2 minutes for an app on screen, 1 per 30 minutes in the background. A Wi-Fi
  "warmer/colder" meter therefore updates every 30 s at best, BLE every second.
- **Local-only hotspot** (`WifiManager.startLocalOnlyHotspot`) is allowed for apps; Android picks the name
  (`AndroidShare_xxxx`); the SOS tag carries it so rescuers can match it.
- **The passive "listening port" exists in Android as a filtered BLE scan registered with a PendingIntent**
  (`BluetoothLeScanner.startScan(filters, settings, PendingIntent)`, API 26+). The Bluetooth chip does the matching,
  the app process may be dead, and the system delivers a broadcast to the app when a matching advertisement appears.
  No service runs. `SCAN_MODE_LOW_POWER` listens ~0.5 s in every 5 s; this is the only mechanism that can meet
  "< 1 % per day", and it means wake latency is up to ~5 s, not the PRD's 2 s (BALANCED mode halves it at 2–3× the
  battery). Must be re-registered at boot (a `BOOT_COMPLETED` receiver may do that; no service needed). A phone whose
  app was force-stopped, or whose Bluetooth is off, cannot be woken by anything.
- **Waking the screen and starting the microphone from a probe** is legitimate only through a full-screen-intent
  notification (the incoming-call mechanism, `USE_FULL_SCREEN_INTENT`): it turns the screen on, shows our beacon
  screen over the lock screen, and because that puts the app on screen the microphone foreground service may start.
  Sideloaded builds get the permission by default; a Play Store build would have to ask the user for it.
- **`POST_NOTIFICATIONS` is currently "ignore" on both phones**: the app never asks for it, so today no Hush
  notification is shown at all. Phase 1 must request it at first launch.
- Runtime permissions (mic, Bluetooth, location, notifications) can only be granted from the activity: first launch
  always needs the screen once. After that, nothing needs a tap until the phone reboots or the app is force-stopped.
- **OriginOS (vivo): a passive scan registered from the background never delivers unless Hush is exempt from
  battery optimisation** (measured 26 Sep 21:11; `dumpsys bluetooth_manager` showed the scans running with
  "0 results"). The app asks for the exemption at first launch (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, system
  dialog "Allow Hush to run in the background?"); until granted the armed screen shows an orange button. From the
  laptop the same thing is `adb shell dumpsys deviceidle whitelist +com.hush`. Also worth setting once per phone:
  i Manager → Autostart → Hush on, and do not swipe Hush away from recents (a force-stop disables the port until
  the app is opened again).
- `ProbeReceiver` is **not exported**: the boot and app-update broadcasts and our own PendingIntent scan results
  still reach it (verified 26 Sep 22:58), but another app cannot fire the rescue alarm. Consequence: the laptop
  cannot send it a broadcast either; use the MainActivity hooks instead.

### PRD 2.1 acceptance criteria against the facts

| PRD says | What we can deliver and how we will measure it |
|---|---|
| Passive listener < 1 % battery/day | PendingIntent BLE scan, LOW_POWER, one filter (probe UUID). No process, no service. Measure: charge to 100 %, arm, leave 12 h, read `dumpsys batterystats` / battery level; expect 1–2 %/day, report the number honestly. |
| Probe wakes 100 % of phones within 15 m in < 2 s | Commander advertises the probe at high power for 30 s. Open air: 15 m yes; latency ≤ 5 s in LOW_POWER (2 s needs BALANCED at 2–3× battery; team decides). Through rubble: 2.4 GHz loses 10–20 dB per slab, so a few metres, and a phone with Bluetooth off or the app force-stopped never wakes. Measure: probe start time on the commander vs. first tag seen from each victim in the commander's log. |
| Notification + screen wake + haptic pulse | High-priority notification with a full-screen intent → beacon screen (red/white, "Rescuers are nearby. Tap 3 times or shout"), `setTurnScreenOn` + `setShowWhenLocked`, alarm-class vibration long-long-short ×3. Needs `POST_NOTIFICATIONS` (not asked today) and `USE_FULL_SCREEN_INTENT`. |
| Victim answers with ID and RSSI; commander shows "~3.5 m under rubble" | The woken phone advertises its Hush tag (name suffix, awake flag, battery %); the commander's tag scan (already in `BleRanging`) gives RSSI per sighting. The log-distance formula is shown as "~N m?" with a warmer/colder trend, because on these phones RSSI read 6–14 m at 0.5 m. Real distance comes from the chirp round once the phone has joined the mesh (needs an air path). |
| Then acts as a sensor | The woken phone starts the existing sensor path (mic service + Nearby) and joins the mesh; HUSH, ranking, locator and arrow work unchanged. If no commander is seen for 10 min it returns to passive so a passing probe cannot drain it. |

### Build order (each phase is a build, an install on all phones, a test, a commit)

**Phase 1 · PROBE and PASSIVE (PRD 2.1).** Builds 1a and 1b DONE 26 Sep 21:15 (see Status). Left from 1c:
overnight passive battery number, open-air wake range, wake through a cupboard/mattress, and a 30-minute
"phone asleep in a pocket" wake test (OriginOS may still kill the port later than our tests reached).

- *Build 1a, passive port.* First launch: permissions incl. notifications → "ARMED" screen ("This phone will wake
  as a rescue sensor when rescuers probe for it") → registers the PendingIntent BLE scan for the probe UUID and
  exits; a `BOOT_COMPLETED` receiver re-registers it. Commander screen gets `[ACTIVATE SENSORS]` (the PRD's RADIO
  SWEEP): advertises the probe (service UUID `0000A5A7-…`, commander name, high power, 30 s), logs `Probe: started`.
  Victim side: the scan-result receiver logs `Probe heard from <name> rssi=…`, posts the activation notification
  with the full-screen beacon screen, vibrates. Test: victim phone locked in a pocket, commander taps ACTIVATE 3 m
  away, phone buzzes and lights up within 5 s; repeat after a reboot of the victim phone.
- *Build 1b, wake to sensor.* The beacon screen starts the sensor path (mic service, Nearby as SENSOR, Hush tag with
  awake flag + battery). Commander: "Discovered phones" list from the tag scan (name suffix, RSSI, "~N m?", trend,
  "joined as Sensor E" once Nearby connects). Return to passive after 10 min without a commander. Test: after
  ACTIVATE the phone appears in the list, then as a sensor row with live seconds; HUSH runs on it.
- *Build 1c, battery and range numbers.* Overnight passive measurement on one phone; open-air wake range with the
  commander walking away; wake through a closed metal cupboard / under a mattress. Numbers go into this file.

**Phase 2 · Already built (PRD 2.2–2.4).** HUSH window, ranking with brief, radar canvas with the arrow. Beyond the
PRD: the arrow points at the located knocking/voice, not at a sensor pin (see "How the source is located"). Only
polish left: the PRD's gold/cyan highlight of the strongest row and a glowing aura on the map target.

**Later, in this order once phase 1 is measured:** SOS mode (victim-initiated: SOS chirp pattern + hotspot
beacon + torch, the locator finds the phone to ±0.3 m); sweep of non-Hush phones (BLE only, coarse blobs, honest
labels); multi-commander flooding mesh; persona B (single-phone walk-to-triangulate, whistle counter); persona C
(screen flashes + haptics for doorbells and knocks).

**Not in any phase:** Wi-Fi RTT/Aware/UWB (absent), exact position of a non-Hush phone (physics), waking a phone
whose Bluetooth is off or whose app was force-stopped (platform), a 2 s wake guarantee at < 1 %/day (the two
numbers trade against each other; the team picks one).

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
    val chirpTs: Long? = null,
    val micDelay: Float? = null, val micQ: Float? = null   // two-mic delay over the second, voice only
)
// Bluetooth (not JSON): probe advertisement = service UUID 0xA5A7 + 4-char commander suffix; Hush tag = service data
// under 0xA5A5 = 4-char suffix + flags byte (1 = woken by probe, 2 = SOS) + battery byte.
// Every phone, once a second and only when it heard knocks: each knock to the sample, on the sender's own audio clock.
data class Onset(val sample: Long, val peak: Float, val ratio: Float, val micDelay: Float?, val micQ: Float?, val felt: Boolean)
data class OnsetReport(val letter: String, val heading: Float, val moving: Boolean, val onsets: List<Onset>)
data class Command(val type: String /* ASSIGN|HUSH|STOP|CHIRP */, val seconds: Int = 20, val letter: String?, val to: String?, val ble: String?)
data class ChirpReport(val hearer: String, val from: String, val sample: Long, val ratio: Float, val micDelay: Float?, val heading: Float?, val level: Float?)
data class Placement(val letter: String, val east: Float, val north: Float, val steps: Int)
data class Join(val name: String, val hops: Int, val leaving: Boolean, val ble: String?)
```

## File layout (single module `app`)

```
app/src/main/java/com/hush/
  MainActivity.kt          // first launch: permissions + arms the passive port; role buttons; back leaves the role
  BeaconActivity.kt        // red "rescuers nearby" screen over the lock screen; starts the sensor service
  Activation.kt            // the alert notification (full-screen intent) + haptic pulse when a probe is heard
  ProbeReceiver.kt         // system entry points: probe scan results, boot, app update, re-arm alarm
  SensorService.kt         // foreground service that keeps Engine alive
  Engine.kt                // everything: audio pipeline, fusion, hush window, live scoring, ranking, sources,
                           // chirp ranging, map frame, alignment sources, radio ranging glue, export
  HLog.kt                  // logcat + private file logger (the phones drop logcat)
  Ranging.kt               // two-way acoustic distance maths, triangle
  Locator.kt               // WHERE the sound is: clock offsets from chirps, mic axes, onset matching, grid fusion
  audio/AudioCapture.kt    // stereo AudioRecord loop, 1 s windows, ring buffers, debug WAV
  audio/Dsp.kt             // band-pass, RMS, downsample
  audio/Classifier.kt      // YAMNet + buckets
  audio/TapDetector.kt     // onsets with ring-down check, refined to the sample
  audio/Doa.kt             // two-mic cross-correlation → inter-mic delay (knock onsets and voice seconds)
  audio/RhythmTracker.kt   // steady / pattern / tempo
  audio/AccelChannel.kt    // jolts, moving
  audio/Compass.kt, DeadReckoning.kt, Gps.kt, MicProbe.kt
  audio/Chirp.kt           // chirp template, playback, matched filter
  audio/Ping.kt            // beeps
  net/NearbyLink.kt        // mesh (cluster) link
  net/BleRanging.kt        // Android 16 ranging sessions, tag advertising/scan (tag = suffix+flags+battery), GATT, pairing
  net/Probe.kt             // passive port (PendingIntent BLE scan, re-arm alarm) and the commander's probe advertisement
  model/Events.kt          // all messages + JSON
  log/SessionLog.kt        // export
  ui/CommanderScreen.kt, SensorScreen.kt, MapView.kt, ArrowView.kt
app/src/main/assets/yamnet.tflite, yamnet_class_map.csv
app/src/test/java/com/hush/LocatorTest.kt   // laptop-only synthetic test of the locator (JUnit 4.13.2)
.github/workflows/build.yml
```

## Build, run, inspect

```bash
export JAVA_HOME=/c/Android/jdk17 ANDROID_HOME=/c/Android/Sdk   # if the shell predates the install
./gradlew assembleDebug -q
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do adb -s $s install -r app/build/outputs/apk/debug/app-debug.apk; done
adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep -i 'RANKING\|BRIEF\|RANGING result\|DoA align\|BleRanging\|LOCATE\|Clock:\|Axis:\|Probe\|Activation\|Beacon\|Tag seen'
adb shell am start -n com.hush/.MainActivity --es role COMMANDER    # pick a role without tapping coordinates
adb shell am start -n com.hush/.MainActivity --ez probe true        # commander: ACTIVATE SENSORS
adb shell am start -n com.hush/.MainActivity --ez hush true         # commander: HUSH window (solo allowed)
adb shell dumpsys bluetooth_manager | grep -A8 'com.hush (Registered)'   # Bluetooth's view of the port: scan time, results
./gradlew testDebugUnitTest -q          # locator maths on synthetic phones, no device needed
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
