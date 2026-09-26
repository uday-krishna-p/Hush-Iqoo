#!/usr/bin/env python3
"""
knockdir.py: calibrate the own knock arrow (KnockBearing) from the phones' logs.

Reads every phone's `Onset ...` lines (over adb, or from saved log files), groups them into bursts (a gap of
more than 4 s starts a new burst) and prints per burst: when, how many knocks, the median two-mic delay
(mic 1 minus mic 0, samples), its spread, the correlation quality, how many were felt through the table,
how many the arrow used, and the angle from the phone's top that the current constants would give.

Calibration rounds (phone on cloth or in the hand, knock 8-10 times at one per second):
  beyond the TOP edge, beyond the BOTTOM edge, LEFT of the phone, RIGHT of the phone, at 0.5 m.
Expected: top and bottom give opposite signs of about the same size (that size = the end-fire delay, i.e. the
mic spacing: 21.7 samples for 0.155 m), left and right give about 0. If TOP is positive, channel 1 is the
bottom mic: run `adb shell am start -n com.hush/.MainActivity --es mic1top false` on every phone.

Usage:
  python knockdir.py                       # all phones on USB, bursts of the last 20 minutes
  python knockdir.py --minutes 5           # only the last 5 minutes
  python knockdir.py --file log_B.txt      # a saved log instead of adb
  python knockdir.py --spacing 0.14 --mic1top false   # what the angles would be with other constants
"""
import argparse
import math
import re
import statistics
import subprocess
import sys
from datetime import datetime, timedelta

ONSET = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d\d\d) Onset @(\d+) \(\+\d+ ms\) peak=([\d.]+) x(\d+) rise=\d+ '
                   r'dl=(-?[\d.]+|-) q=([\d.]+|-)( felt)? heading=(-?\d+)( arrow)?')
GEOMETRY = re.compile(r'Mic geometry: mic1IsTop=(true|false) spacing=([\d.]+) m')
FS, C = 48000.0, 343.0


def read_log(serial=None, path=None):
    if path:
        return open(path, encoding="utf-8", errors="replace").read().splitlines()
    out = subprocess.run(["adb", "-s", serial, "shell", "run-as com.hush cat files/hush.log"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    return out.stdout.splitlines()


def parse(lines):
    onsets, geometry = [], None
    for ln in lines:
        g = GEOMETRY.search(ln)
        if g:
            geometry = (g.group(1) == "true", float(g.group(2)))
        m = ONSET.match(ln)
        if not m:
            continue
        h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
        t = h * 3600 + mi * 60 + s + ms / 1000.0
        dl = None if m.group(8) == "-" else float(m.group(8))
        q = None if m.group(9) == "-" else float(m.group(9))
        onsets.append(dict(t=t, peak=float(m.group(6)), ratio=int(m.group(7)), dl=dl, q=q,
                           felt=bool(m.group(10)), heading=int(m.group(11)), used=bool(m.group(12))))
    return onsets, geometry


def theta(dl, spacing, mic1top):
    md = spacing * FS / C
    towards_top = -dl if mic1top else dl
    return math.degrees(math.acos(max(-1.0, min(1.0, towards_top / md))))


def bursts(onsets, gap=4.0):
    out, cur = [], []
    for o in onsets:
        if cur and o["t"] - cur[-1]["t"] > gap:
            out.append(cur); cur = []
        cur.append(o)
    if cur:
        out.append(cur)
    return out


def hms(t):
    return "%02d:%02d:%02d" % (int(t) // 3600, (int(t) // 60) % 60, int(t) % 60)


def summarise(name, onsets, geometry, args):
    now = max((o["t"] for o in onsets), default=0)
    recent = [o for o in onsets if now - o["t"] <= args.minutes * 60]
    mic1top = args.mic1top if args.mic1top is not None else (geometry[0] if geometry else True)
    spacing = args.spacing if args.spacing else (geometry[1] if geometry else 0.155)
    print("== %s  (constants: mic1IsTop=%s spacing=%.3f m -> end-fire %.1f samples)" % (name, mic1top, spacing, spacing * FS / C))
    if not recent:
        print("   no onsets in the last %d minutes" % args.minutes)
        return
    for b in bursts(recent):
        if len(b) < args.min_knocks:
            continue
        good = [o for o in b if o["dl"] is not None and o["q"] is not None and o["q"] >= 0.5]
        dls = [o["dl"] for o in good]
        felt = sum(1 for o in b if o["felt"])
        used = sum(1 for o in b if o["used"])
        ratios = [o["ratio"] for o in b]
        line = "   %s  %2d knocks (%d s)  ratio x%d..x%d  felt %d  used by arrow %d" % (
            hms(b[0]["t"]), len(b), int(b[-1]["t"] - b[0]["t"]), min(ratios), max(ratios), felt, used)
        if dls:
            med = statistics.median(dls)
            spread = statistics.median([abs(d - med) for d in dls])
            th = theta(med, spacing, mic1top)
            line += "\n        two-mic delay median %+.1f samples (MAD %.1f, %d with q>=0.5, q median %.2f) -> %.0f deg from the top %s" % (
                med, spread, len(dls), statistics.median([o["q"] for o in good]), th,
                "(TOP)" if th < 30 else "(BOTTOM)" if th > 150 else "(side)")
            line += "\n        delays: " + " ".join("%+.0f" % d for d in dls)
        else:
            line += "\n        no usable two-mic delays (q < 0.5 or none)"
        print(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=20)
    ap.add_argument("--min-knocks", type=int, default=3)
    ap.add_argument("--spacing", type=float, default=None)
    ap.add_argument("--mic1top", type=lambda s: s.lower() == "true", default=None)
    ap.add_argument("--file", action="append", default=[])
    args = ap.parse_args()
    if args.file:
        for f in args.file:
            onsets, geometry = parse(read_log(path=f))
            summarise(f, onsets, geometry, args)
        return
    devs = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    serials = [d.split()[0] for d in devs if d.strip() and d.split()[1] == "device"]
    if not serials:
        print("no phones on USB"); sys.exit(1)
    for s in serials:
        lines = read_log(serial=s)
        name = next((re.search(r"name=(I2501-\w+)", ln).group(1) for ln in reversed(lines) if "Engine start" in ln and "name=" in ln), s)
        onsets, geometry = parse(lines)
        summarise("%s (%s)" % (name, s), onsets, geometry, args)


if __name__ == "__main__":
    main()
