# Plan: personas B and C, one phone at home (27 Sep 2026)

**Progress (27 Sep 04:00):** steps 1–5 are built on branch `worktree-personas-bc` (5 commits, 53 unit tests pass, APK
builds), not yet installed on the phones and not yet tested on real sounds. Step 0 (measure) and step 6 (numbers) remain,
and step 0 must come before any threshold is trusted. Differences from the text below: TEACH lives on the ALERT screen
only (not on COUNT yet); the FIND map reuses `MapView` unchanged (marks 1 2 3, walker A); knock alerts are never
"extended" (each burst buzzes); a TAUGHT alert outranks DOORBELL and carries the taught sound's own colour.

From the PRD (v2.2, `docs/PRD-v2.2.md`), two lines each:

- **Persona B, everyday household user, single phone.** Goal: locate a mysterious noise in the house, track kitchen
  timers. Action: *Single-Mobile Walk-to-Triangulate* and *Whistle Counter*.
- **Persona C, hearing-impaired / elderly user.** Goal: know when doorbells, knocks or distress calls happen nearby.
  Action: instant screen colour flashes and distinct haptic vibrations.

**How this plan reads those lines (confirm or correct before step 1):**

1. *Walk-to-Triangulate* = one phone, no other phones, no chirps. The phone's own two-mic arrow (already built,
   `KnockBearing.kt`) says which way a noise is from where you stand. Walk to a second and third spot and the arrow
   lines from those spots cross at the noise: a point and a distance. Same maths the commander already uses to cross
   its sensors' arrows (`Crossing.kt`), with the walker's own positions (step counting + compass, `DeadReckoning.kt`)
   instead of sensor dots.
2. *Whistle Counter* + *kitchen timers* = **pressure-cooker whistles** ("cook for 3 whistles"): the phone counts them
   and alarms at the number you set, and it also tells you when the microwave / oven timer beeps. If the PRD meant
   something else by whistle (a referee's whistle, the user whistling to count laps) the detector is the same and only
   the screen text changes.
3. *Colour flashes and distinct vibrations* = the phone lies in the room and listens all day; a doorbell, a knock on the
   door, a smoke/fire alarm, a scream or a crying baby turns the screen a category colour with one big word, vibrates
   in a pattern unique to that category, and leaves a notification until dismissed. Over the lock screen too (same
   mechanism as the rescue beacon screen). Nothing leaves the phone.

**Why C before B.** Persona C is a safety feature, needs no geometry, and reuses the most (classifier, tap detector,
alarm-class haptics, the lock-screen alert activity, the notification channel). Its category detector and its
flash-and-buzz alarm are exactly what the whistle counter needs at "3 of 3", so B's counter comes second and B's
walk-to-triangulate, which depends on indoor step counting and compass (the riskiest part), comes last.

Build order. Each step is one build, installed on all three phones, tested, committed, pushed.

| Step | What the app gains | Needs | Size |
|---|---|---|---|
| 0 | Facts we do not have: what YAMNet says about a cooker whistle, a doorbell, a microwave beep, a drip, a knock at a door, a smoke-alarm test; battery per hour of listening alone; does the mic survive an hour with the screen off | current build, no code change | 1 hour |
| 1 | **ALERT role (persona C):** categories → colour flash + distinct vibration + notification, over the lock screen, history list; the shared "one phone at home" plumbing | step 0 (a) | 1–2 builds |
| 2 | **HOME role, COUNT tab (persona B):** pressure-cooker whistle counter with a target and an alarm; microwave/oven timer beeps | steps 0 (a), 1 | 1 build |
| 3 | **HOME role, FIND tab (persona B):** walk-to-triangulate for ticking, dripping, beeping, knocking noises | step 1 | 1 build |
| 4 | FIND for continuous noises (fridge, fan, pump hum) | step 3 | 1 build |
| 5 | TEACH a sound: your own doorbell, your microwave, the water-heater alarm | step 1 | 1 build |
| 6 | Numbers: battery per hour, false alerts per hour with a TV on, detection rate per category, resume after reboot | steps 1–5 | measure |

---

## Step 0 · Measure (current build, no code change)

Any role records and writes one `window …` line per second to the log with YAMNet's top five classes and scores
after the `|`. Start one phone as COMMANDER alone (`adb shell am start -n com.hush/.MainActivity --es role COMMANDER`,
unlock it first), then make each sound for 10 s at 2 m and note what the model calls it:

```bash
adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep 'window id' | sed 's/.*| //'
```

**(a) Sounds.** A pressure-cooker whistle (real, or a recording played from another phone with
`--es play cooker.wav --ef level 0.6`; record one at home into `files/debug.wav` if nobody has a clip), a doorbell
(a recording is fine; Indian doorbells are electronic melodies as often as ding-dongs), a microwave or oven timer
beep, a dripping tap, three knocks on a wooden door, a smoke-alarm test button, a shout, a phone ringing, and 5 min
of TV and of normal talking. Expected from the class list: `Whistle`, `Steam whistle`, `Hiss`, `Steam`, `Boiling`
for the cooker; `Doorbell`, `Ding-dong`, `Ding`, `Chime`, `Bell`, `Buzzer` for doorbells; `Beep, bleep`,
`Microwave oven`, `Alarm clock` for timers; `Knock`, `Door`, `Wood`, `Thump, thud` for knocks; `Smoke detector,
smoke alarm`, `Fire alarm`, `Alarm`, `Siren` for alarms; `Screaming`, `Shout`, `Yell`, `Crying, sobbing`, `Baby cry,
infant cry` for distress. The scores seen here set the thresholds in step 1 and decide whether TEACH (step 5) is
needed on day one for doorbells.

**(b) Battery.** One phone, COMMANDER alone, screen off, one hour: `adb shell dumpsys battery | grep level` before
and after. The current build also runs Nearby and Bluetooth, so this is an upper bound; step 6 measures the real
household roles.

**(c) Screen-off survival.** From the same hour: `grep -c 'window id'` should be about 3600 and the timestamps
should have no gaps. The rescue tests only ever ran the microphone service for about an hour with the screen off;
OriginOS may kill it later (the battery-optimisation exemption is already requested at first launch).

---

## Step 1 · ALERT role (persona C)

**Idea.** The phone sits in the living room or bedroom. Every second the existing pipeline already gives us the
loudness above the room's noise floor, YAMNet's 521 class scores and every sharp onset with its loudness. A small new
object, `SoundAlerts`, folds those into a handful of categories, each with a colour, a word and a vibration pattern,
with per-category thresholds, a loudness gate and a debounce. When a category fires: the screen flashes its colour and
shows the word; the phone vibrates the pattern (alarm-class, full strength, like the Hush buzz); a notification stays
until dismissed; if the screen is off or locked, a full-screen-intent notification opens `AlertActivity` over the lock
screen (the same mechanism as the rescue beacon screen, already working on these phones). Every alert goes into a
history list, so somebody who was asleep sees "Doorbell 14:32".

**Categories (start values; step 0 (a) corrects them).** `score` = the sum of the listed YAMNet classes for that
second, exactly how the VOICE / MACHINE buckets are built today. Every category also needs loudness ≥ 2× the noise
floor (so a TV murmuring in the next room does not fire), except ALARM at 1.5× (a distant smoke alarm matters).

| Category | Evidence | Colour | Vibration | Word |
|---|---|---|---|---|
| ALARM | Smoke detector, Fire alarm, Alarm, Alarm clock, Siren, Civil defense siren, Car alarm, Buzzer: score ≥ 0.35 in 2 of the last 3 s | red | 1 s on / 0.3 s off, repeated until dismissed (max 60 s) | SMOKE ALARM / ALARM / SIREN (by top class) |
| DOORBELL | Doorbell, Ding-dong, Ding, Chime, Bell, Buzzer: score ≥ 0.3 in one second | blue | short-short (150 / 100 / 150 ms), twice | DOORBELL |
| KNOCK | 2–6 tap-detector onsets within 3 s, each ≥ 6× the background, and (Knock + Door + Wood + Thump ≥ 0.15 in that second OR every onset ≥ 10×) | yellow | **replays the knock rhythm**: three knocks 400 ms apart = three buzzes 400 ms apart | KNOCK ×3 |
| DISTRESS | Screaming, Shout, Yell, Bellow, Children shouting, Crying/sobbing, Baby cry, Whimper: score ≥ 0.3 in 2 of 3 s | purple | long-long-long (600 / 200 × 3) | SHOUTING / BABY CRYING (by top class) |
| TIMER | Beep bleep, Microwave oven, Alarm clock ≥ 0.3 in 2 of 3 s (and not ALARM) | green | short ×4 | TIMER BEEPING |
| CRASH | Shatter, Smash/crash, Glass, Explosion ≥ 0.3 in one second | orange | one 800 ms buzz | CRASH |
| PHONE | Telephone bell ringing, Ringtone, Telephone ≥ 0.3 in 2 of 3 s; skipped while this phone itself rings (`AudioManager.mode`) | teal | short-long | PHONE RINGING |
| Off by default | DOG (Bark, Dog), WATER (Drip, Water tap, Sink, Boiling), LOUD SPEECH (Speech ≥ 0.5 and loudness ≥ 8× floor for 3 s: someone calling you, not the TV) | grey | short | … |

A door knock is not rescue tapping: rescue asks for a steady rhythm of ≥ 3 onsets over 8 s; a door knock is 2–4
knocks and then silence. KNOCK therefore has its own 3 s rule and does not touch `RhythmTracker` or `fuseLabel`.

**Gates and debounce.** Nothing for the first 5 s after start, nothing while our own beeps play, nothing while the
phone is being handled (`moving`) except ALARM. One alert per category per 20 s; a category that keeps sounding
extends the alert instead of posting a new one. Highest category wins the screen when two fire together (ALARM >
DISTRESS > CRASH > DOORBELL > KNOCK > TIMER > PHONE). Log: `ALERT DOORBELL top=Doorbell 0.62 rms=… floor=… x4.1` and
`ALERT suppressed DOORBELL: debounce 12 s left` so thresholds can be tuned from the log; the `window` line already
carries the top five every second.

**Flash.** On screen: the ALERT screen background alternates the category colour and black at 2 Hz for 3 s, then holds
the colour with the word in the largest white type that fits, the time, and "tap to dismiss". Never faster than 3 Hz
(photosensitive users). Screen off or locked: a high-importance notification on a new channel "alerts" (separate from
"rescue" so each can be tuned in Settings) with a full-screen intent that opens `AlertActivity` (setShowWhenLocked,
setTurnScreenOn, same as `BeaconActivity`), same colours and word, dismiss button. Torch strobe is not in this step.

**Vibration.** `Haptics.vibrate(context, pattern, "Alert DOORBELL")`: already alarm-class and full amplitude. The
phone's own cap on vibration strength stays the limit, as with the Hush buzz; Settings → Sound & vibration is the lever.

**Screen (`ui/AlertScreen.kt`, `res/layout/screen_alert.xml`).** Big status ("Listening · 2 h 14 min · 3 alerts
today"), the history list (time, word, score; last 100 kept in `files/alerts.jsonl`, survives restarts), one toggle
per category, a sensitivity switch (Normal / High = thresholds × 0.7), and a TEST row: tap a category to feel its
vibration and see its colour, so the person learns the patterns. Large type, high contrast, few buttons.

**Shared plumbing for both personas (done here, used by steps 2 and 3).**
- `Engine.ROLE_ALERT` and `Engine.ROLE_HOME`. In `Engine.start`, these roles skip everything that talks to other
  phones or costs battery for nothing: Nearby, the Bluetooth tag scan / ranging, GPS, the chirp warm-up, the debug
  WAV. They keep the microphone capture, the classifier, the tap detector, the accelerometer (for the `moving` gate)
  and the compass + step counter (step 3 needs them; HOME only).
- In `Engine.onWindow`, the `when (role)` at the end gains `ROLE_ALERT -> alerts.onWindow(w)` and
  `ROLE_HOME -> home.onWindow(w, …)`. `Engine.stop()` resets both. The rescue roles are not touched.
- Battery: in these two roles the classifier runs only on seconds worth classifying (loudness ≥ 1.5× the floor or
  any onset), not every second. Rescue roles keep classifying every second (the voice score feeds the ranking).
- First screen (`activity_main.xml`): under the two rescue buttons a divider "At home" with **HOME** (find a noise,
  count whistles) and **ALERTS** (for hearing). Laptop hooks: `--es role ALERT|HOME`, and `--es alerttest DOORBELL`
  fires the flash, buzz and notification without a sound (checks the lock-screen path from the laptop).
- Foreground notification text: "Listening for household sounds" instead of "Listening as SENSOR".
- The passive rescue port stays armed in these roles (it costs nothing); a rescuer's probe still wakes the phone.

**Files.** `audio/SoundAlerts.kt` (categories, evidence, debounce; pure logic over one `Window`, unit-tested by
`SoundAlertsTest.kt` with synthetic per-second scores), `Alerting.kt` (notification + full-screen intent + haptic,
modelled on `Activation.kt`), `AlertActivity.kt` (lock-screen colour screen, modelled on `BeaconActivity.kt`),
`ui/AlertScreen.kt`, `screen_alert.xml`, strings.

**Test.** Phone on the table, ALERTS, screen off. Play a doorbell from the laptop at room level: within 2 s the screen
lights blue with DOORBELL, two short double buzzes, a notification that stays until tapped. Knock three times on the
door: yellow KNOCK ×3 and three buzzes in the knock's rhythm. Smoke-alarm test button or a recording: red, buzzing until
dismissed. Talk beside it for 5 min: nothing. TV at normal volume for 30 min: count the `ALERT` lines (target ≤ 1
false alert per hour; tune thresholds from the top-five scores in the log). `--es alerttest ALARM` with the phone
locked: the red screen appears over the lock screen.

---

## Step 2 · Whistle counter (persona B, HOME → COUNT)

**Idea.** A pressure-cooker whistle is 1–4 s of loud, tonal steam: YAMNet calls it Whistle / Steam whistle / Whistling
/ Hiss / Steam / Boiling (step 0 (a) confirms). Count the whistles, alarm at the target. The kitchen is noisy (frying,
water, talking), so three things must agree for one second: the whistle score ≥ 0.3, loudness ≥ 4× the floor, and the
sound is **tonal**: in the 16 kHz window's spectrum (a 1024-point FFT, the FFT already in `Chirp.kt`) the strongest bin
between 500 and 5000 Hz is ≥ 8× the median bin. One whistle = these hold for ≥ 1 s, then at least 8 s below (a double
whistle back to back counts once; real whistles are ≥ 20 s apart). The whistle classes stay in the VOICE bucket for
rescue (nothing changes there); `Classifier.Result` gains a separate `whistle` field.

**Screen (COUNT tab of `ui/HomeScreen.kt`).** Target with − / + (default 3), the count in the largest type, the time
of each whistle, elapsed since the first, "last whistle 4 min ago". At the target: DONE = the step 1 alarm path (green
flash "3 WHISTLES · DONE", long-long-long ×3, notification over the lock screen) **plus beeps at alarm volume through
`Ping`**: the cook is in another room and, unlike rescue, noise is the point. A warning "no whistle for 15 min, check
the cooker" after the first whistle. TIMER beeps (step 1's category) show here too: "Microwave beeped 12:04". RESET.

**Files.** `audio/WhistleCounter.kt` (+ `WhistleCounterTest.kt`: synthetic per-second score/level/tonality series,
double whistle counts once, frying does not count), `Classifier.kt` `whistle` bucket, `ui/HomeScreen.kt` with FIND /
COUNT tabs (`screen_home.xml`), `Engine` feeds `home.onWindow`.

**Test.** `tools/calibration/cooker.wav` (record a real cooker with a phone: its `debug.wav`, or a clip) played from
another phone 2 m away with `--es play cooker.wav --ef level 0.6`, five whistles: count 5, no extra counts for the
tap running, a kettle, talking, a TV. Then a real cooker at someone's home: the count matches the cook's over 5
whistles and DONE fires within 3 s of the third. Log: `WHISTLE #2 at 12:04:31 score=0.55 tonal=14x level=x9`.

---

## Step 3 · FIND a noise: walk-to-triangulate (persona B, HOME → FIND)

**Idea.** The phone's own arrow (`KnockBearing`) already points at sharp repeating sounds with a left/right mirror
that turning the phone resolves. One phone cannot cross lines with itself unless it moves, so: stand still, let the
arrow settle, the app records a **mark** (where you stand, the bearing and its twin, the loudness); walk 2 m to the
side, stand still, second mark; third mark. The marks' bearing lines are crossed by `Crossing.solve` exactly as the
commander crosses its sensors' lines: a point, a radius, and a mirror rule (a mark whose twin is unresolved
contributes both candidates; the combination with the smallest residual wins clearly, or the third mark decides).
Position of each mark = step counting along the compass heading from where FIND started (`DeadReckoning`, 0.7 m per
step). Indoors steel bends the magnetic compass, but the rotation vector is gyro-stabilised, so turns are right over the
two or three minutes a search takes; the frame only needs to be consistent, not true north.

**Which noises.** Anything with sharp repeating onsets: a dripping tap (drips are steady onsets, the rhythm tracker
already says "steady"), a ticking, a rattling, a phone buzzing on a shelf, a low-battery smoke-alarm chirp every 30–60 s.
For that last one the arrow's rescue gates are too strict (3 knocks within 15 s), so `KnockBearing`'s `TAU_MS`,
`ACTIVE_MS` and `MIN_KNOCKS` become settable with today's values as defaults (rescue unchanged); FIND sets τ 60 s,
active 90 s, 2 knocks, and calls `KnockBearing.reset()` whenever the walker arrives at a new spot, so a spot's arrow
is not polluted by the last spot's bearings (that difference is the very thing that gives distance).

**Marks.** Automatic when: still ≥ 3 s (accelerometer `moving` false), ≥ 2 usable onsets heard at this spot, and this
spot is ≥ 1.5 m (by steps) from the previous mark; also a MARK button. A mark stores x, y (m), bearing, twin (null if
resolved), confidence, level = median (loudness − floor) in dB over the last 5 s. `Crossing.solve(marks, maxRange 15 m)`
runs after 2 marks. While ambiguous the screen says what to do: "walk 2 m to the side and stand still", or "turn
around once" (a resolved mark needs no third).

**Loudness cues.** *Warmer/colder*: level now vs the level at the last mark, smoothed 3 s, as a bar and a word.
*1/r fit*: on a 0.25 m grid over the marks ±8 m, cost Σ(level_i − (L0 − 20·log10 r_i))² with L0 removed by the mean;
weight 0.5 (σ 6 dB) against the crossing, the same weak-cue role the locator gives loudness. It breaks the two-mark
mirror tie and nothing more: indoors, walls and doors decide loudness, not distance.

**Screen (FIND tab).** The arrow (`ArrowView`): the crossing when there is one ("→ NOISE · 3.2 m ±0.8 · 3 spots"),
else this spot's own arrow with its twin and the prompt. A small map (`MapView` gets a walk mode: the walker as A,
marks as 1 2 3, their bearing lines, the walked path, the red cross-hair) so the person sees the noise is "in that
corner of the kitchen". Warmer/colder bar. Mode buttons TICKS / HUM / ANY (HUM comes in step 4). RESET.

**Expected accuracy.** ±12° per mark, ±1 m of position per 10 m walked: the noise lands within about 1 m at 3–5 m,
enough for "behind the fridge" against "the bathroom". A sound through a doorway arrives from the doorway: the arrow
points at the door, and the person walks through it and marks again. Put the phone on cloth or hold it: knocks felt
through a table lie about direction (`felt` weight 0.3 already).

**Files.** `WalkLocator.kt` (marks, auto-mark rule, crossing call, 1/r grid, prompts; pure, `WalkLocatorTest.kt`:
three marks around a synthetic source, a mirrored first mark resolved by the third, too-close marks refused),
`KnockBearing` settable constants, `MapView` walk mode, FIND tab in `HomeScreen`, `Engine` passes `ownArrowRaw()`,
`deadReckoning.east/north`, heading and the window to `home`.

**Test.** A phone playing `knocks.wav` (1/s) hidden in a room; the searcher starts 5 m away in the corridor, stands
still (arrow + twin), walks 2 m sideways, stands (mark 2: a crossing or "turn around once"), walks again (mark 3:
cross-hair, "→ NOISE · 4.1 m"), follows the arrow: within 1 m of the phone in under 2 minutes. Log: `WALK mark #2 at
(1.4, 0.3) m bearing 72° twin 248° conf 0.6 level −41 dB`, `WALK cross (3.8, 2.9) ±0.9 m from 3 marks`, `WALK: no
point: marks 1,2 mirrored, walk to a third spot`.

---

## Step 4 · FIND for continuous noises (hums)

A fridge, a fan, a pump: no onsets. Per second, the two-mic delay over the whole second as the voice DoA does today
(`Doa.delayBandPassed`), but with a low band, 60–1500 Hz (the voice band starts at 300 Hz and would drop the hum).
At 100 Hz the wavelength is 3.4 m against 0.17 m of mic spacing, so there is no cycle ambiguity, only a broad
correlation peak: ±25° per second, fed into the same bearing histogram with its quality. The loudness cues take the
lead for hums: warmer/colder becomes the main readout and the 1/r fit gets weight 1. YAMNet's Hum, Mains hum,
Mechanical fan, Vacuum cleaner, Boiling, Water, Drip name the kind of noise on screen. Limits, said on screen: a hum
carried by a whole wall (pipes) has no single point; expect "warmer" and a wide disc, not a cross-hair.

**Test.** A fan in a closed room, door open; from the corridor the warmer/colder bar rises towards the door and the
disc lands in the room within a wall's width.

---

## Step 5 · TEACH a sound (your doorbell, your microwave)

Doorbells and appliance beeps vary far more than YAMNet's classes do, but each home's are the same every time. TEACH:
press, make the sound three times; the phone keeps the average of YAMNet's 521 scores over the loud seconds (no
audio is kept: 521 numbers). Live: cosine similarity ≥ 0.85 with a stored fingerprint and loudness ≥ 2× the floor →
"MY DOORBELL" with the colour and vibration the user picked. This is a coarse fingerprint (the model has no embedding
output); if two taught sounds collide in step 0's numbers, the upgrade is a 64-band log-mel spectrogram template of
1 s with normalised cross-correlation, computed with the FFT in `Chirp.kt`. Stored in `files/sounds.json`; TEACH
also available on the COUNT tab for a cooker whose whistle YAMNet misses.

**Test.** Teach the venue's doorbell or a microwave beep; play it 10 times: ≥ 9 hits; 30 min of TV: 0 hits.

---

## Step 6 · Numbers and the long run

- **Battery per hour** in ALERTS and COUNT with the screen off, with the classifier gated (step 1) and, for
  comparison, every second. Written into CLAUDE.md honestly.
- **False alerts per hour** with a TV at normal volume, and detection rate per category from 10 tries each.
- **Reboot.** A microphone foreground service may not start at boot (Android 15+). The boot receiver that already
  re-arms the rescue port (`ProbeReceiver`) posts "Tap to resume Hush alerts"; one tap restarts ALERTS. Say so on the
  ALERT screen. i Manager → Autostart → Hush on, and the battery exemption (already asked at first launch).
- **Eight hours with the screen off** on OriginOS: gaps in the `window` lines tell whether the system killed the
  service; if so, the AlertScreen shows "stopped at 03:12" on reopening instead of pretending.

---

## What is deliberately left out

- No speech recognition or keywords ("help!"): no on-device model in the app and no cloud. Distress = scream, shout,
  cry, by YAMNet's classes.
- No identification of who is at the door, no smart-doorbell or smart-home integration, no hearing-aid streaming.
- No torch strobe in these steps (it is listed for rescue; if the team wants it for the doorbell it is one line in
  `Alerting`: `CameraManager.setTorchMode`).
- No radios in the household roles: no mesh, no Bluetooth tag, no GPS. One phone, on its own, nothing sent anywhere.
- FIND does not claim a dot through walls: a sound through a doorway is located at the doorway.

## Unknowns that the steps answer

1. What YAMNet actually scores on a real pressure cooker and on Indian doorbells (step 0 (a) decides the thresholds
   and whether TEACH is needed on day one).
2. Whether the step detector fires on slow indoor walking (rescue tests saw it on carried phones; if not, FIND counts
   steps itself from the accelerometer, which `AccelChannel` already streams at 200 Hz).
3. Whether the compass frame stays consistent for three minutes indoors (marks that stop crossing are the symptom).
4. Battery per hour of listening alone, and whether OriginOS keeps the microphone service alive for a night.
5. False-alert rate with a TV on: the one thing that decides whether persona C keeps the app installed.

## Demo script once steps 1–3 are in

1. **ALERTS.** One phone on the table, screen off. Someone rings a doorbell recording: the phone lights blue,
   DOORBELL, double buzz. Three knocks on the door: yellow, three buzzes in the same rhythm. A smoke-alarm recording:
   red until dismissed. Show the history list.
2. **COUNT.** Target 3; play three cooker whistles: 1, 2, 3 · DONE, green, beeps and buzz from another room.
3. **FIND.** Hide a phone playing knocks; from the corridor, mark, walk, mark, walk, mark: the cross-hair lands on the
   room, the arrow leads to the phone.
