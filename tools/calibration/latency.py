"""Command latency over the mesh for the SERIAL ranging protocol (27 Sep).

Trigger time of each CHIRP on the commander (log_A.txt): an explicit "Ranging: trigger CHIRP X" line when the
build logs it; otherwise inferred: the first letter at "Ranging: chirping ..." / "re-chirping [...]", each next
letter 0.4 s (CHIRP_SETTLE_MS) after the "Ranging: chirp L reported by ..." line of the previous one.
Receive time on each sensor: its "Command received: CHIRP ... letter=X" line. HUSH: "Hush window started".

Wall clocks: pass per-phone clock corrections in seconds (phone minus laptop, from `adb shell date +%s.%N`) as
  python latency.py A=0.00 B=-0.41 C=0.53
otherwise the phones are assumed to agree (they did to within about a second on 26 Sep).
"""
import re, sys

corr = {k: float(v) for k, v in (a.split("=") for a in sys.argv[1:])}

def tsec(l):
    h, m, s = l[:12].split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)

def lines(letter):
    out = []
    for l in open(f"log_{letter}.txt", encoding="utf-8", errors="replace"):
        if re.match(r"\d\d:\d\d:\d\d\.\d{3} ", l): out.append((tsec(l) - corr.get(letter, 0.0), l[13:].rstrip()))
    return out

A = lines("A")
trig = []
explicit = any("Ranging: trigger CHIRP" in s for _, s in A)
queue = []
for t, s in A:
    if explicit:
        m = re.search(r"Ranging: trigger CHIRP (\w)", s)
        if m: trig.append((t, "CHIRP", m[1]))
    else:
        m = re.search(r"Ranging: chirping (.+?)…", s) or re.search(r"re-chirping \[(.+?)\]", s)
        if m:
            queue = m[1].replace(",", "").split()
            trig.append((t, "CHIRP", queue[0]))
            continue
        m = re.search(r"Ranging: chirp (\w) reported by", s)
        if m and m[1] in queue:
            i = queue.index(m[1])
            if i + 1 < len(queue): trig.append((t + 0.4, "CHIRP", queue[i + 1]))
    if "Hush window started" in s: trig.append((t, "HUSH", "-"))

print("%d CHIRP triggers (%s), %d HUSH" % (sum(1 for x in trig if x[1] == "CHIRP"), "logged" if explicit else "inferred", sum(1 for x in trig if x[1] == "HUSH")))
for S in "BC":
    recv = []
    for t, s in lines(S):
        m = re.search(r"Command received: (\w+)", s)
        if m: recv.append((t, m[1], (re.search(r"letter=(\w+)", s) or [None, "-"])[1]))
    lat = []
    for t, kind, L in trig:
        cands = [r[0] - t for r in recv if r[1] == kind and r[2] == L and -1.0 <= r[0] - t <= 25]
        if cands: lat.append((kind, L, min(cands, key=abs)))
    if lat:
        v = sorted(x[2] for x in lat)
        print("%s: %d commands matched, latency min %.2f median %.2f max %.2f s" % (S, len(v), v[0], v[len(v) // 2], v[-1]))
        print("   " + " ".join("%s%s=%.2f" % (k[0], L, x) for k, L, x in lat))
    else:
        print("%s: no matched commands" % S)
