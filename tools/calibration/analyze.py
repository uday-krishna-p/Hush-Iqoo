"""
Reads log_A.txt, log_B.txt, log_C.txt (the phones' hush.log for the session) and schedule.json, and reports
how each phone's detectors did against the ground truth: C played knocks.wav, so C's "PLAY: started ... at
sample S" line pins every scheduled knock to C's audio clock; A and B are aligned to the schedule by the
best time offset. Then: detection rate and timing jitter per phase, false onsets, pairwise timing
stability (what the locator's TDOA sees), two-mic delay stability, rhythm labels per phase, locator fixes.
"""
import json, re, sys, statistics as st
from datetime import datetime

FS = 48000
sched = json.load(open("schedule.json"))
knocks = sched["knocks"]
phases = ["steady", "3-2", "fast"]

def ts(line):
    try: return datetime.strptime(line[:12], "%H:%M:%S.%f")
    except Exception: return None

def parse(letter):
    lines = open(f"log_{letter}.txt", encoding="utf-8", errors="replace").read().splitlines()
    onsets, windows, play = [], [], None
    for l in lines:
        m = re.search(r"Onset @(\d+) \(\+(\d+) ms\) peak=([\d.]+) x(\d+) rise=(\d+) dl=(\S+) q=(\S+)( felt)? heading", l)
        if m:
            onsets.append(dict(t=ts(l), s=int(m[1]), peak=float(m[3]), ratio=int(m[4]), rise=int(m[5]),
                               dl=None if m[6] == "-" else float(m[6]), q=None if m[7] == "-" else float(m[7]), felt=bool(m[8])))
            continue
        m = re.search(r"window id=(\S+) hush=(\w+) label=(.+?) rms=([\d.]+) floor=([\d.]+) taps=(\d+) rej=(\d+) .*rhythm=([\d.]+)/(\S+?)\((\d+)\)", l)
        if m:
            windows.append(dict(t=ts(l), label=m[3], rms=float(m[4]), floor=float(m[5]), taps=int(m[6]), rej=int(m[7]),
                                rscore=float(m[8]), rname=m[9], rcount=int(m[10])))
            continue
        m = re.search(r"PLAY: started \S+ .* at sample (-?\d+) elapsed (\d+)", l)
        if m: play = dict(t=ts(l), s=int(m[1]))
    return dict(onsets=onsets, windows=windows, play=play, lines=lines)

logs = {L: parse(L) for L in "ABC"}
playC = logs["C"]["play"]
if not playC or playC["s"] < 0:
    sys.exit("C has no PLAY start line with a sample clock")

def sched_samples(offset_samples):
    return [(offset_samples + k["ms"] * FS // 1000, k["phase"]) for k in knocks]

def match(onset_samples, offset, tol_ms=60):
    """Greedy match of detected onsets to scheduled knocks within tol. Returns (matches, missed, extra)."""
    tol = tol_ms * FS // 1000
    used = set(); matches = []; missed = []
    for s_true, phase in sched_samples(offset):
        best = None
        for i, s in enumerate(onset_samples):
            if i in used: continue
            d = s - s_true
            if abs(d) <= tol and (best is None or abs(d) < abs(best[1])): best = (i, d)
        if best is None: missed.append((s_true, phase))
        else: used.add(best[0]); matches.append((best[0], best[1], phase, s_true))
    extra = [s for i, s in enumerate(onset_samples) if i not in used]
    return matches, missed, extra

def best_offset(onset_samples, hint=None, span_ms=300):
    """Offset (phone sample of the schedule's t=0) that lines up the most onsets with scheduled knocks.
    Every (onset, knock) pair votes for the offset that would align them; the best 4 ms bin wins, then the
    median residual refines it. With a hint (C's PLAY sample) only offsets within +-span_ms of it may vote:
    C's own knocks reach its mic after the playback latency, which the PLAY line does not include."""
    votes = {}
    for s in onset_samples:
        for k in knocks:
            c = s - k["ms"] * FS // 1000
            if hint is not None and abs(c - hint) > span_ms * FS // 1000: continue
            b = c // (4 * FS // 1000)
            votes[b] = votes.get(b, 0) + 1
    if not votes: return None
    best = None
    for b in sorted(votes, key=votes.get, reverse=True)[:5]:
        c = b * (4 * FS // 1000) + 2 * FS // 1000
        m, _, _ = match(onset_samples, c, tol_ms=20)
        if not m: continue
        c2 = c + int(st.median([d for _, d, _, _ in m]))
        m2, _, _ = match(onset_samples, c2, tol_ms=20)
        if best is None or len(m2) > best[1]: best = (c2, len(m2))
    return best[0] if best else None

print("=" * 78)
print("GROUND TRUTH: C played %d knocks (%s) starting at C sample %d" % (len(knocks), ", ".join("%s x%d" % (p, sum(1 for k in knocks if k["phase"] == p)) for p in phases), playC["s"]))
offsets = {}
for L in "ABC":
    on = [o["s"] for o in logs[L]["onsets"]]
    if not on: print("\n%s: NO onsets logged" % L); continue
    off = best_offset(on, hint=playC["s"] if L == "C" else None)
    if off is None: print("\n%s: could not align onsets to the schedule" % L); continue
    offsets[L] = off
    m, missed, extra = match(on, off)
    print("\n%s: %d onsets logged, %d matched a scheduled knock, %d scheduled knocks missed, %d unscheduled onsets" % (L, len(on), len(m), len(missed), len(extra)))
    if L == "C":
        lat = [off + d - playC["s"] for _, d, _, _ in m]
        if lat: print("   C's own knocks reached its mic %.1f ms after the PLAY sample (playback latency + speaker-to-mic path), spread (IQR) %.2f ms" % (st.median(lat) / 48, (sorted(lat)[3 * len(lat) // 4] - sorted(lat)[len(lat) // 4]) / 48))
    for p in phases:
        mp = [d for _, d, ph, _ in m if ph == p]; tot = sum(1 for k in knocks if k["phase"] == p)
        if mp:
            res = [(d - st.median(mp)) / 48 for d in mp]
            print("   %-6s detected %2d/%2d, timing jitter (median abs residual) %.2f ms, worst %.1f ms" % (p, len(mp), tot, st.median([abs(r) for r in res]), max(abs(r) for r in res)))
        else:
            print("   %-6s detected  0/%2d" % (p, tot))
    ons = logs[L]["onsets"]
    matched_idx = {i for i, _, _, _ in m}
    peaks = [ons[i]["peak"] for i in matched_idx]; ratios = [ons[i]["ratio"] for i in matched_idx]; rises = [ons[i]["rise"] for i in matched_idx]
    if peaks: print("   matched knocks: peak median %.3f (min %.3f), ratio median x%d (min x%d), rise median %d samples (%.1f ms)" % (st.median(peaks), min(peaks), st.median(ratios), min(ratios), st.median(rises), st.median(rises) / 48))
    dls = [ons[i]["dl"] for i in matched_idx if ons[i]["dl"] is not None]; qs = [ons[i]["q"] for i in matched_idx if ons[i]["q"] is not None]
    if dls:
        srt = sorted(dls)
        print("   two-mic delay on matched knocks: median %.2f samples, IQR %.2f..%.2f, quality median %.2f (%d of %d had a delay)" % (st.median(dls), srt[len(srt) // 4], srt[3 * len(srt) // 4], st.median(qs), len(dls), len(matched_idx)))
    felt = sum(1 for i in matched_idx if ons[i]["felt"])
    if felt: print("   %d matched knocks were also felt by the accelerometer" % felt)
    if extra:
        ex = [ons[i] for i in range(len(ons)) if ons[i]["s"] in set(extra)]
        print("   unscheduled onsets: %d, peak median %.3f, ratio median x%d (self-noise? echoes? doubles?)" % (len(ex), st.median([e["peak"] for e in ex]), st.median([e["ratio"] for e in ex])))

# Pairwise: for knocks both phones detected, the sample difference should be constant (fixed geometry).
print("\nPAIRWISE TIMING (what the locator's time-difference cue sees; should be constant for a fixed layout):")
for X, Y in [("A", "B"), ("A", "C"), ("B", "C")]:
    if X not in offsets or Y not in offsets: continue
    # Key by the knock's schedule time: each phone's scheduled sample is on its own clock.
    mx = {s_true - offsets[X]: d for _, d, _, s_true in match([o["s"] for o in logs[X]["onsets"]], offsets[X])[0]}
    my = {s_true - offsets[Y]: d for _, d, _, s_true in match([o["s"] for o in logs[Y]["onsets"]], offsets[Y])[0]}
    both = [(mx[s] - my[s]) / 48 for s in mx if s in my]
    if len(both) >= 3:
        med = st.median(both); dev = [abs(b - med) for b in both]
        print("   %s-%s: %d common knocks, difference spread: median abs dev %.2f ms (= %.0f cm), 90th pct %.2f ms" % (X, Y, len(both), st.median(dev), st.median(dev) * 34.3, sorted(dev)[int(0.9 * (len(dev) - 1))]))

# Rhythm labels per phase from the per-second window lines of each phone
print("\nRHYTHM per phase (seconds labelled as expected; 'steady' phase → steady, '3-2' → 3-2, 'fast' → steady):")
t0 = playC["t"]
expect = {"steady": "steady", "3-2": "3-2", "fast": "steady"}
ranges = {"steady": (11, 28), "3-2": (41, 57), "fast": (66, 72)}   # seconds after play start, once the tracker has ≥ 3 onsets
for L in "ABC":
    row = []
    for p in phases:
        a, b = ranges[p]
        w = [x for x in logs[L]["windows"] if x["t"] and a <= (x["t"] - t0).total_seconds() <= b]
        ok = sum(1 for x in w if x["rname"] == expect[p]); tap = sum(1 for x in w if x["label"] == "HUMAN TAPPING")
        names = {}
        for x in w: names[x["rname"]] = names.get(x["rname"], 0) + 1
        row.append("%s %d/%d (tapping %d/%d) %s" % (p, ok, len(w), tap, len(w), names))
    print("   %s: %s" % (L, " | ".join(row)))

# Locator on the commander
lines = logs["A"]["lines"]
lk = [l for l in lines if "LOCATE knock" in l]; lf = [l for l in lines if "LOCATE fix" in l]
print("\nLOCATOR: %d knocks fused, %d fix updates" % (len(lk), len(lf)))
for l in lf[:2] + (["   ..."] if len(lf) > 4 else []) + lf[-2:]:
    print("   " + l[:200])
for key in ("RANGING result", "Clock:", "Axis:", "Placed by sound", "Ranging:"):
    for l in [l for l in lines if key in l][-2:]:
        print("   " + l[:170])
briefs = [l for l in lines if "BRIEF" in l or "Live ·" in l]
strong = {}
for l in briefs:
    m = re.search(r"strongest at Sensor (\w)", l)
    if m: strong[m[1]] = strong.get(m[1], 0) + 1
print("\nBRIEF 'strongest at': %s (C is the source)" % strong)
