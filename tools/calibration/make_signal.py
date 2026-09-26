"""
Builds the calibration signal from real ESC-50 door-knock recordings (CC BY-NC 3.0):
  1. finds single knocks in the clips (short energy bursts), cuts each with a little ring-down,
  2. lays them out on an exact schedule, so every knock has a known onset time (ground truth),
  3. writes knocks.wav (48 kHz, 16-bit mono) and schedule.json.

Schedule (ms from the start of the file):
  0–8000       silence (the phone's noise floor settles)
  8000–28000   STEADY: one knock per second (20 knocks)
  28000–33000  silence
  33000–57000  PATTERN 3-2: three quick knocks 250 ms apart, 700 ms gap, two knocks 250 ms apart, 1.8 s pause; x6
  57000–62000  silence
  62000–72000  FAST: 2.5 knocks per second (25 knocks)
  72000–78000  silence
"""
import glob, json, sys
import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly, butter, sosfilt

FS = 48000
files = sorted(glob.glob("*-30.wav"))
if not files:
    sys.exit("no ESC-50 knock clips here")

knocks = []   # list of float arrays at 48 kHz, peak-normalised, 20 ms pre-roll, up to 350 ms long
for f in files:
    sr, x = wavfile.read(f)
    x = x.astype(np.float64)
    if x.ndim > 1: x = x.mean(axis=1)
    x /= max(1.0, np.abs(x).max())
    if sr != FS:
        from math import gcd
        g = gcd(FS, sr); x = resample_poly(x, FS // g, sr // g)
    # 5 ms frame energy, onset = frame > 8x the median and > 3x the previous frame, 150 ms refractory
    frame = FS // 200
    n = len(x) // frame
    e = np.array([np.sqrt(np.mean(x[i*frame:(i+1)*frame] ** 2)) for i in range(n)])
    med = max(np.median(e), 1e-4)
    last = -100
    for i in range(1, n):
        if e[i] > 8 * med and e[i] > 3 * e[i-1] and i - last > 30:
            last = i
            start = max(0, i * frame - FS // 50)          # 20 ms before the frame
            end = min(len(x), i * frame + int(0.35 * FS))
            k = x[start:end].copy()
            # fade the tail so cut knocks do not click, and require a real transient
            tail = min(len(k), FS // 20)
            k[-tail:] *= np.linspace(1, 0, tail)
            pk = np.abs(k).max()
            # Single hit only: a second burst inside the cut would make the detector's pick ambiguous.
            fe = np.array([np.sqrt(np.mean(k[j*frame:(j+1)*frame] ** 2)) for j in range(len(k) // frame)])
            first = int(np.argmax(fe)); later = fe[first + 6:]        # 30 ms after the loudest frame
            if pk > 0.2 and (len(later) == 0 or later.max() < 0.30 * fe[first]):
                # A phone speaker radiates almost nothing below ~600 Hz: keep the click, drop the thump.
                sos = butter(2, 700, "hp", fs=FS, output="sos")
                k = sosfilt(sos, k)
                knocks.append((f, k / np.abs(k).max()))
print("single knocks found:", len(knocks), "from", len(files), "clips")
if len(knocks) < 4:
    sys.exit("too few knocks")

sched = []   # (ms, phase)
t = 8000
for i in range(20):
    sched.append((t + i * 1000, "steady"))
t = 33000
for rep in range(6):
    base = t + rep * 4000
    for j in range(3): sched.append((base + j * 250, "3-2"))
    for j in range(2): sched.append((base + 500 + 700 + j * 250, "3-2"))
t = 62000
for i in range(25):
    sched.append((t + i * 400, "fast"))
total_ms = 78000

out = np.zeros(int(total_ms / 1000 * FS))
rng = np.random.default_rng(2609)
for idx, (ms, phase) in enumerate(sched):
    f, k = knocks[idx % len(knocks)]
    # the onset inside the cut is 20 ms in (the pre-roll): place it so the knock STARTS at ms
    # find the true first-arrival sample in the cut: first sample above 10 % of peak
    on = int(np.argmax(np.abs(k) > 0.10))
    start = int(ms / 1000 * FS) - on
    level = 1.0 if phase != "fast" else 0.8
    seg = k * level
    end = min(len(out), start + len(seg))
    out[start:end] += seg[:end - start]
out = np.clip(out / max(1e-9, np.abs(out).max()) * 0.95, -1, 1)
wavfile.write("knocks.wav", FS, (out * 32767).astype(np.int16))
json.dump({"fs": FS, "total_ms": total_ms, "knocks": [{"ms": ms, "phase": p} for ms, p in sched],
           "clips": files, "single_knocks": len(knocks)}, open("schedule.json", "w"), indent=1)
print("wrote knocks.wav (%.1f s) with %d knocks" % (total_ms / 1000, len(sched)))
