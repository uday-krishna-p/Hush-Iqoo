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
   rolling-median noise floor (mic gains taken as 1: identical phones, no chirp calibration), times the evidence for
   the chosen listen mode (TAPPING by default / VOICE / ANY). The brief ("Live · Human tapping · 90% · strongest at
   Sensor B · rhythm steady") and the starred row update every second. Multiple knock sources are separated by
   tempo/pattern ("Source 1 … | Source 2 …").
5. **HUSH = silence call.** One button: 20 s window with a strong three-pulse buzz and a quiet double beep at start,
   two pulses and a beep at the end → "Window ·" brief. STOP ends the window on every phone. HUSH refuses with zero
   sensors unless long-pressed. Nothing chirps on its own any more (team, 27 Sep: chirps are impossible on a chaotic
   site); AUTO-PLACE and `--ez range` still chirp on request.
6. **The arrow (compass plan, 27 Sep, `docs/PLAN-compass.md`).** Every phone times each knock at its two microphones
   and turns the delay into an angle from its own top edge (`KnockBearing`); left/right is a mirror the two mics
   cannot tell, so each knock votes for both compass bearings and the true one wins when the phone is turned or when
   the other phones settle it. Phantom clicks (the same signal in both channels) are dropped. The commander adds every
   phone's bearing votes (quality × rhythm, fading over 10 s) into one **fused bearing with a confidence** and sends it
   down the moment a vote arrives; **every phone draws that one arrow** through its own compass, gliding smoothly, and
   uses its own estimate only until the fusion arrives. Calibrated for this phone model: mic spacing 0.17 m, channel 1 =
   top mic. Works with no positions at all; meant for phones within a metre or so of each other (same direction seen
   from all of them).
7. **Map + crossing.** Positions come from a hand layout (`--es layout`, Place buttons, Align by pointing) or from
   GPS outdoors (strict rules, north-up); the chirp-based auto placement still exists behind AUTO-PLACE. With
   positions the commander draws each phone's bearing line on the map and crosses them into a red cross-hair with a
   rough radius, which adds a distance to the arrow when two lines meet at ≥ 20° in front of every phone. The
   timing-based locator (`Locator.kt`, "How the source is located") needs chirp clock sync and is dormant until the
   silent ultrasonic chirp (plan step 4) exists.
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
| Link | `NearbyLink` v2, `Strategy.P2P_CLUSTER`, service id `com.hush.v2`. Advertised name `C|<name>` / `S|<name>|<hops>`; a sensor advertises only once routed (no loops). Up: events, chirp/placement reports, `join`/`leave`. Down: commands (`ASSIGN` carries `to=<name>` and the commander's Bluetooth address). The commander keys sensors by **name**. One-hop verified from the laptop; multi-hop needs a hall test. Since 27 Sep 03:05 links are `ConnectionType.NON_DISRUPTIVE` (Bluetooth, no Wi-Fi hotspot upgrade: the upgrades stalled links 2.5–4 s at a time). Down also: `Fix` (fused bearing, crossing or loudest phone, dots, north). |
| Audio capture | `AudioRecord` **stereo** (both mics are distinct: 78 % channel difference), 48 kHz, 16-bit. Source order VOICE_RECOGNITION → MIC → UNPROCESSED (UNPROCESSED measured ~10 dB quieter). Room level is ≈ −60 dB RMS on these phones. Mic 0 feeds everything; mic 1 only the chirp ring buffer. 8 s ring buffer per mic with an absolute sample counter. |
| Classifier | Stock YAMNet TFLite (mediapipe float32, input [15600], output [1,521], **no embedding output**). Input peak-normalised with gain capped at ×20 (×1000 turned room rumble into "Vehicle"). Buckets (exact display names): VOICE = Speech, Child speech, Conversation, Narration, Shout, Yell, Children shouting, Screaming, Whistling, Whistle. IMPACT (corroboration only) = Tap, Knock, Hammer, Hands, Thump/thud, Wood, Bang, Slap/smack, Clapping, Finger snapping, Tick, Tick-tock, Dishes, Cutlery, Chop, Chopping, Percussion, Drum, Wood block, Basketball bounce, Bouncing. MACHINE = Engine (+ light/medium/heavy/starting), Idling, Power tool, Tools, Drill, Jackhammer, Sawing, Chainsaw, Vehicle, Motor vehicle (road), Motorcycle, Aircraft, Helicopter. YAMNet is reliable for voice and useless for knocks (calls them Dishes/Stir/Hammer at random). |
| Tap detector | 10 ms frame energies on mic 0. Onset = frame ≥ **×3** the window median AND ≥ ×2 the loudest of the previous 3 frames; 80 ms refractory; ≤ 8/s; **ring-down**: energy 100 ms later ≤ 60 % of the onset frame (knocks measured 3–27 %, coughs/syllables stay loud). Tuned from two 90 s recordings: quiet ×2–6, speech ×2–5, knuckle knocks ×11–28, soft fingertip ×3–14. Onset times are sample-accurate for later TDOA. Since 27 Sep 02:50 onsets that are the same signal in both mic channels (electrical clicks at −42 dBFS, a third of all onsets in a quiet room) are dropped as phantoms before the rhythm tracker and the arrow (`Onset … PHANTOM`, `phantoms=N` in the window line). |
| Rhythm | `RhythmTracker`, 8 s history, audio only. Steady = ≥ 3 onsets with gap CV < 0.45 at any tempo (people knock at 1/s or 2–3/s; 600 ms grouping once merged fast knocking into one endless group). Patterns ("3-2") from group sizes repeating ≥ 2×, rotated to start with the largest group. Cap 40 onsets/8 s. Reports `tempoMs` (signature). **Single impacts never make a headline.** |
| Self-noise | The app's own start buzz (3 × 700 ms pulses rattle the phone), beeps and chirps once produced phantom "Source 1 / Source 2". Now: rhythm reset at window start; onsets ignored 3.5 s after start and 1.5 s after any chirp; those seconds carry zero weight in scoring; the noise floor never samples them. |
| Noise floor | Per phone, **rolling median of the last 30 quiet, still, chirp-free seconds**. (A 3 s mean sampled during chirps once zeroed two sensors' scores.) |
| Mic gain | Chirp-calibrated: received chirp RMS × distance must match across phones for the same chirp; the ratio to the commander is the sensor's gain (median of up to 12 samples, clamped 0.2–5). Ranking divides loudness by it. Recomputed every ranging round; logged as `Mic gain: sensor B = 1.3×`. |
| Live score | Per event: quality (0 if moving or self-noise, else 1) × max(0, rms − floor) / gain × evidence; decayed with τ = 15 s. Evidence: TAPPING = rhythm score, VOICE = YAMNet voice, ANY = max. Window score (HUSH) = mean of the 5 best seconds after the first 3.5 s. |
| Sources | Sensors with rhythm ≥ 0.9 grouped by same pattern name or tempo within 25 %; numbered strongest first. |
| Accelerometer | `AccelChannel`, 200 Hz (fastest rate needs a permission and crashed the service), high-passed magnitude. **Jolts are never onsets** (they flooded the tracker on a handled phone). Jolts only tag heard tapping "✓felt" (within 100 ms of an audio onset) and set ⚠moving (rms > 0.25 m/s²; at rest 0.007, handled 0.4–3). Movement never hides a label: in a collapse everything trembles. |
| Hush signal | Start: 3 × 700 ms pulses at full amplitude, alarm-class vibration, + double 2.5 kHz beep at **10 %** alarm volume. End: 2 × 400 ms + 1.8 kHz beep. Vibration strength is capped by the phone; Settings → Sound & vibration is the last lever. |
| Acoustic ranging | `Chirp.kt` 80 ms 2–6 kHz Hann sweep at **40 %** alarm volume (team's request; raise for a hall). Matched filter (27 Sep): FFT correlation on a 16 kHz copy as an analytic signal (its magnitude = smooth envelope, so no 4 kHz carrier-cycle slips), first arrival = earliest envelope peak ≥ 0.3 × the strongest and ≥ 6 × the median within 30 ms before it (the direct path between phones on a table is often weaker than a reflection 3–20 ms later), then refined at 48 kHz ±12 samples with the chirp and its quadrature partner, parabolic sub-sample peak. **37–94 ms per 5 s search on the phones** (sample-by-sample: 1.2–5.6 s). `ChirpTest` covers it. SERIAL chirps: one letter at a time, each phone searches ±2.5 s around its command, commander moves on when all reported or after 7 s. Pair distance `D = c/2·[(t_i(j)−t_i(i)) − (t_j(j)−t_j(i))]/fs + 0.12 m` (clock offsets cancel). Measured: AB 0.91 (real ≈ 1.0), AC 0.56 (real ≈ 0.5), later rounds 0.54–0.58 for the same layout. Detection strength 95–1780× vs threshold 5. Self-checks: timing filter ±0.35 s (a wrong peak once produced 59.7 m), triangle inequality and ≤ 30 m, one retry of lost pairs, **two rounds within 20 % before the map moves**. Only the first three letters form the map (N > 3 solver not built). |
| Map frame | B origin, C on +x, A (commander) moves inside; scale fixed at first ranging (1.6 × the largest side). A's dot moves by step counting between rangings. A settled sensor (moved, then still 3 s) triggers a re-ranging (only with chirp calibration on). Without chirps: hand layout (`--es layout "B=x,y;C=x,y"`, Place buttons, Align by pointing for north) or GPS outdoors (north-up, A's dot follows its fix); `--es layout clear` drops the stored layout and pointing. |
| North | Ranging alone cannot know rotation. Sources, best first: (1) **two-mic direction of arrival** of B's and C's chirps at the commander (sub-sample inter-mic delay; mic spacing solved against the triangle's known angle, then held as a median; skipped when phones < 0.8 m apart; rotation smoothed over 5 rounds); (2) the commander's walk (A's shift on the map vs compass bearing walked; moves > 10 m ignored); (3) placement walk (step detector + compass on carried-out sensors); (4) manual Place buttons. First DoA run: spacing 0.10 m, angles 44°/8° vs true 38°, spread 1°. Physical direction test still failing at 50 cm spacing (near field) — needs ≥ 1 m. |
| Compass arrow | `ArrowView` + rotation-vector `Compass`; angle = mapBearing(A→target) + rotation − heading. Target = the located source when there is a fix < 60 s old, else the strongest sensor. Since 27 Sep 02:30 the drawn arrow glides to its target the shortest way round (τ 0.22 s, ≤ 480°/s, settles in ~0.6 s), the screens update the target 20×/s and the compass runs at GAME rate (~80 ms lag), after the team found the arrow "not fast enough" and asked for "a lazy turning effect". |
| Own knock arrow (27 Sep) | `KnockBearing.kt`, every phone, first claim on the arrow: the two-mic delay of each knock → angle from the phone's top (spacing 0.155 m, mic 1 = top mic), both mirror bearings voted into a decaying histogram (τ 8 s since 03:00, 5° bins, σ 12°); faint twin arrow until turning the phone resolves it (best peak ≥ 1.5× the second); the solid arrow is always the stronger candidate, kept only through near ties (within 8 %). Gates q ≥ 0.4, |delay| ≤ 33 samples, felt knocks × 0.3, weight × loudness over background (×10 → 1, clamped 0.3–3); shown while own rhythm ≥ 0.9, a Hush window runs, or 3 knocks ≥ ×8 in 8 s (held 8 s). Measured 27 Sep 02:00: spacing 0.17 m, channel 1 = top mic. When the phone hears nothing it draws the commander's fused bearing of the phones that do (`SHARED:`), and an open twin is settled by it. Constants changeable with `--es mic1top` / `--ef micspacing`, kept in preferences. |
| Fused bearing (27 Sep) | Commander: every phone's own-arrow bearing (in its once-a-second event: `br`, `bq`, `br2`) is one vote per second into an accumulating 5° histogram with τ = 10 s, weight = correlation quality × (0.5 + rhythm score), half a vote per candidate while mirrored. Peak = the fused bearing; resolved when ≥ 1.5× the runner-up; confidence = share of votes within ±24° (≈ 0.35 with a mirror open, 0.8+ when three phones agree). Sent down in the `Fix` (`sb`, `sb2`, `sbq`, `sby`; `pt=false` when there is no map point) the moment a vote arrives, on a 2° change, ≤ 3/s. Every screen draws it first (one arrow, green deepening with confidence, "fused from A,B,C · confidence N%"), the own estimate only when the fusion is missing, > 4 s old or made of this phone alone; an open own twin is settled by it within 40°. Log: `SHARED: bearing … from A,B, conf …`. |
| Passive port / probe | `Probe.kt`: victim side = PendingIntent BLE scan, filter on 16-bit UUID 0xA5A7, `SCAN_MODE_LOW_POWER`, re-armed at boot (+10 s), app update (+3 s), every 15 min (inexact alarm) and whenever the app opens; a re-registration waits 2.5 s between stop and start. Commander side = 30 s non-connectable advertisement of 0xA5A7 with its name suffix, high power. Woken phone: notification (channel "rescue", full-screen intent) → `BeaconActivity` → `SensorService` (byProbe) → Nearby SENSOR; tag flags bit 0 = woken by probe; back to passive after 10 min without a commander. Commander shows "Discovered phones" from tag sightings: median RSSI, "~N m?" (−59 dBm at 1 m, exponent 2.7), warmer/colder trend, battery, letter once joined. |
| Source locator | `Locator.kt`, see "How the source is located". Constants: cell 0.25 m, σ_t 0.5 ms, σ_amp 6 dB (weight 0.5), σ_doa 12° (voice 20°), decay 25 s, hold 3.5 s for late reports, mic spacing = median solved by the chirp rounds else 0.10 m. `LOCATE knock #n heard by A,B,C: A:+0.0ms B:+3.1ms …` and `LOCATE fix: peak (x, y) region … radius … nearest … spread …` in the log. Export gets a `locate` record per update. |
| Radio ranging | `BleRanging` (Android 16 `RangingManager`). Capabilities on the I2501: CS enabled, RSSI enabled, UWB/RTT absent; own address read from the capabilities object's `toString`. Sensors advertise a connectable BLE tag (service UUID `0000A5A5-…`, data = name suffix); the commander scans for the tag to learn the sensor's **live** (rotating) address, opens a GATT link, then initiates; the sensor learns the commander's live address from its GATT server and answers it. **Result so far: CS opens, starts and closes with reason 3 (UNSUPPORTED) within 1 ms every time**, even over an open link with the responder ready; RSSI ranging then runs continuously but reads 6–14 m for phones 0.5 m apart. RSSI is displayed with "?" and never used to drop a chirp round. Latest build requests a one-time pairing and retries CS once bonded (untested). |
| GPS | `Gps.kt`, framework `LocationManager`; `lat/lon/gacc` in events when a fix < 60 s old exists; in the export. Since 27 Sep the commander places the phones from it outdoors (`GpsLayout.kt`: 3 fixes each, ≤ 20 m accuracy, nearest pair ≥ 2× the worst accuracy), north-up map, hand layout wins if stored. |
| Export | `SessionLog`: header (commander, sensors, dots, mode, last brief) + every event line + `hush`/`ranging_start`/`ranging`/`ranking`/`stop` records → `Downloads/hush-<date>-<time>.jsonl` via MediaStore. |
| Permissions (declared, requested at role pick) | RECORD_AUDIO, BLUETOOTH_SCAN/ADVERTISE/CONNECT, NEARBY_WIFI_DEVICES, ACCESS_FINE/COARSE_LOCATION, ACTIVITY_RECOGNITION, RANGING (API 36), VIBRATE, FOREGROUND_SERVICE(+MICROPHONE), ACCESS/CHANGE_WIFI_STATE, legacy BLUETOOTH/ADMIN. |

## Status (27 Sep 03:45)

**Where things stand.** The demo path has no chirps. Every phone listens, times each knock at its two mics, drops
phantom clicks, and turns real knocks into a bearing (its own arrow; left/right open until it is turned or the other
phones settle it). The commander accumulates every phone's bearing votes into one fused bearing with a confidence
and forwards it at once; every phone draws that single arrow through its own compass, gliding, with the confidence
in the label. Calibrated for this phone model in the team's half-metre space (spacing 0.17 m, channel 1 = top mic,
stored on all phones). Positions by hand layout or GPS outdoors; with positions the bearing lines cross into a
point. Mesh on Bluetooth only. **Installed on all three phones 27 Sep 03:18** (6a46 commander, ef39 B, 991e C).
Open, in order: (1) compass agreement between phones (a ~50° disagreement seen once half a metre apart: lay the
phones parallel, compare `heading=` in the logs); (2) the rotation test for the real delay-to-angle curve (raw
stereo audio is kept for 300 s from a role start: restart the roles, then six 45° steps, 5 knocks each, 6 s apart,
then `knockdir.py` and the WAV); (3) the crossing with a hand layout of the fixed cluster; (4) the sweep demo with
one phone armed and the app closed; (5) "every phone both roles" (auto-role, HUSH/SWEEP buttons on every phone,
brief sent down); (6) the 1 s audio window (a knock is reported at the end of its second; a 250 ms hop would cut
~0.4 s); (7) silent ultrasonic chirps (plan step 4) to bring back ranging, clock sync and the timing locator.
The paragraphs below are the chronological log, newest first.

**Closest phone replaces the arrow in the demo path, 27 Sep 05:40 (team after 15 h: "none of the features are even
remotely working"; RCA from the 04:16-05:18 logs of 6a46 and 991e):** (1) phones 0.4-0.5 m apart make every
multi-phone cue tiny (04:32 burst: A vs 991e peak levels 1-4 dB apart); (2) the two-mic mirror never resolved because
the phones lay parallel as SYNC asked (A 106/322, B 123/303, fused confidence 0.44-0.48; median 0.41 over 308
fusions); (3) compasses jump (B read 122 -> 297 -> 204 deg in 25 s on the table); (4) the live ranking used 1 s RMS minus a
rolling floor that climbs during knocking (04:32:13: knock x43, rms 0.0011 < floor 0.0017 -> score 0); (5) 0 `LOCATE fix`,
0 `CROSS: source`, 0 `Clock:` lines on either phone: the timing locator and the crossing never produced a result.
New: `Closest.kt` (commander) takes every phone's per-knock PEAK (onset reports now carry `ag`, the knock's age in ms,
so the commander puts it on its own clock), matches detections within 350 ms as one knock (judged 2.2 s later, needs
one phone >= x8), the loudest phone wins it; the panel counts wins over 20 s: "CLOSEST: Sensor B - loudest on 7 of 9
knocks - 12 dB louder than A" (LEANING below 60 %). Board field `cl` shows it on every phone. Log `CLOSEST knock: A=0.0780
B=0.0610 -> A +2.1 dB over B`, `CLOSEST panel: ...`; export record `closest_knock`. Arrow and SYNC COMPASS are hidden
(layout `gone`, code kept). `ClosestTest` (5) passes. Test: phones in three corners of the 2 m x 1.5 m room on cloth,
knock 0.3 m from one phone 10x: pass = that phone wins >= 8. Installed on all three 05:52. **First run 05:53-05:56 (team knocking, positions not logged): clear runs A 17 knocks in a row (+12..+22 dB), C 27 of 28 (+8..+20 dB), B 15 in a row (+17..+23 dB); the panel switched C -> B within 2 s (LEANING) and 3 s (CLOSEST). Knocks between runs won by < 3 dB were coin flips.** Since 06:05 such knocks are logged `(tie, not counted)` and left out of the count (`Closest.MIN_LEAD_DB = 3`); the panel says "of N clear knocks". **Second run 06:00-06:01 (ef39 commander, 6a46 B, 991e C; team knocked A, B, C):** B 9 of 10 clear knocks
(+5..+28 dB), C 20 of 20 (+8..+30), then A 32 of 34 (+6..+23; not in the team's list, ask); ~95 % of knocks to the right
phone. But the panel needed 7 s (B -> C) and 10 s (C -> A) to switch, because it counted 20 s of wins. Since 06:15 it
decides from the last 6 clear knocks (`Closest.WINDOW_KNOCKS`): the new phone leads after 3 knocks, CLOSEST after 4. **Third run 06:05 (same roles, A then B then C):** every clear knock right (A 11/11 +7..+17 dB, B 12/12
+10..+25, C 9/9 +28..+33), but the panel still showed the new phone ~4.5 s after the first knock there: the commander
waited a fixed 2.2 s before judging each knock. Since 06:25 every SensorEvent carries `wa` (ms since that second's audio
ended), the commander tracks per phone up to when everything is reported, and judges a knock once every talking phone is
past it + 250 ms (`Closest.READY_MARGIN_MS`; 2.2 s stays as the fallback). A report arriving after its knock was judged is
dropped (`CLOSEST late:` in the log) so it cannot make a false one-phone win. Each `CLOSEST knock` line ends with
`judged N ms after the knock`. Next lag cut if needed: the 1 s audio chunk (open item: 250 ms hop). Team 06:30: "working perfectly".

**Where between the phones, from loudness, 27 Sep 06:40 (`LoudnessLocator.kt`):** the same last 6 clear knocks, each
phone's peak and its hand-layout position: a spot fits when ln(peak) + ln(distance) is equal across the phones (1/r law,
knock strength cancels), Cauchy misfit with 6 dB per unit, grid 5 cm over the phones + 0.3 m. Needs 3 placed phones
hearing the knock (two phones = a circle; in the narrow strip around two phones it looked falsely precise). Margin 0.3 m,
not 1 m: two level ratios are two circles crossing at TWO points, the second outside the phones (simulated: a knock
0.3 m from B came out 0.9-1.2 m away with a 1 m margin). Simulated in the 2 x 1.5 m corners: 2 dB level noise 0.05-0.35 m
off (up to 0.6 m in the corner far from both others), 5 dB mostly 0.2-0.5 m, a few 0.8-1.5 m. Panel line "Sound ~ 0.3 m
from Sensor B (+-0.2 m)", red cross-hair + shaded region on every phone's map (Board field `wp`), log `WHERE: (x, y) m
+-r from N knocks ...`, export record `where`. The layout may now name the commander by its suffix too (any phone can be
commander): `--es layout "6a46=0,0;ef39=2,0;991e=2,1.5"`. Not yet run on the phones.

**WARMER / COLDER for a carried phone, 27 Sep 06:55 (`Warmth.kt`; team: "since the devices would also move isnt doing
this useless? we just want something where the devices will point towards the sound"):** positions do not survive
moving phones, and no phone can know the direction to another (GPS indoors none, BLE RSSI 6-14 m at 0.5 m, Channel
Sounding refused, no UWB, chirp distance 0.78 for 0.40 m, compass jumped 175 deg in 25 s): no arrow to the closest phone.
Instead each phone is a metal detector: per judged knock, its peak over the median peak of the other STILL phones (dB;
knock strength cancels; a carried phone in the reference would make still phones read COLDER), mean of the last 2 knocks
vs up to 4 before, +-3 dB = WARMER/COLDER. Moving phones now report into `Closest` (flag per detection): they feed the
trend but never the vote (`CLOSEST knock: ... B=0.0300(moving)`; a knock only moving phones heard = "no vote"). Log
`WARMTH B: WARMER +4.2 dB (level vs the still phones +8.1 dB, 6 knocks) moving`; Board field `wm`; each phone shows its
own line under the panel (orange WARMER / blue COLDER / grey no change), the panel adds "Carried: B warmer +4 dB". The
"Place 3 phones" hint is gone (the loudness point stays dormant without a layout). `WarmthTest` (4) passes.

**The arrow is back, fused from confirmed knocks only, 27 Sep 07:10 (`KnockDirection.kt`; team: "now that the knock
recognition is so good, try bringing back the compass ... enough phones would form a complete 360 view"):** checked first
on the 06:05 run (knocks at each phone in turn): a phone >= 1 m from the knock repeats its two-mic delay within +-1 sample
(ef39 -23.4..-25.3 for C's knocks, 6a46 17.6..18.1 for A's, 991e ~25.7 for A's, ~5 for B's); the phone next to the knock
(~0.3 m) scatters (ef39 -26..+26 for its own). So every knock `Closest` judges carries each phone's delay, quality,
heading and felt flag (its loudest detection); still phones vote heading +- angle (both mirrors) weighted by quality,
felt x0.3, the knock's clear winner (>= 6 dB) x0.3; the last 6 judged knocks (20 s) make a 5 deg histogram; resolved when
the peak >= 1.5x the runner-up; confidence = share within +-24 deg. Room noises never vote. Board field `kd`; every phone
draws that one bearing minus its own gyro heading (after SYNC); all older arrow sources are bypassed
(`SensorScreen.drawKnockArrow`). Arrow and SYNC COMPASS are visible again. Needs: SYNC with the phones parallel, then
the phones at clearly different angles (parallel phones share the mirror), within ~0.5 m of each other with the knock
farther away (parallax). Log: `KNOCKDIR knock: A dl=.. q=.. hd=.. -> th.. votes ../.. w..`, `KNOCKDIR fused: 70 deg
resolved conf 0.8 from A,B,C over 6 knocks`, per phone `ARROW drawn: screen .. = bearing .. - heading ..`; export
`knockdir`. `KnockDirectionTest` (5) passes. The delay-to-angle curve is still the cosine law (rotation test never
done): expect +-30 deg until measured.

**Correction:** the clock times in the entries above from "Where between the phones" on (06:40, 06:55, 07:10) were
estimated, not read: the phones' clocks put the arrow test at 06:43-06:47 and the chirp test at 06:49.

**Why the fused arrow failed, and what the chirps showed, 27 Sep 06:43-06:52 (phones' clocks):** SYNC worked (A 136,
B 137, C 139 deg). But almost every knock gave each phone an end-on two-mic delay (about -24 samples, "beyond my top
edge") whichever way it lay: B at 266 deg and C at 347 deg both read -24 for the same knocks, and 991e reached -42
samples where 17 cm of mic spacing allows +-24, so the stereo channels are not in step (capture processing?). The fusion
therefore jumped between the phones' own top directions (138 / 266 / 347 deg). The two-mic arrow is off
(`SensorScreen.drawKnockArrow`, code kept, tag `v-before-chirps`). Chirp test (`--ez range true`, 19-21.5 kHz at alarm
x0.8): every phone heard every other phone's chirp at 15-155x the threshold (the band carries: no need for a lower
frequency or more volume); clock offsets repeat round to round (B 29, C 14 samples in a minute: drift); arrival-time
differences between phones for the same knock repeat (C then B, +0.4..+0.6 ms). But chirp DISTANCES are wrong for the
team's 1.2 m equilateral triangle: AB 2.03/2.02/2.08, AC 2.02/2.11/2.03, BC 1.46/1.46/1.45 m (repeatable, wrong):
split per phone, ef39 about +0.7 m and 6a46, 991e about +0.1 m each (a phone's own-chirp arrival is off, as in the
00:30 notes). So positions come from the TAPE (`--es layout "6a46=0,0;ef39=1.2,0;991e=0.6,-1.04"` on the commander),
clocks from the chirps (third-party chirps, unaffected), and the arrow now points from each phone to the timing
locator's spot (`Engine.localSourceArrow`). ef39 held a stale 0.40 m tape layout from 06:17, which is why the locator
ran during the arrow test with radius ~10 m. Tags: `v-closest-working` (9a12fcc, last confirmed), `v-before-chirps`.

**Auto-locate: positions from the chirps every round, 27 Sep (`AutoLocate.kt`; team: "have it auto locate every few
seconds with the chirps"):** the chirp range error is a fixed amount per phone (from the three 06:49-06:52 rounds on the
1.2 m triangle: ef39 +0.70/+0.74/+0.73 m, 6a46 +0.14/+0.09/+0.15, 991e +0.13/+0.18/+0.10). With a tape layout on the
commander, each round's chirp - tape = b_i + b_j gives every phone's error (`AUTOLOCATE calibration round: ...`); two
rounds agreeing within 0.10 m switch it on (`AUTOLOCATE on: range errors ...`). Then every round subtracts the errors,
rebuilds the triangle from its three sides and turns (or mirrors) it onto the previous positions (2-D Kabsch; distances
say nothing about orientation, so the arrows stay right only while the phones do not ALL move a lot between rounds);
when some phone moved > 0.08 m the positions replace the layout in memory (`AUTOLOCATE round: corrected ... -> ...
moved N m, map updated`, export `autolocate`). Rounds run back to back (2 s gap, ~9 s per cycle with three serial
chirps). Three phones only (A and the first two sensors). A new tape layout recalibrates. `AutoLocateTest` (5) passes.
Needs on the phones: the tape layout entered once with the phones on it, then two rounds (~20 s) before moving them.

**Timing locator back, inaudible chirps, every phone locates, 27 Sep 04:40 (team: "we are just pointing a compass
… not using the power of multiple devices to triangulate"; "if the chirps are made, make them at such high frequencies
that humans cant hear"):** (1) *Top-band test* (`tools/calibration/ultrasweep.py` makes the file,
`ultrasweep_measure.py` measures it): 6a46 playing a 12–23.5 kHz sweep and steady tones to its OWN mics at alarm ×0.5:
19 kHz 47/31 dB above the background (mic 0 / mic 1), 20 kHz 39/24, 21 kHz 33/17, 22 kHz 21/15, 23 kHz nothing (the
capture filter). The between-phone level is not measured yet (two phones were off USB). (2) *The chirp* is now a
120 ms 19–21.5 kHz Hann sweep at alarm ×0.8; the matched filter's coarse pass runs at 48 kHz (the 16 kHz copy could
not hold it); `ChirpTest` passes (found to the sample, weak direct path before a strong echo). The two-mic delay of a
chirp is now nearly always ambiguous (2.4 samples per carrier cycle) and stays gated off; the test now checks that an
accepted delay is right. (3) *Knock detector vs chirps*: an onset whose second-difference energy is > 8× its energy
(19–21.5 kHz ≈ 13–14, white noise ≈ 6, knocks < 2) is a PHANTOM "(ultrasonic: a chirp)"; the blanking after a chirp
command dropped from 1.5 s to 0.3 s. (4) *Clocks from tape positions*: with a hand layout (`--es layout
"B=x,y;C=x,y"`, metres, A at 0,0; or GPS positions) a chirp round only syncs the clocks, using the TAPE distances
(chirp ranging read 0.78 m for a 0.40 m pair) and preferring third-party chirps (a phone hearing its own chirp had odd
delays); it never moves a taped map; `RANGING vs tape: AB tape 0.50 chirp 0.52 …` is logged every round (step 4:
can the inaudible chirp replace the tape?). A round runs 2 s after the layout is applied and every 60 s (commander,
`Clock round (every 60 s, inaudible …)`), and needs only 2 phones. The status line says "Clocks: inaudible chirp
round N, all phones timed". The locator's fix is no longer gated on the old `chirpCalibration` flag. (5) *Orientation
without pointing*: tape the layout with +y along the direction the phones' tops point when SYNC COMPASS is pressed
(x to the right); SYNC then sets the map rotation (`ALIGN: taped layout oriented by SYNC`). (6) *Every phone locates
(step 3)*: the commander relays every phone's onset report down the tree and sends each chirp round down
(`ClockRound`: who heard which chirp at which sample, the distances used); the board carries positions (metres),
scale and rotation. Each sensor feeds its own `Locator` and runs it once a second (`LOCATE here: …`); every screen's
arrow now starts with the located point seen from THAT phone's position ("→ KNOCK · 1.2 m ±0.3 · located by timing
across all phones"), then own-arrow-heard-well, crossing, fused direction. (7) *Step 4, not built*: positions from
knocks alone (clock offsets + positions solved jointly from TDOA + two-mic angles: in principle ~2 knocks at different
places for 3 phones, fragile with the mirror and table-borne knocks) and GPS time for clock sync (raw GNSS clock is
precise, but tying it to the audio sample clock needs AudioRecord timestamps accurate to < 0.1 ms; outdoors only).
The practical path is the chirp-vs-tape log above. Not tested on phones yet: installed on 6a46 only (ef39 and 991e
dropped off USB); first test = the between-phone level of the chirp, then a clock round, then knocks.

**Pointing at a nearby knock, gyroscope heading, 27 Sep 03:45 (team: "sensor B and C now point in the same
direction, but … all point in the direction of the sound"):** the 03:38 round was knocks beside A (A: 11–14 knocks
at ×14–93, dl ≈ +22.5 = straight off its bottom; B: 3 faint knocks; C: none). The fused bearing is ONE compass
direction; B and C drew it from where they lie, i.e. parallel to A's line, not at the knock. A fused direction is only
right for a knock far away compared with the phone spacing; a phone off to the side of a near knock can only point at
it if it hears it itself or knows where the phones are. Changes: (1) precedence on every screen is now **own arrow
when this phone hears it well** (resolved by turning or by the other phones, ≥ 3 knocks: `Engine.ownHeardWell`,
label "heard here … left/right from A,B") > the commander's point (crossing / located source, needs positions) >
the fused direction (label adds "same direction as the phones that hear it (Place the phones on the map …)") > own
unresolved. (2) **Heading from the gyroscope** (`TYPE_GAME_ROTATION_VECTOR`, started once from the magnetic
heading): after the phones were moved, A's magnetic error changed by ~130° while it still reported accuracy 3.
SYNC is now once per session (not stored); the screen shows "Heading N° (gyroscope) · synced ±N°" or "NOT SYNCED".
Also seen: C's 03:37 knocks were 14/16 "felt" (through the table), which read as sideways: cloth under the phones.
991e stopped answering over USB during this install (it joined the mesh; build unknown until re-plugged).

**Compass sync and one screen on every phone, 27 Sep 03:30 (team: "why does sensor A point the opposite way … they
are all kept parallel"; "they all need to have the same UI (of commander)"):** cause of the backwards arrow: with
the three phones parallel their compasses read A (6a46) 96–98°, B 236–238°, C 245–246°. Every phone draws the
fused bearing minus its OWN heading, so A's 141° compass error turned its arrow almost around. A's rotation-vector
sensor still reported accuracy 3 ("high"), so the phone's own flag cannot catch this. Fix: **SYNC COMPASS** (button
under the arrow on every phone; `--ez sync true`): with the phones lying parallel, the commander takes every phone's
current heading (its own plus `hd`, now in every event), picks the circular median (so one bad compass is outvoted;
with two phones the commander's wins) and sends `SYNC hd=<ref>` down; each phone stores a correction
(`compassOffset` in the `hush_mic` preferences, kept across restarts) and restarts its bearing votes. Long-press
clears a phone's correction. Log: `COMPASS SYNC: headings A=96° B=237° C=246° -> every phone reads 237°`, then
per phone `COMPASS SYNC (…): raw …, now … (correction +141°)`. Measured 03:30: A +141°, B +0°, C −9°, all read
237°. The screen shows "Compass N° · correction ±N° · accuracy …" (figure-8 hint when the phone says low).
A constant correction is only right while the disturbance stays the same: re-sync after moving the phones to a new
place, and check whether A's error changes with its orientation (a magnet in a case would do that). **Same screen:**
sensors now use the commander's layout. The commander sends a `Board` down once a second after its own second
(brief, ranking, names, every phone's latest event, status lines, discovered phones); a sensor's HUSH (long-press
= solo), STOP, ACTIVATE, mode buttons and SYNC go up as a `Request` and the commander runs them (`REQUEST from B:
…`). The sensor's map shows the commander's dots and point from the Fix; Place / Align / Auto-place / Flip stay on
the commander. A sensor's export now holds every phone's seconds from the board. Verified from the laptop: boards
arrive every second (`BOARD brief:` on both sensors), SYNC requested from Sensor B ran on the commander and reached
all three. Not yet tried by hand: HUSH/STOP from a sensor's screen, the screen itself (phones were locked).

**One arrow, accumulating confidence, 27 Sep 03:35 (team: "instead of jumping around, increase confidence based on
the readings of the other phones", "why two arrows, it should be one"):** the commander's fusion is no longer a
snapshot of each phone's latest bearing but an accumulating 5° histogram that decays with τ = 10 s: each phone's
event adds one vote (weight = correlation quality × (0.5 + rhythm score), a resolved phone one vote, an unresolved
one half a vote per candidate). Agreeing phones pile up, one phone's swing barely moves the peak, and confidence =
the share of votes within ±24° of the peak (about 0.35 while a mirror is open, 0.8+ when three phones agree). The
faint twin arrow is gone on every screen: one arrow, whose green deepens with confidence, and "confidence N%" in
the label ("turn a phone to firm up" while the mirror is open). Rhythm over class (03:15): knocks in a steady or
patterned rhythm count up to twice a stray loud onset in each phone's arrow and in the fusion; the listen mode was
already TAPPING. GPS layout tightened (03:20): never closer than 8 m or 3× the accuracy, and only after two
solutions 5 s apart agree, after indoor noise placed the 0.5 m cluster 14 m apart at ±7 m. Open: the commander and
Sensor B disagreed on a world bearing by ~50° half a metre apart, likely compass error between phones (cables,
laptop, table): check by laying the phones parallel and comparing `heading=` in their logs.

**Fused bearing first, faster relay, Bluetooth-only mesh, 27 Sep 03:15 (team: "it keeps concentrating on a single
phone", "the relay between the phones is not that fast"):** every phone now draws the commander's FUSED bearing
first (all phones' microphones, mirrors resolved across phones, drawn through the phone's own compass) and its own
two-mic estimate only when the fusion is missing, older than 4 s or made of this phone alone (`Engine.preferShared`).
Labels: "→ KNOCKING · all phones fused: A,B,C" / "fused from A,B · left or right? …". Relay: a sensor's bearing used
to wait for the commander's own next second before it was fused and sent down, then only when it moved by 5° or
every 5 s; now the commander fuses and sends the moment a bearing arrives, on a 2° change, at most ~3 times a
second. Mesh: `ConnectionType.NON_DISRUPTIVE` on advertising and connection requests (Nearby 19.5.0), so links
stay on Bluetooth instead of upgrading to a Wi-Fi hotspot: the commander logged 29 "nothing from … for 2.5–4 s"
stalls in the 30 min before, every link at quality 3 (Wi-Fi). What "using every device" means without chirps: the
fused bearing (weighted votes of every phone's own arrow), the crossing (needs positions: `--es layout` and Align
for a fixed cluster, then knocks within ~1 m of a 0.5 m cluster get a point, farther ones a direction), and the
timing locator only with clock sync (silent ultrasonic chirps, plan step 4).

**Personas B and C built on branch `worktree-personas-bc`, 27 Sep 03:00–04:00 (docs/PLAN-personas-bc.md steps 1–5; NOT
installed on the phones yet, another session was using them; laptop build + 53 unit tests pass):** the first screen has
an "AT HOME" section with **ALERTS** (persona C) and **HOME** (persona B). Both roles run one phone alone: no Nearby, no
Bluetooth, no GPS, no chirp warm-up, no debug WAV, and YAMNet only on seconds ≥ 1.5× the floor or with an onset
(`Household classifier: ran N s, skipped M s` every 10 min). *ALERTS:* `SoundAlerts` folds each second into
categories (table in the plan) → `Alerting` buzzes the category's pattern (alarm-class; a knock replays its own
rhythm; ALARM repeats until dismissed), posts a high-importance notification on channel "alerts" whose full-screen
intent opens `AlertActivity` (colour + word over the lock screen, 2 Hz flash for 3 s, tap to dismiss), appends to
`files/alerts.jsonl`; the screen shows the last alert big, the history, a switch + TEST per category, TEACH rows,
a sensitivity switch. *TEACH:* 3 loud seconds → mean YAMNet score vector in `files/sounds.txt`; cosine ≥ 0.85 →
alert with the sound's name. *HOME → COUNT:* whistle second = whistle classes ≥ 0.3 + loud ≥ 4× + tonal ≥ 6×, or
tonal ≥ 15× and loud ≥ 6× in 600–4500 Hz; 8 s refractory; DONE = green flash + buzz + notification + loud double
beeps (60 % alarm volume); TIMER/ALARM alerts also shown. *HOME → FIND:* the own arrow (KnockBearing, tuned to
τ 60 s / 90 s / 2 knocks, histogram reset on arriving at a new spot) + `DeadReckoning` position → `WalkLocator`
marks when still ≥ 3 s with an arrow, ≥ 1.5 m apart → `Crossing.candidates` → fix, loudness (1/r) breaks a two-mark
mirror tie by ≥ 3 dB; prompts; HUM mode uses the whole second's 60–1500 Hz two-mic delay. **All thresholds are
start values from the plan, untested on real sounds: run step 0 of the plan first (what YAMNet says about a cooker
whistle, a doorbell, a microwave beep, a door knock, a smoke alarm), then tune `SoundAlerts.Category` and
`WhistleCounter` from the `window` and `ALERT`/`WHISTLE` log lines.** Laptop test without sounds:
`--es role ALERT` then `--es alerttest DOORBELL` (phone locked: blue screen over the lock screen).
**Deaf-aid additions, 27 Sep 04:30 (team: ship features, test briefly):** on the ALERTS screen, *Hearing now* (level bar in dB
above the room with quiet / LOUD / VERY LOUD, the sound names the model hears, the last 8 distinct sounds with times);
*Live captions* (`Captions.kt`: Android's speech recogniser, on-device when available, `EXTRA_PREFER_OFFLINE`, restarted
after every phrase; our own capture is stopped while it runs and restarted after, `Engine.setCaptions`; a typed name is
flagged "SOMEONE SAID …" with a buzz when it appears in a final caption); *Type to speak* (`Speak.kt`, on-device
text-to-speech, six quick phrases; while it speaks the alerts treat the sound as our own); *Torch* (`Torch.kt`,
camera flash at ~3 Hz for 3 s, or until dismissed for ALARM; switch on the screen) and *Night mode* (torch for every
alert, the alert screen at full brightness stays 20 min); a button to Android's Accessibility settings (system flash
notifications); KNOCK words carry the side from the own arrow ("KNOCK ×3 · left"). Untested on the phones: whether the
I2501 has the offline English speech pack (the status line says which recogniser it got), and whether the recogniser
gets the microphone from us cleanly.

**First run on the phones, 27 Sep 05:20–05:35 (ALERTS on 991e and 6a46, merged build):** the role starts with no radios,
TTS ready, mic open, model classifying; `--es alerttest DOORBELL` buzzed the pattern, found the back camera's flash
(`TORCH: camera with flash = 0`) and posted the full-screen notification; ALARM repeated until dismissed on the
phone. **Found and fixed:** the vibration motor is heard as knocks (×18–154), so every buzz produced a "KNOCK ×2" a
second later, endlessly; `Haptics.busyUntilMs` now marks seconds up to 0.6 s after any buzz as the phone's own noise
(alerts and whistles skip them, buzz onsets are dropped). **Captions:** the phone has Google's on-device recogniser
(`com.google.android.as`), which refuses `en-IN` offline; the code now falls back en-IN → en-US → default, and en-US
transcribed room speech within 10 s (`CAPTIONS: 'Hello hello the captions open'`). Captions that stop by themselves
now give the microphone back to our capture (it stayed paused before). Laptop hook: `--ez captions true|false`.
Not yet tried on the phones: type-to-speak's audible output, TEACH, the HOME role, KNOCK from real door knocks.

**Phantom knocks, 27 Sep 02:50:** in a quiet room a third of all onsets (91 of 269 on 6a46 in 8 min) were clicks of
about −38 dBFS (peak 0.011–0.016, ×5–8 the median frame) IDENTICAL in both microphone channels: two-mic delay 0.0,
correlation 0.95–0.98, rise 0, no accelerometer jolt, at random moments in the second, on every phone, with or
without the mesh. Checked on ef39's raw stereo audio (02:47–02:50, 68 phantoms vs 30 real knocks): with the mean removed they
are still coherent at lag 0 with correlation 0.89–0.97, AC peak ~270 counts (−42 dBFS), broadband with 22 % below
300 Hz; real knocks correlate at 0.55 with peaks ~2100 counts and 57 % in 3–8 kHz. A common signal in both mic
channels is electrical, not sound (radio, USB or flash bursts; the DC offset is only −3 counts, not the cause). They voted "beside the phone"
for the arrow and at ~1/s they made a "steady" rhythm (Sensor C's sideways twin arrow and a HUMAN TAPPING label
with taps=0). Now `Engine.phantomFlags`: an onset whose channel difference energy is < 5 % of the signal, or a weak
click (peak < 0.03) with correlation ≥ 0.93 at |delay| < 1 sample, is a phantom: logged as `Onset … PHANTOM`, kept
out of the rhythm tracker, the arrow and the onset reports, subtracted from `taps`; the window line has
`phantoms=N`. Also: the solid arrow keeps its candidate while the mirror twin is within 0.8 of it (Sensor C with the
knock beside it flipped 180° every second); a probe heard by a phone already in a role buzzes twice and shows
"Rescuer probe heard from …" for 10 s (the alert screen is only for armed phones with the app closed). The
02:29–02:37 rotation test was mostly phantoms plus two large bursts (cable end +26.9, top −29.9), and its raw
audio was wiped by a reinstall: redo it after this build (5 min recording from a role start).

**Calibration in a tight space and the shared bearing, 27 Sep 02:00–02:25 (team: the phones cannot be more than
0.5 m apart):** knuckle-knock rounds 0.5 m from the phones, all three on one table, nearly every knock "felt":
end-fire delays +22..+28 samples on all three phones → `micSpacingM = 0.17` (23.8 samples), set on all phones with
`--ef micspacing 0.17` and kept in preferences. The round beyond the USB-cable (bottom) end of 6a46 read +17..+25
with the arrow drawn at 148–176° on screen ("downish", team), so **channel 1 is the top mic: `mic1IsTop = true`
stays.** Table-felt knocks keep consistent delays (MAD 1–2 samples) but low correlation quality (0.4–0.7), so
`KnockBearing.MIN_Q` is 0.4 now. The arrow showed on every phone during the rounds; the turn test resolved the twin
several times, but with the phone in hand the compass heading jumped 60–180° between knocks, so left/right by
turning is only as good as the compass while handling. **Sensitivity** ("too low", team): the arrow now also
shows after 3 usable knocks ≥ ×8 the background within 8 s (irregular loud knocks), not only on a steady rhythm;
`KNOCK ARROW shown=… loud=N` logs the count. **Shared bearing** (the team's idea: one phone hears it and points
right, the others should point too): the commander fuses the compass bearings of every phone whose own arrow shows
(a resolved phone one vote, an unresolved one half a vote per candidate, 5° histogram; `SHARED: bearing … from A,B,
conf …` in the log) and sends it down in the Fix (`sb`, `sb2`, `sbq`, `sby`; a Fix with no map point says
`pt=false`). A phone that hears nothing draws that bearing through its own compass ("→ KNOCKING · heard by B · this
phone hears nothing yet"); a hearing phone with an open left/right twin takes the candidate within 40° of the shared
bearing ("confirmed by B"). Phones lying at different angles have different mirrors, so two unresolved phones at
different angles resolve each other without anyone turning; parallel phones do not (lay them at different angles).
This is right only because the phones are within 0.5 m of each other and the knock is farther away (parallax
< 30° at 1 m); at wider spacings the crossing takes over. Precedence on every screen: own arrow > shared bearing >
crossing (commander) > located source / loudest phone / commander's fix. Each phone votes with its RAW estimate, never
with the choice it was given, so the fusion cannot feed on itself.

**Compass plan, step 3 built 27 Sep 02:05 (positions from GPS, outdoors):** the commander keeps the last 5 GPS
fixes of every phone (they were already in every event) and every 5 s tries to place all phones from them
(`GpsLayout.kt`): each phone needs 3 fixes in the last minute with median accuracy ≤ 20 m; the median fix is
projected to metres east/north of the commander; accepted only when the nearest pair of phones is ≥ 2× the worst
accuracy apart (a table triangle is refused with the reason in the log). Then the dots are set exactly like the
hand layout (centred, 1.6× the largest side), `mapRotationDeg = 0` (map-up = north, so the compass bearing is the
map bearing), mirror off, `alignSource = "GPS (±N m)"`, the commander's dot follows its own fix and step counting
is ignored. A stored hand layout wins over GPS (`--es layout clear` drops it and the stored pointing); once GPS
places the map, pointing is dropped as unnecessary. Log: `GPS layout: A(0.0,0.0) B(12.3,-4.1) … m east/north of A,
worst ±4 m, nearest pair 12 m` or `GPS layout: not yet: <reason>` (once per reason), status line "Positions from
GPS (±4 m): …". `GpsLayoutTest` (4) passes; installed on all three phones; indoors it logs "B has 0 GPS fixes"
and nothing changes, so the outdoor test is still to do.

**Compass plan, step 2 built 27 Sep 01:50 (bearings on the map, crossing):** each phone's own arrow now rides in
its once-a-second event (`br` compass bearing, `bq` confidence, `br2` the mirror twin while unresolved; only
while the arrow shows). The commander draws a teal line from every dot in the direction that phone reports (faint
twin while mirrored) and crosses the fresh lines (< 5 s old) of the phones on the map into a least-squares point
(`Crossing.kt`): accepted when some two lines meet at ≥ 20°, the point is in front of every phone and within
3× the array size + 2 m. A mirrored phone contributes both candidates; every combination is tried and the one
with the smallest residual wins if it wins clearly (with two lines every combination fits exactly, so those stay
ambiguous until a phone is turned or a third phone hears it; the in-front rule alone settles many). Radius =
max(residual, range × tan 12°, 0.3 m). The crossing is the red cross-hair on the map when the locator has no fix,
goes down to the sensors as the existing Fix message (their arrows point at it when their own arrow is off), and
the commander's arrow shows "→ CROSSING · ~N m ±r · the arrows of k phones cross here" when its own arrow is off,
or appends "~N m where the arrows cross" to its own arrow when the two agree within 30°. Needs positions and north
(hand layout + Align today, GPS in step 3). Log: `CROSS: source at (x, y) m ±r from A→…° B→…° (lines meet at
≥ …°, residual …)`, `CROSS: no point from N lines (…)`; export record `cross`. `CrossingTest` (6) passes;
installed on all three phones. Laptop plumbing test 01:43 (991e as Sensor B playing `knocks.wav` at level 0.3, hand
layout B=1,0 and a laptop-set pointing): B used 30 of its own knocks, showed its arrow (`shown=true`, screen 98°,
twin 262°, unresolved because the phone never turned), its bearing reached the commander and became a map line
(`CROSS: no point from 1 line (B→93°/256°): need two phones on the map hearing it`); the commander heard 3–4 knocks
without a steady rhythm, so its own arrow stayed off (`shown=false`). The crossing itself needs two phones hearing
real knocks: not tried yet.

**Compass plan, step 1 built 27 Sep 01:20 (`docs/PLAN-compass.md`; team decision: approximate direction is the
goal, no audible chirps in the demo path):** every phone now draws its OWN arrow at the knocking from its two
microphones, with no chirps, no positions and no clock sync (`audio/KnockBearing.kt`). Each knock's two-mic delay
(already measured per onset, `dl=`/`q=` in the `Onset` lines) becomes the angle from the phone's top
(cos θ = delay / 21.7 samples for 0.155 m mic spacing). Left and right are a mirror the mics cannot tell, so each
knock votes for both compass bearings in a decaying 5° histogram (τ 15 s). The arrow shows only while this phone hears deliberate tapping (own rhythm score ≥ 0.9, i.e. steady or patterned tapping, the
HUMAN TAPPING label or a Hush window, held 8 s; 0.5 would be any 3 onsets in 8 s, which room noise gives too) and after 3 usable knocks within 15 s, each weighted by its loudness over the
background (×10 = weight 1, clamped 0.3–3, so knuckle knocks at ×11–28 outweigh room noises at ×2–6), with a faint twin arrow and "left or right? turn the phone to confirm" until the phone has been turned
and one peak wins (≥ 1.5× the other: "confirmed by turning"). Gates: q ≥ 0.5, |delay| ≤ 30 samples; knocks felt
through the table count 0.3 and the label says to put cloth under the phone when most were felt. The own arrow has
first claim on the arrow on both screens; the located source, the loudest phone and the commander's fix remain the
fallbacks. Log: `Onset … arrow` per used knock, `KNOCK ARROW screen=… twin=… bearing=… conf=… knocks=… felt=…
resolved=… turned=…` once a second. Constants from the calibration logs: knock delays cluster at 0 and at
±16–24 samples (end-fire ≈ 22 → spacing 0.155 m); the player phone hears its own knocks at +3.07 samples (mic 1
after mic 0, so mic 0 is the bottom mic beside the speaker and `mic1IsTop = true`: a guess until verified).
**Verify first (step 0 of the plan):** one phone on cloth, knock 1 m beyond its TOP edge; the arrow must point up.
If it points down: `adb shell am start -n com.hush/.MainActivity --es mic1top false` (kept in preferences, any
role). `KnockBearingTest` (5 tests) passes; installed on all three phones 01:20; started as commander from the
laptop: room-noise onsets (peak ×6) produced `KNOCK ARROW` lines within a minute, so the pipeline works end to
end and that is why the tapping gate and the loudness weight were added; no real knocks tried yet. Next: step 2 (sensors send their bearing, the commander draws the
lines on the map and crosses them into a point), step 3 (GPS positions outdoors).

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

**Open tests (each independent, 27 Sep 03:45):** compass agreement (three phones parallel for 10 s, `heading=` in
the logs within 10°); the arrow on real knocks with all three phones at different angles (one arrow on every phone,
same direction, confidence rising to 80 %+, `SHARED:` lines with three letters); a knocker walking around the
cluster (the arrows follow within a few knocks, no jumps); the crossing after a hand layout (`CROSS: source at …`,
red cross-hair near the knock, "~N m where the arrows cross"); the sweep with one phone armed and the app closed
(buzz, red screen, joins as a sensor) and the buzz + "Rescuer probe heard" on phones already in a role; the rotation
test for the delay-to-angle curve; a quiet window ("No human signal detected"); multi-hop ("2 hops" on the far
sensor). Chirp-era tests (locator on real knocks, `Clock:`/`Axis:` spreads, radio pairing) wait for the silent chirp.

**Known gaps / next steps:**
1. Compass offsets between phones would blur every fused arrow: measure; if real, calibrate the phones against each
   other from knocks they all hear (per-phone heading offset that makes their bearings agree).
2. Delay-to-angle curve of this phone model from a rotation test (the cosine law with 0.17 m read a knock straight
   beyond the cable end as 30° off-axis); a per-model lookup replaces `acos(delay / maxDelay)`.
3. "Every phone both roles": auto-role at launch (join a visible commander, else become one), HUSH/STOP/SWEEP on every
   phone forwarded to the hub, the brief sent down in the Fix; the map and list stay on the hub.
4. Audio hop of 250 ms for the tap detector so a knock is reported within 0.3 s instead of up to 1 s.
5. Silent ultrasonic chirps (plan step 4, gated on a roll-off test): ranging, clock sync, the timing locator, mic axes.
6. Channel Sounding refused by the stack; RSSI stays "nearer/farther" only. Positions for more than three phones.
7. Call-and-listen through the speaker; torch strobe; VICTIM mode (SOS chirp pattern + beacon), not started.
8. Remove the debug WAV (300 s of raw stereo per role start, private files) before any public build.

## Roadmap (revised 26 Sep 20:00 against PRD v2.2, `docs/PRD-v2.2.md`)

The PRD's Feature 2.1 is the priority: **passive victim phones (< 1 % battery/day) that a commander's probe wakes,
which then notify and buzz the victim and join the network as sensors.** Features 2.2–2.4 (Hush window, ranking,
radar canvas with arrow) already exist, and the arrow now points at the located sound rather than at a sensor pin.
Personas B and C (household, hearing-impaired) are planned in `docs/PLAN-personas-bc.md` (ALERT role first, then the whistle counter, then walk-to-triangulate). What follows is what the hardware and Android actually
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
labels); multi-commander flooding mesh; persona B (single-phone walk-to-triangulate, whistle counter) and persona C
(screen flashes + haptics for doorbells, knocks, alarms), both planned step by step in `docs/PLAN-personas-bc.md`.

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
    val micDelay: Float? = null, val micQ: Float? = null,  // two-mic delay over the second, voice only
    val bearing: Float? = null, val bearingQ: Float? = null, val bearingTwin: Float? = null,  // the phone's own knock arrow (compass °), only while it shows
    val heading: Float? = null   // hd: compass heading of the top edge after SYNC COMPASS
)
// Bluetooth (not JSON): probe advertisement = service UUID 0xA5A7 + 4-char commander suffix; Hush tag = service data
// under 0xA5A5 = 4-char suffix + flags byte (1 = woken by probe, 2 = SOS) + battery byte.
// Every phone, once a second and only when it heard knocks: each knock to the sample, on the sender's own audio clock.
data class Onset(val sample: Long, val peak: Float, val ratio: Float, val micDelay: Float?, val micQ: Float?, val felt: Boolean)
data class OnsetReport(val letter: String, val heading: Float, val moving: Boolean, val onsets: List<Onset>)
data class Command(val type: String /* ASSIGN|HUSH|STOP|CHIRP|SYNC */, val seconds: Int = 20, val letter: String?, val to: String?, val ble: String?, val heading: Float?)
// Sensor → commander: a button pressed on a sensor's screen (HUSH|HUSH_SOLO|STOP|SWEEP|MODE arg=TAPPING…|SYNC).
data class Request(val type: String, val from: String, val arg: String?)
// Commander → every phone, once a second: what the commander's screen shows (same UI on every phone), plus positions for every phone's locator.
data class Board(val brief: String, val mode: String, val ranks: List<Rank /* l, s, e, src */>, val names: Map<String, String>,
                 val events: List<SensorEvent>, val status: String, val discovered: String,
                 val pos: Map<String, Pair<Double, Double>>, val scale: Float?, val rotation: Float?)
// Commander → every phone after each chirp round; every OnsetReport is also relayed down (every phone locates).
data class ClockRound(val letters: List<String>, val heard: Map<String, Map<String, Long>>, val dist: Map<String, Double>)
data class ChirpReport(val hearer: String, val from: String, val sample: Long, val ratio: Float, val micDelay: Float?, val heading: Float?, val level: Float?)
data class Placement(val letter: String, val east: Float, val north: Float, val steps: Int)
data class Join(val name: String, val hops: Int, val leaving: Boolean, val ble: String?)
// Commander → every phone, the moment a bearing vote arrives (≤ 3/s) and every 5 s: where to point.
data class Fix(val seq: Int, val x: Float, val y: Float, val radius: Float, val knocks: Int, val edge: Boolean,   // map point (located source, crossing or loudest phone)
               val rotation: Float?, val mirror: Boolean, val scale: Float?, val north: String, val dots: Map<String, Pair<Float, Float>>,
               val near: String?, val hasPoint: Boolean = true,                                                // pt=false: only the fused bearing is carried
               val sharedBearing: Float?, val sharedTwin: Float?, val sharedQ: Float?, val sharedBy: String?)  // sb, sb2, sbq, sby: the fused bearing
```

## File layout (single module `app`)

```
app/src/main/java/com/hush/
  MainActivity.kt          // first launch: permissions + arms the passive port; role buttons (rescue + at home); back leaves the role
  BeaconActivity.kt        // red "rescuers nearby" screen over the lock screen; starts the sensor service
  Activation.kt            // the alert notification (full-screen intent) + haptic pulse when a probe is heard
  ProbeReceiver.kt         // system entry points: probe scan results, boot, app update, re-arm alarm
  SensorService.kt         // foreground service that keeps Engine alive
  Engine.kt                // everything: audio pipeline, fusion, hush window, live scoring, ranking, sources,
                           // chirp ranging, map frame, alignment sources, radio ranging glue, export
  HLog.kt                  // logcat + private file logger (the phones drop logcat)
  Ranging.kt               // two-way acoustic distance maths, triangle
  Locator.kt               // WHERE the sound is: clock offsets from chirps, mic axes, onset matching, grid fusion
  LoudnessLocator.kt       // WHERE between the phones: per-knock peaks + hand-layout positions, 1/r fit on a grid
  Closest.kt               // WHICH phone hears each knock loudest (per-knock peaks matched across phones): the demo headline
  Crossing.kt              // where the phones' own-arrow bearing lines cross (least squares, mirror combinations, in-front rule)
  GpsLayout.kt             // positions from every phone's GPS fix (median, east/north of A, accepted only when far enough apart)
  Alerting.kt              // persona C effects: vibration pattern, notification + full-screen intent, alerts history, taught-sound file
  AlertActivity.kt         // the colour screen with the word (DOORBELL, KNOCK x3, SMOKE ALARM...) over the lock screen
  WalkLocator.kt           // persona B FIND: marks while standing still -> Crossing of the marks' bearing lines, loudness tie-break, prompts
  SoundLibrary.kt          // TEACH a sound: fingerprint = mean YAMNet scores over 3 loud seconds, cosine match, one line per sound
  audio/AudioCapture.kt    // stereo AudioRecord loop, 1 s windows, ring buffers, debug WAV
  audio/Dsp.kt             // band-pass, RMS, downsample
  audio/Classifier.kt      // YAMNet + buckets
  audio/TapDetector.kt     // onsets with ring-down check, refined to the sample
  audio/Doa.kt             // two-mic cross-correlation → inter-mic delay (knock onsets and voice seconds)
  audio/KnockBearing.kt    // the phone's OWN arrow: two-mic knock delays → left/right candidates, resolved by turning (no chirps)
  audio/SoundAlerts.kt     // persona C categories (ALARM, DISTRESS, CRASH, DOORBELL, KNOCK, TIMER, PHONE, DOG, WATER, SPEECH, TAUGHT, COOKER): rules, debounce
  audio/WhistleCounter.kt  // persona B COUNT: whistle seconds (model + loud + tonal), refractory, target, DONE, "check the cooker"
  audio/Tonality.kt        // 4 x 1024-point FFT on the 16 kHz second: strongest line / median of the 400-5000 Hz band
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
  ui/AlertScreen.kt        // persona C: last alert big and flashing, history, category switches + TEST, TEACH rows, sensitivity
  ui/HomeScreen.kt         // persona B: FIND tab (arrow, prompt, warmer/colder, walk map, TICKS/HUM/ANY, MARK) and COUNT tab (whistles)
  ui/CommanderScreen.kt     // the one screen every phone shows (sensors fill it from the commander's Board and Fix)
  ui/SensorScreen.kt        // base: this phone's mic block + a sensor's arrow logic; ui/MapView.kt, ui/ArrowView.kt
app/src/main/assets/yamnet.tflite, yamnet_class_map.csv
app/src/test/java/com/hush/LocatorTest.kt   // laptop-only synthetic test of the locator (JUnit 4.13.2)
app/src/test/java/com/hush/KnockBearingTest.kt   // the own arrow: mirror resolved by turning, expiry, junk delays
app/src/test/java/com/hush/CrossingTest.kt       // bearing lines → point: three lines, a mirrored phone, ambiguity, parallel, behind, range
app/src/test/java/com/hush/GpsLayoutTest.kt      // GPS positions: wide triangle placed, table triangle refused, stale or poor fixes named
app/src/test/java/com/hush/SoundAlertsTest.kt    // persona C rules: doorbell, quiet TV, warm-up, alarm 2-of-3, extend vs refire, knock rhythm, priorities
app/src/test/java/com/hush/WhistleCounterTest.kt // 3 whistles -> DONE, double whistle once, frying/water/talk not counted, stale warning, tonality
app/src/test/java/com/hush/WalkLocatorTest.kt    // three marks find the noise, mirrored marks need a third, moving never marks, warmer/colder, reset
app/src/test/java/com/hush/DoaTest.kt            // a 100 Hz hum's two-mic delay in the low band, not in the voice band; a click to the sample
app/src/test/java/com/hush/SoundLibraryTest.kt   // TEACH: 3 loud seconds, match same not others, timeout, file round trip
tools/calibration/knockdir.py               // per phone: knock bursts, two-mic delays, implied angle (calibrating the arrow's sign and spacing)
docs/PLAN-compass.md                        // the compass plan (own arrow → bearings on the map → GPS → silent chirps)
docs/PLAN-personas-bc.md                    // personas B and C: ALERT role (colour flash + haptics), whistle counter, walk-to-triangulate
.github/workflows/build.yml
```

## Build, run, inspect

```bash
export JAVA_HOME=/c/Android/jdk17 ANDROID_HOME=/c/Android/Sdk   # if the shell predates the install
./gradlew assembleDebug -q
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do adb -s $s install -r app/build/outputs/apk/debug/app-debug.apk; done
adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep -i 'KNOCK ARROW\|SHARED\|CROSS\|FIX sent\|GPS layout\|PHANTOM\|RANKING\|BRIEF\|Probe\|Activation\|Beacon\|nothing from'
python tools/calibration/knockdir.py --minutes 10   # every phone on USB: knock bursts, two-mic delays, implied angle (calibrating the arrow)
adb shell am start -n com.hush/.MainActivity --es role COMMANDER    # pick a role without tapping coordinates
adb shell am start -n com.hush/.MainActivity --ez probe true        # commander: ACTIVATE SENSORS
adb shell am start -n com.hush/.MainActivity --ez hush true         # commander: HUSH window (solo allowed)
adb shell am start -n com.hush/.MainActivity --es layout clear      # commander: drop the stored hand layout and pointing so GPS may place the phones
adb shell am start -n com.hush/.MainActivity --es mic1top false     # any role: channel 1 is the BOTTOM mic (use if the own arrow points backwards)
adb shell am start -n com.hush/.MainActivity --ef micspacing 0.14   # any role: distance between the two mics, metres (default 0.155)
adb shell am start -n com.hush/.MainActivity --es role ALERT        # persona C: household sound alerts (no radios)
adb shell am start -n com.hush/.MainActivity --es role HOME         # persona B: FIND a noise / COUNT cooker whistles (no radios)
adb shell am start -n com.hush/.MainActivity --es alerttest DOORBELL   # ALERT/HOME: flash + buzz + notification without a sound (any category name)
adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep 'ALERT\|WHISTLE\|WALK\|HUM DoA\|TEACH\|Household classifier'
adb shell am start -n com.hush/.MainActivity --ez sync true         # any role: SYNC COMPASS (phones lying parallel, tops the same way)
adb shell dumpsys bluetooth_manager | grep -A8 'com.hush (Registered)'   # Bluetooth's view of the port: scan time, results
./gradlew testDebugUnitTest -q          # 24 laptop tests: knock bearing, crossing, GPS layout, locator, chirp
adb -s <serial> exec-out run-as com.hush cat files/debug.wav > debug.wav   # first 300 s of raw stereo audio after a role start, laptop analysis only
```

## Demo flow the code supports

1. Three phones on cloth, within a metre of each other. Airplane mode + Bluetooth + Wi-Fi radio on. Pick COMMANDER
   on one, SENSOR on the others; letters and name suffixes appear. Lay them parallel (tops the same way) and tap
   SYNC COMPASS on any phone; then turn them to clearly different angles (so their left/right mirrors differ).
   No chirps, no placing needed.
2. Someone knocks on the table 0.5–2 m away, one per second. Within 3–4 knocks every phone shows one arrow on the
   knock, "fused from A,B,C · confidence N%"; confidence climbs as the phones agree. Turn a phone: its arrow stays
   on the knock. The knocker walks: the arrows follow within a few knocks.
3. Optional distance: Place the phones on the commander's map (or `--es layout`), Align by pointing; knocks within
   ~1 m of the cluster get a red cross-hair and "~N m where the arrows cross".
4. Sweep: one extra phone with Hush opened once and closed (armed). ACTIVATE SENSORS on the commander: it buzzes,
   shows the red rescue screen and joins as a sensor; phones already in a role buzz twice and say "Rescuer probe heard".
5. HUSH for the 20 s silence window and the "Window ·" brief; EXPORT LOG → `Downloads/hush-….jsonl`.

## What we will not claim

No "dot on the map" under rubble. Not a replacement for seismic kits or search dogs. Never overrides emergency
communications. Radio distances are coarse until Channel Sounding works. If asked to add anything that implies more,
push back.
