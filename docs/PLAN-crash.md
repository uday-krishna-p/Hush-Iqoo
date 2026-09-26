# Plan: crash / fall detection and emergency-contact dialing (27 Sep 2026)

Goal: **a Hush phone that is dropped, thrown or crushed with its owner notices, gives the person 30 s to say
"I'm OK", and if nobody answers it raises the alarm by itself: over the mesh to every other Hush phone, with the
nearest one told "you are closest, call their emergency contact or 112", by SMS and phone call to the person's
own emergency contacts, and by opening the dialer on 112.** It must work with the phone in a pocket and the
screen off, and it must never place a call while someone is just handling the phone.

Team decision 27 Sep: **the call does not have to connect.** The deliverable is that the phone genuinely
tried: the attempt shows in the app's own log, in the crash screen's "what I did" list, in the export and,
when a SIM is present, in the phone's own call history. Nothing is faked: the app never writes invented
entries into the system call log, it only records what really happened (rang 12 s, no SIM, no service).

Written while another session is working on the compass/arrow. Everything below goes into **new files** plus a
handful of tiny touch points in shared files (listed at the end) so the two pieces of work do not collide.

Assumed meaning of "crash" (say so if it should be different): the phone's **owner** falls, is hit or is buried
(rescuer on a rubble pile, or a person in a building that gives way), and secondarily a **vehicle crash** on
the way to the site. Both use one detector with two profiles. Not: the app crashing (that is what `HLog` and
the foreground service already guard).

---

## What was measured today (27 Sep, two phones on USB)

| Fact | Consequence |
|---|---|
| Accelerometer is an ST **LSM6DSVX**, up to 480 Hz, `non-wakeUp` only; gyroscope `vsen_gyro` 480 Hz, also non-wakeup; no "significant motion" sensor listed. | Sensor data only flows while the processor is awake. Detection therefore needs a running foreground service holding a wake lock (the mic service already keeps the phone awake in a role). A phone with no service running cannot detect anything: same rule as the microphone. |
| The app already samples this sensor at 200 Hz (`AccelChannel`, 5000 µs; `SENSOR_DELAY_FASTEST` crashed the service). | The crash detector taps the same stream. 200 Hz is plenty: a fall impact lasts 20–50 ms. |
| The **gyroscope** (turning rate in °/s around each axis) is not used anywhere in Hush yet; the compass reads the system's rotation-vector sensor, which fuses gyro, accelerometer and magnetometer internally. | `AccelChannel` registers the gyroscope too, at the same 200 Hz. A body going down tumbles at roughly 200–500 °/s and ends in a new posture; a phone slipping onto a chair turns less and settles at once. Third vote for the fall profile; a minor cue for vehicles (only rollovers and spins turn a car). |
| Sensor range is not yet logged. The LSM6DSVX supports ±2/4/8/16 g; Android picks one. | `AccelChannel.start()` will log `maxRange` (build A). If it is ±8 g (78 m/s²), a hard impact **saturates**; saturation itself is then treated as "at least 8 g". |
| Both phones: `gsm.sim.state = ABSENT,ABSENT`, telephony hardware present. | **Calls and SMS cannot be tested until one phone gets a SIM.** 112 can be dialled without a SIM (carrier rule in India), but the app cannot dial 112 itself (below). Everything else (detection, countdown, mesh alert, SOS beacon) tests without a SIM. |
| The demo radio setup is airplane mode + Bluetooth + Wi-Fi. | Cellular is off on demo phones, so SMS and calls are impossible then; the alert still reaches the commander over the mesh. The app cannot switch airplane mode off (system-only). The screen must say "no mobile network: told the commander, could not call". |
| Existing pieces: `BeaconActivity` shows over the lock screen and turns the screen on; `Activation` posts a full-screen notification (the incoming-call mechanism); `Haptics` does alarm-class vibration; `AlarmVolume` raises and restores the alarm volume; `Gps.fix` is fresh for 60 s; the Bluetooth tag already has an unused **SOS flag** (`BleRanging.FLAG_SOS = 2`); `MainActivity` takes `--es/--ez` extras from adb; `SessionLog` exports records. | The crash screen, siren, SOS beacon and export all reuse these. Nothing new in the radio path. |

## Android rules that shape it (platform behaviour, Android 10–16)

- **A normal app cannot place an emergency call.** `ACTION_CALL` to 112/911/100 is refused for everyone but
  the default phone app. Only `ACTION_DIAL` works: the dialer opens with 112 typed in and the person taps the
  green button once. Becoming the default phone app (an `InCallService`) is a week of work and out of scope.
  So: **contacts are called automatically; 112 is one tap away.** Say this honestly on the screen.
- **Calling a contact automatically** is allowed with the `CALL_PHONE` permission (runtime, asked at first
  launch). `SEND_SMS` sends the text with the position; `READ_PHONE_STATE` lets us see whether the call was
  answered (call state idle → off-hook → idle) so we can move to the next contact. Speakerphone on so the
  person can talk without holding the phone: `AudioManager.setCommunicationDevice` (API 31+).
- **A full-screen notification is the only legitimate way to put a screen up from the background** (an app in
  the background may not start an activity directly since Android 10). `Activation` already does exactly this
  for the probe; the crash screen uses the same call with its own channel ("crash").
- **Foreground service type.** In a role the mic service (`microphone` type) is already running and keeps the
  phone awake. A "guard" mode without a role (build C) needs its own service type: `health` (allowed with
  `ACTIVITY_RECOGNITION`, which is declared) and a partial wake lock (`WAKE_LOCK` permission). Battery
  exemption is already requested at first launch.
- **Play Store** would refuse `CALL_PHONE`/`SEND_SMS` for an app like this; sideloaded builds are fine. Not a
  concern for the hackathon; note it in "what we will not claim".

---

## Build order (each is one build, installed on all phones, tested, committed, pushed)

| Build | What the phone gains | Needs | Size |
|---|---|---|---|
| A | Fall/impact detector on the existing sensor stream; 30 s countdown screen over the lock screen with siren + buzz + "I'M OK"; alert to the commander over the mesh; SOS flag in the Bluetooth tag; **dry-run** of SMS/calls (logged, nothing sent); accelerometer + gyroscope trace file for tuning | nothing | 1 build |
| A2 | **Every other Hush phone gets a red "CRASH" alarm** over its lock screen; the nearest one is told it is nearest and offered one-tap "CALL <their contact>" and "CALL 112"; "I'M GOING" tells the commander and the fallen phone who is coming | A | 1 build |
| B | Emergency contacts screen (up to 3, picked from the phone book or typed); real SMS with position; real call attempts one contact after another on speaker, each attempt and its outcome recorded; dialer opened on 112 when nobody answers | A, A2; a SIM only to see a call connect | 1 build |
| C | GUARD mode: detection without picking a role (a rescuer walking the site, a worker at home), own foreground service + wake lock; battery number | A | 1 build |
| D | Vehicle profile (GPS speed before, sensor saturation, loud bang in the audio) and thresholds re-tuned from recorded traces | A, traces from real drops/falls | 1 build |

---

## Build A · detect, count down, tell the commander

### The detector (`CrashDetector.kt`, pure maths, laptop-testable)

Input: the raw acceleration (x, y, z, time) at 200 Hz that `AccelChannel` already receives, **before** its
high-pass filter (the crash detector needs gravity: free fall is "gravity disappears"), plus the gyroscope
(x, y, z turning rate, time) at 200 Hz, registered in the same class on the same thread. One extra hook in
`AccelChannel`: an optional `rawSink` called from `onSensorChanged` for both sensors (~10 lines).

Five features, acceleration in m/s² (g = 9.81), turning in °/s:

1. **Free fall.** |a| < 3 m/s² for ≥ 100 ms (a 0.5 m drop is 320 ms; a person going down is 100–250 ms
   because the legs and arms brake it). Sets `fellAt`.
2. **Impact.** A peak ≥ 30 m/s² (≈ 3 g) within 800 ms after the free fall; or, with no free fall, a peak
   ≥ 60 m/s² (≈ 6 g; being struck or thrown) or the sensor pinned at its `maxRange` for ≥ 10 ms (saturated).
3. **Stillness afterwards.** For 5 s after the impact the high-passed RMS that `AccelChannel` already computes
   stays < `MOVING_RMS` (0.25 m/s²) for at least 3 of those seconds. A person who gets straight up is not a
   casualty; the countdown does not start (logged as `Crash: impact but moving again, ignored`).
4. **Carried before.** The phone was being handled or carried in the 10 s before the free fall (moving RMS >
   0.25 in ≥ 3 of the last 10 seconds). **This is the gate that keeps sensor phones lying on the floor quiet:**
   a slab crashing down beside a resting sensor phone is a jolt, not a fall of its owner. A resting phone
   only triggers on the saturation rule (it was hit hard enough to pin the sensor: buried or crushed).
5. **Tumble and posture change (gyroscope + gravity).** Peak turning rate (magnitude of the three gyro axes)
   in the window from 1 s before the free fall to the impact, and the angle between the gravity direction
   averaged over the 2 s before the free fall and over seconds 2–5 after the impact. A fall of the owner:
   peak turn ≥ 150 °/s **or** posture change ≥ 60°. Neither alone rejects a candidate in build A (a phone
   can fall out of a pocket with a spin, and a person can go down straight); together they are the
   tie-breaker for a candidate that only just passes rule 2 (peak 30–40 m/s²), and both are printed in every
   candidate line so their weight is set from the traces. Vehicle profile (build D): a peak turn ≥ 300 °/s
   sustained ≥ 0.5 s marks a rollover or a spin.

Output: `Candidate(kind = FALL | IMPACT, peakMs2, freeFallMs, stillSeconds, carriedBefore, saturated,
peakTurnDps, postureChangeDeg, tMs)`. Every candidate is logged whether or not it passes (`Crash: candidate
fall=320ms peak=41.2 still=4/5 carried=true turn=310°/s posture=74° → DETECTED` / `→ rejected: not carried`),
so the log tells us how to tune.

**What will fool it and what we do about it.** A phone dropped onto a cushion from a pocket while walking
passes all four rules: free fall, impact, still, carried. That is the honest limit of an accelerometer; Apple
and Google accept the same false positives and rely on the countdown. So do we: the countdown with a big
"I'M OK" button is the real guard, and the siren makes sure the owner notices. Thresholds are a first guess
from the literature (fall detectors use 2–3 g impact after < 0.5 g free fall); **build A also writes the raw
accelerometer and gyroscope to a private file** (`files/motion.csv`, first 300 s, 200 Hz × 7 numbers ≈ 5 MB,
pulled over USB like `debug.wav`) so drops, sitting down, jumping and stairs get replayed on the laptop
(`tools/calibration/crash.py`) and the numbers get set from data, the way the tap detector was.

### The state machine (`CrashGuard.kt`)

```
IDLE ──candidate passes──▶ COUNTDOWN (30 s) ──"I'M OK" / any cancel──▶ IDLE (log "cancelled by user at 12 s")
                               │
                               └─ expires or "CALL NOW" ──▶ ESCALATED ──"I'M OK"──▶ IDLE (cancel repeats, tell commander)
```

- **COUNTDOWN.** One `Notification` with a full-screen intent on a new channel "crash" (max importance,
  `CATEGORY_ALARM`, public on the lock screen) opens `CrashActivity` over the lock screen with the screen on
  (copy `BeaconActivity`'s window flags). Siren: a 1 s rising two-tone through `Ping`/`Player` on the alarm
  stream at full alarm volume via `AlarmVolume` (restored afterwards), repeated; vibration: `Haptics` pattern
  long-long-long, repeated, alarm usage. The screen: red, "FALL DETECTED", a huge countdown number, a huge
  green **I'M OK** button, a smaller **CALL NOW** button, the contact names that will be called, and a line
  saying what is possible right now ("will text and call Priya, then open 112" / "no mobile network: the
  commander will be told, calls are not possible"). A 30 s debounce so one fall cannot open two screens.
- **ESCALATED**, in this order, each step logged with its outcome:
  1. `Alert` message up the mesh (below), repeated every 10 s with the fresh position until cancelled. The
     commander relays it down to every phone (build A2).
  2. Bluetooth tag re-advertised with `FLAG_SOS` set (the commander's tag scan already reads flags;
     "Discovered phones" shows "SOS"). Works with no mesh link at all, the way the probe does.
  3. SMS to every contact (build B; build A logs `Crash: DRY RUN would SMS +91… "Hush: possible fall of
     I2501-6a46 at 12:03:41, no response for 30 s. Position https://maps.google.com/?q=17.44,78.35 (±12 m).
     Battery 61 %."`).
  4. Call contact 1 on speaker; if the call ends in under 20 s of talk, contact 2, then 3 (build B).
  5. Dialer opened on 112 (`ACTION_DIAL`) with the siren still running (build B).
  6. Screen stays up for 10 min with I'M OK; SMS repeated every 5 min with the new position (build B).
- **The commander side** (`Engine`, `CommanderScreen`): a red row at the top, "⚠ Sensor B (6a46): FALL, no
  response since 12:04:11 · 4 m/s² still · GPS ±12 m · battery 61 %", the commander phone buzzes once
  (`Haptics`), the map dot of that sensor pulses red, and the export gets a `crash` record. Cleared when the
  sensor sends `state = CANCELLED` or after 30 min.

### Messages (one new JSON line, up the mesh)

```kotlin
data class Alert(                 // "rep":"alert"
    val name: String, val letter: String?,
    val kind: String,             // FALL | IMPACT | CRASH | MANUAL
    val state: String,            // COUNTDOWN | ESCALATED | CANCELLED
    val tMs: Long, val peak: Float, val stillS: Int, val battery: Int,
    val lat: Double?, val lon: Double?, val gpsAcc: Float?,
    val cellular: Boolean,        // whether the phone could also text/call
    val contacts: List<Pair<String, String>> = emptyList(),   // name + number, only once ESCALATED (build A2)
    val nearest: String? = null,  // set by the commander when relaying down: the letter it thinks is closest
    val distances: Map<String, Float> = emptyMap()             // letter → metres (or "~" RSSI guess), relay only
)
data class Response(val name: String, val letter: String?, val forName: String, val action: String)   // "rep":"resp"; action = GOING | CALLED_CONTACT | CALLED_112
```

`Messages.parse` gets one more `rep == "alert"` line. Old builds ignore it (they log "unparseable" and carry
on, verified behaviour of `parse`).

### Laptop hooks (`MainActivity` extras, kept in the same style as `--es role`)

- `--ez crashtest true` fires a synthetic candidate: opens the countdown for real (siren, buzz, screen) and
  runs the escalation in dry-run. First test of the whole path without dropping anything.
- `--es crashmode dryrun|live` (preference). **Default dryrun until build B is tested with a SIM.**
- `--ez motionrec true` writes the 300 s accelerometer + gyroscope trace now (it also runs on every service
  start, like the debug WAV).

### Tests for build A (three phones, no SIM needed)

1. `--ez crashtest true` on a sensor phone: red screen over the lock screen within 1 s, siren at full alarm
   volume, buzz; countdown runs; tap I'M OK: everything stops, log `Crash: cancelled by user at N s`.
2. Same, let it expire: commander's screen shows the red row and buzzes; log on the sensor: `Crash: ESCALATED`,
   `DRY RUN would SMS…`, `DRY RUN would call…`; tag advertised with `flags=2`; export contains `crash`.
3. Phone carried in a hand for 10 s, then dropped from 1 m onto a cushion or a folded jacket (never the floor):
   countdown appears within 6 s (5 s stillness check + screen). Log the candidate line, including its
   `turn=` and `posture=` values: these are the "phone dropped" reference numbers.
3b. Phone in a trouser pocket, team member lies down on a mattress as fast as is safe (a staged fall; nobody
   actually falls): candidate line with its `turn=`/`posture=` values, the "person went down" reference.
   The difference between 3 and 3b is what rule 5 has to work with.
4. Phone on cloth on the table, knock hard beside it, slap the table: nothing (`rejected: not carried`).
5. Phone in a pocket: walk, sit down hard, climb stairs, jump once: note every candidate line. Expect none to
   pass; if one does, that trace goes into the tuning set.
6. Airplane mode on: the screen says calls are not possible; the mesh alert still arrives.

---

## Build A2 · every nearby Hush phone gets the alarm, the nearest one is asked to call

**Why this is the right design for a rubble site.** The fallen phone is the one most likely to have no
network, a dead battery or a crushed screen. The phones around it belong to people who are physically close
and may have signal. So the alarm should not stop at the commander's screen: it should reach every Hush phone
within mesh range, and the closest person should be told so and handed the numbers.

**Flow.**
1. The fallen phone escalates (build A): `Alert(state = ESCALATED, contacts = its emergency contacts)` goes up.
   The contacts cross the mesh only at this moment, only as name + number, and only to phones in this mesh.
2. The commander relays it down to every sensor as the same `Alert` with `nearest` and `distances` filled in
   (see "who is nearest"), and shows its own red row as in build A.
3. Every phone that receives it (commander included) shows a full-screen red notification over the lock
   screen, siren at alarm volume for 10 s, the rescue buzz: **"CRASH · Ravi's phone (6a46), Sensor C · no
   response for 30 s · 4 m from you"**. Buttons: **CALL <contact name>** (one per contact, dials with
   `ACTION_CALL`; on a phone without a SIM this fails and says so), **CALL 112** (`ACTION_DIAL`, one more tap),
   **I'M GOING** (sends `Response(GOING)`), and a small "dismiss". The nearest phone's screen says in big
   letters **"YOU ARE THE CLOSEST"**; the others say "Sensor C is closer (4 m), you are 11 m away".
4. `Response` goes up; the commander shows "Ravi (C) is going · Priya (D) called +91…", and relays it down so
   the fallen phone's screen shows "Ravi is coming" (if it can still show anything) and the others stop
   their siren. A phone that pressed CALL logs the attempt exactly like the fallen phone would.
5. Everything is cleared when the fallen phone sends `state = CANCELLED` or the commander clears it (30 min).

**Who is nearest, honestly.** Best evidence first: (1) chirp-ranging distances between the fallen phone and
the others, if a ranging round has placed the map (±5 cm); (2) map positions from GPS outdoors (±5–20 m);
(3) the fallen phone's SOS tag as seen by each phone's Bluetooth: the strongest signal is "probably nearest",
labelled "~" and never a number in metres (RSSI read 6–14 m at 0.5 m on these phones). For (3) every phone
needs to scan for tags, which today only the commander does; the sensor phones add a low-power tag scan
while an alert is active (`BleRanging.startTagScan` already exists) and report the RSSI in their next
event. If nothing is known, no phone is called nearest and everyone sees "distance unknown: look around".
The label always says which of the three it used.

**Phones outside the mesh.** A Hush phone that is armed but not in a role sees nothing over the mesh. Its
passive port only listens for the commander's probe, so the commander's ACTIVATE SENSORS button is the
answer today: it wakes them into the mesh, and the alert reaches them on the next relay. A dedicated
"crash probe" (the SOS tag itself waking passive phones) is a later build.

**Code.** `Alert` gains `contacts`, `nearest`, `distances`; new `Response`; `Engine`: relay down, nearest
computation, response bookkeeping (~40 lines); `CrashAlarmActivity.kt` + `res/layout/activity_crash_alarm.xml`
(the red screen for the *other* phones, distinct from `CrashActivity`, the fallen phone's countdown); the
notification goes through the same full-screen-intent helper as `Activation`, new channel "crash-nearby".
Every phone logs `CRASH NEARBY: from 6a46 (C), nearest=C by ranging 4.1 m, me 11 m` and each button press.

**Tests (three phones, no SIM).** `--ez crashtest true` on sensor C, let it expire: within 3 s every other
phone shows the red screen over its lock screen with the siren; after a ranging round the screen names the
nearest phone with a distance; without one it says "~" or "distance unknown". Press CALL <contact> on the
nearest phone: the phone app opens, fails for lack of a SIM, the log records the attempt. Press I'M GOING:
the commander's row shows it, the other phones' sirens stop, sensor C's screen shows "… is coming". I'M OK
on C clears every phone.

---

## Build B · contacts, SMS, calls

- **`EmergencyContacts.kt` + a small screen** reached from a button on the armed/role screen ("EMERGENCY
  CONTACTS · 2 set"; orange when none). Up to 3 entries, name + number, order matters. **PICK** opens the phone
  book (`ACTION_PICK` with the phone-number content type: the system picker hands back one number, no
  contacts permission needed) or the number is typed. Stored in the existing "hush" preferences. **TEST** runs
  the escalation in dry-run and shows what would be sent; **TEST CALL** dials contact 1 for real after a
  confirmation dialog. Laptop hook: `--es contacts "Priya:+919…;Ravi:+919…"`.
- **`EmergencyDialer.kt`.** SMS via `SmsManager` (per-SIM manager on dual-SIM phones), text as above, sent to
  all contacts first because a text gets through where a call does not. Then `ACTION_CALL` to contact 1 with
  `CALL_PHONE`; speaker on; a `TelephonyCallback` (API 31+) watches the call: off-hook for ≥ 20 s counts as
  answered (voicemail also counts, honest limit); otherwise the next contact 5 s after the call ends. After
  the last contact, `ACTION_DIAL` with 112.
- **The attempt is the deliverable (team decision).** Every step is *attempted for real* whether or not a SIM
  is present, and its outcome is recorded in three places: `HLog` (`Crash: CALL +91… → no SIM, phone app
  said "mobile network not available"` / `→ rang 14 s, not answered` / `→ answered, 63 s`), the crash screen's
  "what this phone did" list (also sent to the commander in the next `Alert`), and the export's `crash`
  record. With a SIM the phone's own call history shows the outgoing attempt too, because the system writes
  it; without a SIM the system may not, so **our own list is the evidence**, not the call history. The app
  never inserts entries into the system call log. SMS without a SIM fails at once and is recorded the same
  way; with a SIM the delivery report (`SENT`/`DELIVERED` broadcasts) is recorded.
- **Permissions to add** to the permissions file (AndroidManifest.xml) and to the first-launch request in
  `MainActivity`: `CALL_PHONE`, `SEND_SMS`, `READ_PHONE_STATE`. All three are runtime permissions; a refusal
  degrades to "dialer on 112 only" and the screen says so.
- **Never test with a stranger's or a client's number.** Only team members who have agreed, and dry-run first.
  A sent SMS or a placed call cannot be undone.

Tests. Without a SIM (what we have): `crashtest` live → the phone app opens for contact 1 and fails, the
crash screen's list reads "SMS Priya: no SIM · call Priya: no SIM · call Ravi: no SIM · dialer on 112 opened",
the same list is on the commander's row and in the export. With a SIM (when one is available, contacts = two
team members' numbers): both receive the SMS with a working map link within 15 s; contact 1's phone rings on
speaker; decline it → contact 2 rings; decline → the dialer opens on 112 (do not press call); the phone's own
call history shows the two attempts. I'M OK at any point stops the sequence and sends a "cancelled" SMS.
Repeat with the phone in airplane mode: mesh alert only, reasons logged.

---

## Build C · GUARD mode (no role)

A third button on the role screen: **GUARD**. Starts `GuardService` (foreground, type `health`, partial wake
lock, notification "Hush is watching for a fall"), which runs only `AccelChannel` + `CrashDetector` +
`Gps`, no microphone, no Nearby. Same countdown and escalation; the mesh alert is replaced by the SOS tag
(a commander nearby still sees it) and the phone joins the mesh as a sensor only if the person taps the
existing beacon path. Stops from the notification or the screen. Measure: charge to 100 %, GUARD on, phone in
a pocket for a working day; expect 2–4 %/h (wake lock + 200 Hz sensor); if that is too much, drop the guard's
rate to 100 Hz (`AccelChannel` gets a rate parameter) and re-measure. Numbers go into CLAUDE.md.

## Build D · vehicle profile and tuning

Vehicle profile adds: GPS speed ≥ 25 km/h at some point in the last 10 s and ≤ 5 km/h afterwards; sensor
saturation or a peak ≥ 60 m/s²; a loud broadband bang in the same 200 ms from the audio pipeline when it is
running (mic RMS ≥ 10× the floor); a sustained gyroscope turn ≥ 300 °/s for a rollover or a spin. This is the
same recipe as Pixel's car crash detection (accelerometer + microphone + speed from location + activity
recognition, then a 60 s countdown), minus the barometer Apple adds for the airbag pressure jump (absent on
the I2501) and minus the direct call to emergency services (system apps only). Thresholds for both profiles come from the traces recorded in A and the
laptop replay script. Not before A has produced real drop and pocket traces.

---

## Shared-file touch points (keep them tiny so the other session's work is not disturbed)

| File | Change | Lines |
|---|---|---|
| `audio/AccelChannel.kt` | register the gyroscope beside the accelerometer (same thread, 200 Hz); `var rawSink` called with (sensor, tMs, x, y, z); log both sensors' `maxRange` at start | ~15 |
| `Engine.kt` | create `CrashGuard` in `start()`, feed it the per-second `AccelChannel.Result` and GPS, dispose in `stop()`; handle incoming `Alert` on the commander, relay it down with `nearest`, handle `Response`; `crashAlerts()` for the screen | ~65 |
| `model/Events.kt` | `Alert` and `Response` classes + two `parse` lines | ~45 |
| `AndroidManifest.xml` | `CrashActivity` and `CrashAlarmActivity` (same attributes as `BeaconActivity`), permissions `CALL_PHONE`, `SEND_SMS`, `READ_PHONE_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE_HEALTH`; `GuardService` in C | ~15 |
| `MainActivity.kt` | three extras, the contacts button, the three permissions in the first-launch list | ~15 |
| `ui/CommanderScreen.kt` | red alert row at the top | ~15 |
| `res/values/strings.xml` | crash strings | ~20 |
| `log/SessionLog.kt` | `crash` record | ~5 |

New files: `CrashDetector.kt`, `CrashGuard.kt`, `CrashActivity.kt` (+ `res/layout/activity_crash.xml`),
`CrashAlarmActivity.kt` (+ `res/layout/activity_crash_alarm.xml`, build A2),
`EmergencyContacts.kt`, `EmergencyDialer.kt`, `ContactsActivity.kt` (+ layout), `GuardService.kt` (C),
`app/src/test/java/com/hush/CrashDetectorTest.kt` (synthetic traces: 1 m drop passes, table slap without
carrying is rejected, sitting down hard is rejected, saturation on a resting phone passes, moving again
within 5 s is rejected, a borderline 35 m/s² impact passes with a 300 °/s tumble and is rejected without one), `tools/calibration/crash.py`.

Order of work when the other session is mid-change: write all new files first (they compile on their own),
then the `Events.kt` and manifest lines, then the `Engine.kt` wiring **after pulling the other session's
commit** so there is one small, obvious edit in `start()`/`stop()` rather than a merge.

## Decisions for the team (defaults in bold if nobody answers)

1. Countdown length: **30 s** (Apple uses 20 s for crashes, 30 s for falls on older users).
2. GUARD on by default at first launch, or only when picked? **Only when picked** until build C's battery
   number is known.
3. Which phone gets a SIM for build B, and which two team members' numbers go in as test contacts.
4. Emergency number: **112** (India, works without a SIM), configurable in the contacts screen.
5. Should the commander be able to cancel a sensor's alarm remotely (it can see the person)? **Not in A/B.**
6. Do the fallen phone's emergency contacts cross the mesh to the other phones? **Yes, at escalation only**,
   name + number, never stored on the receiving phones beyond the alert. Say no and the nearby phones get
   only CALL 112 and the commander's row shows the numbers instead.
7. Siren on the nearby phones: 10 s then silent with the screen up (**default**), or until dismissed?

## What we will not claim

Not a medical or certified fall detector. It cannot dial 112 by itself (Android forbids it; one tap remains).
It does not detect anything while the app is not running or the phone is in airplane mode without a commander
nearby. A phone that falls out of a pocket looks the same as a person falling; the countdown is the answer.
Calls and texts need a SIM and a network; under rubble there is often neither, which is why the mesh alert,
the nearby phones' alarm and the SOS beacon come first in the order. "Nearest" is exact only after a chirp
round; from Bluetooth signal strength it is a guess and is labelled as one. A call attempt that fails is
recorded as a failed attempt, never presented as a call that went through.
