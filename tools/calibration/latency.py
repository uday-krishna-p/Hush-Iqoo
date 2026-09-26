"""Command latency over the mesh: commander 'Ranging: chirping' / 'Hush window started' times vs the
sensors' 'Command received: CHIRP/HUSH' times (all phones' wall clocks agreed to within a second today)."""
import re
from datetime import datetime

def ts(l): return datetime.strptime(l[:12], "%H:%M:%S.%f")
def tsec(l): d = ts(l); return d.hour * 3600 + d.minute * 60 + d.second + d.microsecond / 1e6
A = open("log_A.txt", encoding="utf-8", errors="replace").read().splitlines()
trig = []
for l in A:
    m = re.search(r"Ranging: chirping (.+?)…", l)
    if m:
        letters = m[1].split()
        for i, L in enumerate(letters): trig.append((tsec(l) + 1.8 * i, "CHIRP", L))
    if "re-chirping [" in l:
        letters = re.search(r"re-chirping \[(.+?)\]", l)[1].replace(",", "").split()
        for i, L in enumerate(letters): trig.append((tsec(l) + 1.8 * i, "CHIRP", L))
    if "Hush window started" in l: trig.append((tsec(l), "HUSH", "-"))
for S in "BC":
    lines = open(f"log_{S}.txt", encoding="utf-8", errors="replace").read().splitlines()
    recv = [(tsec(l), re.search(r"Command received: (\w+)", l)[1], (re.search(r"letter=(\w+)", l) or [None, "-"])[1]) for l in lines if "Command received:" in l]
    lat = []
    for t, kind, L in trig:
        cands = [r for r in recv if r[1] == kind and r[2] == L and 0 <= r[0] - t <= 20]
        if cands: lat.append((kind, L, min(c[0] - t for c in cands)))
    if lat:
        vals = [x[2] for x in lat]
        print("%s: %d commands matched, latency median %.2f s, max %.2f s: %s" % (S, len(lat), sorted(vals)[len(vals) // 2], max(vals), " ".join("%s%s=%.1f" % (k[0], L, v) for k, L, v in lat)))
    else:
        print("%s: no matched commands" % S)
