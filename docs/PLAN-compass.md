# Plan: a compass that points at the knocking (27 Sep 2026)

Goal from the team: **an arrow on every phone that points at the sound. Approximation, not precision. A working
demo.** Audible chirps are out of the demo path. Nothing here needs clock sync between phones, chirp calibration
or a solved map.

Three pieces, in build order. Each is one build, installed on all phones, tested, committed, pushed.

| Step | What the demo gains | Needs | Size |
|---|---|---|---|
| 0 | Three facts we do not have yet (30 min of measuring, no app change) | current build | 1 hour |
| 1 | **Every phone's own arrow points at the knock** (two mics, no chirps, no positions) | step 0 (a, b) | 1 build |
| 2 | The commander sees every phone's arrow on its map and crosses them into a source point | step 1, a layout | 1 build |
| 3 | Positions from GPS outdoors (phones 15 m+ apart), north-up map, nothing to place by hand | step 2 | 1 build |
| 4 | Silent ultrasonic chirps (17–20 kHz) replace the audible ones for ranging, only if step 0 (c) passes | step 0 (c) | 1–2 builds |

---

## Step 0 · Measure three facts (existing build, no code change)

**(a) Which recording channel is the top microphone.** The code's two-mic delay is "samples mic 1 hears the
knock after mic 0" (`Doa.kt`). We do not know whether mic 1 is the top mic (near the camera) or the bottom mic.
Test: one phone on cloth, knock on the table 1 m beyond its **top** edge, 10 knocks; then 1 m beyond its
**bottom** edge; then 1 m to its **left**, then **right**. Read the `Onset … dl=… q=…` lines from its log
(`adb -s <serial> shell "run-as com.hush cat files/hush.log" | grep Onset`). Expect: top and bottom give
opposite signs of about the same size, left and right give values near 0. The sign of "top" becomes the constant
`MIC1_IS_TOP` in step 1.

**(b) Mic spacing.** The largest |dl| from the top/bottom knocks in (a). Sound moves 7.1 mm per sample, so 21
samples = 0.15 m. The chirp solver once found 0.10 m and the code prefers 0.14 m; a 16 cm phone with mics at
both ends should read about 20 samples. This becomes `MIC_SPACING_M`. Approximate is fine: the spacing only
scales the delay-to-angle curve, and an error of 20 % in it moves an arrow at 45° by about 8°.

**(c) Ultrasonic roll-off (gate for step 4).** A WAV of 1 s tones at 2, 6, 12, 15, 16, 17, 18, 19, 20, 21, 22 kHz,
same amplitude, made by a new `tools/calibration/tones.py`. One phone plays it (`--es play tones.wav --ef level 0.4`,
the chirp's volume, then `1.0`); another phone 1 m away records it in its `debug.wav` (first 90 s of raw audio,
already written by every phone), then again at 3 m. A new `tools/calibration/spectrum.py` reads the WAV and
prints the level in a ±200 Hz band around each tone against the band's noise. **Pass:** at 3 m the 17–20 kHz tones
stand ≥ 6× above their band's noise. (The matched filter adds about 25 dB on top, so 20 dB below the 2–6 kHz
band is still plenty.) The same recording shows whether the current microphone source (VOICE_RECOGNITION) cuts
above 16 kHz; if it does, step 4 moves UNPROCESSED first in the source list.

---

## Step 1 · Every phone's own arrow (the core of the demo)

**Idea.** Each phone already times every knock to the sample and measures the delay between its two mics over
the first 13 ms of the knock (`Engine.buildOnsetReport`, `Doa.delay`, reported as `dl=` with a quality `q=`).
Today that number is only used by the commander's locator, and only after a chirp round has calibrated the
phone's "mic axis". Instead: turn the delay into an angle **on the phone itself** and draw it. Nothing else is
needed: the arrow is drawn relative to the phone's top, so it does not even need the compass to be right.

**Maths (tiny).** `cos θ = delay / maxDelay`, `maxDelay = MIC_SPACING_M × 48000 / 343` (≈ 20 samples).
θ is the angle between the knock and the phone's long axis, 0° = beyond the top edge, 180° = beyond the bottom
edge (sign fixed by `MIC1_IS_TOP`). The two mics lie on one line, so the knock is at **+θ or −θ** from the top:
left or right of the phone is a mirror the mics cannot tell. Delays beyond ±maxDelay clamp to the ends.

**Mirror resolution by turning the phone.** Every onset is stored as two world bearings, `heading + θ` and
`heading − θ` (the compass heading of the phone's top at that moment). Two decaying circular sums (τ = 15 s)
accumulate the candidates. While the phone lies still both sums are equally coherent and the arrow shows both
directions (a solid arrow and a dimmer twin, label "turn the phone to confirm"). As soon as the rescuer turns the
phone, the true bearing keeps adding up in the same direction while its mirror swings by twice the turn and
cancels out; the sum with the longer resultant wins and the twin disappears ("confirmed"). The loudest-phone
target from the commander, when known, breaks ties too. After a 45° turn the wrong candidate is 90° off, so a
few knocks decide it.

**Gates so room noise does not steer it.** Use an onset only if `q ≥ 0.5`, the phone is not `moving`, it is not
self-noise (start buzz, beeps, chirps), and it is not tagged `felt` (a knock that came through the table reaches
the mics through wood, faster than through air, and the delay lies). The arrow is drawn in colour only while the
phone has heard ≥ 3 usable knocks in the last 15 s; otherwise it goes grey with the current fallback text.
**Phones must sit on cloth or foam, or be held**, as the notes already say.

**Expected accuracy.** ±10–15° in a room for knocks 1–3 m away off to the side; worse near the ends of the mic
line (at θ near 0° or 180° one sample of delay is worth 15–20°); ±20° with strong reflections. Fine for an arrow.

**Code changes.**
- `audio/KnockBearing.kt` (new, ~120 lines): constants `MIC_SPACING_M`, `MIC1_IS_TOP`, `TAU_MS = 15000`,
  `MIN_Q = 0.5`; `add(delay, q, heading, nowMs)`; `estimate(nowMs, headingNow)` → screen angle (clockwise from
  the phone's top), twin angle or null once resolved, confidence 0..1, knock count, world bearing.
- `Engine.kt`: feed it from `buildOnsetReport` (every role, audio thread, under the existing gates); expose
  `ownArrow()`; log `KNOCK ARROW screen=… twin=… bearing=… knocks=… conf=…` once a second when active; reset in
  `stop()`.
- `ui/ArrowView.kt`: optional second arrow (`twinAngleDeg`), drawn dim.
- `ui/SensorScreen.kt` and `ui/CommanderScreen.kt`: the own two-mic arrow has first claim on the ArrowView
  whenever it is active (it is what THIS phone hears); the existing targets (located source, loudest phone,
  commander's fix) are the fallback. Strings: `→ KNOCKING · %d knocks · turn the phone to confirm` and
  `→ KNOCKING · %d knocks · confirmed`.
- Laptop hook for the sign test: `--es mic1top true|false` flips the constant at run time (saved in preferences).

**Test (the demo).** Three phones on cloth on the table, any orientation, no chirps, no layout, no HUSH. Knock
1 m away. Within 3–4 knocks every phone's arrow points at the knock (±20°) or shows the twin pair. Turn one
phone by 90°: its arrow stays on the knock and the twin vanishes. Knock from the other side of the table: the
arrows follow. Log: `KNOCK ARROW` lines on every phone.

---

## Step 2 · The commander sees all the arrows and crosses them

**What it adds.** Every sensor tells the commander which way it hears the knocking (a world bearing from its
compass). The commander draws a short line from each sensor's dot in that direction on the map. Where two or
more lines cross is the source: a point, not just a direction. This is the only place the demo gets a distance
without chirps. Needs positions on the map (hand layout today, GPS in step 3) and a compass on each phone.

**Code changes.**
- `model/Events.kt`: `SensorEvent` gains `bearing` (`br`, degrees from magnetic north, null when inactive),
  `bearingQ` (`bq`, 0..1) and `bearingTwin` (`br2`, present while unresolved). Three optional JSON fields; old
  builds ignore them.
- `Engine.kt` (commander): keep the latest bearing per letter; `crossBearings()` every second: least-squares
  intersection of the resolved bearing lines of phones that are on the map (2×2 normal equations); accept when
  the lines meet at ≥ 20° and the crossing lies **in front** of every phone and within 3× the array size;
  otherwise no crossing ("direction only"). Send it down as the existing `Fix` (the sensors' arrows and the map's
  red cross-hair already understand it) with `near = null`; mark it in the export as `cross`.
- `ui/MapView.kt`: draw each phone's bearing as a line from its dot (dim while unresolved), the crossing as the
  existing cross-hair.
- Commander screen: the own two-mic arrow keeps first claim; the crossing supplies the "~N m" distance when it
  agrees with the own bearing within 30°.

**Test.** Hand layout as today (`--es layout` or the Place buttons) and Align B/Align C for north. Knock 2 m
outside the triangle: every dot grows a line towards the knock, the cross-hair lands near the knock, arrows show
the distance.

---

## Step 3 · Positions from GPS (outdoors, wide spacing)

**What it adds.** On a real site the phones are 10–30 m apart and there is sky: GPS puts every phone on the map
with ±3–5 m error, which at 20 m is a ±10–15° arrow error. No placing, no pointing, no chirps. Indoors GPS gives
nothing and the hand layout stays.

**Code changes (commander only; every phone already sends `lat/lon/gacc` in each event when it has a fix).**
- `Engine.applyGpsLayout()` every 5 s: for each phone with a fix < 60 s old and accuracy ≤ 20 m, take the median
  of its last 5 fixes, convert to east/north metres relative to the commander's own fix
  (`east = Δlon × cos(lat) × 111 320`, `north = Δlat × 110 574`). Accept the layout only when the **closest pair
  of phones is at least 2× the worst accuracy** apart, so a 3 m error cannot flip a 4 m triangle. Set the dots
  through the same path as the hand layout (`mapDots`, `mapMetresPerUnit`, scale fixed at the first GPS layout),
  `mapRotationDeg = 0` (map up = north), `mirror = false`, `alignSource = "GPS (±N m)"`. A hand layout or an
  Align pointing set by the team wins over GPS, same rule as today for pointing over chirp alignment.
- The commander's own dot follows its GPS between updates instead of step counting while GPS positions are in use.
- Status line: `Positions: GPS ±4 m (3 phones)`; log `GPS layout: A(0,0) B(12.3,-4.1) C(…) acc 3.2/4.0/3.8 m`.

**Test.** Outdoors, three phones 15–20 m apart, screens on. Within 30 s the map shows the triangle north-up
and the status says GPS. Someone knocks on a board 5 m outside the triangle: step 1 arrows on every phone, step
2 lines and crossing on the commander. Walk the commander 10 m: its dot moves with it.

---

## Step 4 · Silent ultrasonic chirps (optional, only if step 0 (c) passes)

**Why only optional.** The arrow demo above never chirps. Chirps buy three things the demo does without:
centimetre distances between phones, clock sync for the timing-based locator, and mic-axis calibration. If the
phones can send and hear 17–20 kHz at 3 m, all of that comes back **inaudibly**, in the code that already exists,
by changing the chirp's band. If they cannot, we skip this step and lose nothing from the demo.

**Code changes.**
- `audio/Chirp.kt`: `F_START = 17000`, `F_END = 20000` (or wider, per the measured roll-off), same 80 ms Hann
  sweep. The coarse search today runs on a 16 kHz copy of the audio, which cannot hold anything above 8 kHz, so
  the coarse stage moves to the full 48 kHz stream (FFT size 2^18 for a 5 s search instead of 2^17; roughly
  2× the current 37–94 ms, still well inside the 7 s round timeout). Refinement stage unchanged.
  `warmUp` follows. The two-mic chirp delay (`micDelay`) keeps working but its cycle ambiguity becomes 2.6
  samples instead of 12; it stays gated and unused for north, knocks do that job now.
- `audio/AudioCapture.kt`: if step 0 (c) shows VOICE_RECOGNITION cuts above 16 kHz, put UNPROCESSED first
  (it measured ~10 dB quieter; the noise floor and the tap threshold are relative, so they should not care, but
  the `window` lines will show it).
- `Chirp.VOLUME_FRACTION` back up to 0.9: nobody hears it, so there is no reason to whisper.
- `ChirpTest` re-run with the new band; the `firstArrivalShift` / envelope constants stay.
- `Engine.chirpCalibration` stays **false** by default (the team's decision). AUTO-PLACE and `--ez range` chirp
  silently on request; a HUSH still goes straight to the window. If the silent rounds prove reliable, a
  "RANGE (silent)" button on the commander runs one on demand and the locator's timing fix returns as the source
  of distance in the arrow label.
- The 1.5 s onset blackout after a chirp stays: the tap detector works on the full-band energy and would count a
  loud ultrasonic chirp as a knock.

**Test.** Nobody hears anything. `--ez range`: `Chirp from X ratio=…` ≥ 5 on every phone at 1 m and 3 m,
`RANGING result` within ±0.2 m of a tape measure, `Clock:` spread < 10 samples.

---

## What is deliberately left out

- No per-phone mic-axis calibration by chirps, no clock sync, no TDOA in the demo path (step 4 is the only way
  back to them, and it is silent).
- No RTK / "precise GPS" (needs correction data over the internet).
- No attempt to resolve the two-mic mirror without turning the phone (the loudest-phone cue is used only as a
  tie-breaker).
- The audible HUSH start/end buzz and beeps stay: they call people to silence on purpose.

## Unknowns that the steps answer

1. Which channel is the top mic and how far apart the mics are (step 0 a, b). Wrong sign = arrow points the
   opposite way, fixed at run time by `--es mic1top`.
2. Whether knocks through a shared table spoil the delay (the `felt` gate and cloth handle it; step 1 test).
3. Whether the phones pass 17–20 kHz at 3 m (step 0 c decides step 4).
4. GPS is outdoor only; the plan is explicit about that.

## Demo script once steps 1–3 are in

1. Three phones on cloth, any orientation, pick COMMANDER and SENSOR. No HUSH needed.
2. Knock on the table 1–2 m away. Every screen's arrow swings towards the knock within 3–4 knocks; a phone that
   shows two arrows resolves as soon as it is turned.
3. Outdoors: spread the phones 15 m apart, wait for "Positions: GPS", knock on a board: the commander's map shows
   the triangle, the bearing lines and the cross-hair, every arrow points at the board with a rough distance.
4. EXPORT LOG as before.
