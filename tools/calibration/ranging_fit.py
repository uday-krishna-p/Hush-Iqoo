"""Fits the two-way ranging model to tape-measured layouts (27 Sep).

Usage:  python ranging_fit.py <commander log> "<name>-<name>=<metres>,..." [<commander log> "<pairs>" ...]
e.g.    python ranging_fit.py line1/log_A.txt "ef39-991e=0.50,991e-6a46=1.00,ef39-6a46=1.50" \
                              line2/log_A.txt "ef39-991e=0.50,991e-6a46=2.00,ef39-6a46=2.50"

For every "RANGING result ... (heard={...})" line the raw pair sum S_ij = (t_i(j) - t_i(i)) - (t_j(j) - t_j(i))
(samples; clock offsets cancel) is compared with the tape: S_ij = 2 d_ij fs/c * scale - (delta_i + delta_j),
where delta_x is phone x's self-hearing delay (samples) relative to the sound leaving its speaker.
Least squares for the per-phone deltas and the scale, then residuals per pair and round. A good model leaves
residuals of a few samples; one delta per phone that does not fit across layouts means the self-hearing (or the
pick on a weak direct path) changes with the layout.
"""
import re, sys
import numpy as np

FS, C = 48000.0, 343.0
K = FS / C   # samples per metre

def rounds(path):
    names, out = {}, []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.search(r"Commander: I2501-(\w+) is Sensor (\w)", line)
        if m: names[m[2]] = m[1]
        if "Engine start role=COMMANDER" in line:
            names["A"] = re.search(r"name=I2501-(\w+)", line)[1]
        m = re.search(r"RANGING result:.*\(heard=\{(.*)\}\)", line)
        if m:
            heard = {}
            for h, body in re.findall(r"(\w)=\{([^}]*)\}", m[1]):
                heard[h] = {f: int(v) for f, v in re.findall(r"(\w)=(\d+)", body)}
            out.append((line[:12], {names.get(h, h): {names.get(f, f): v for f, v in d.items()} for h, d in heard.items()}))
    return out

rows, labels, phones = [], [], []
args = sys.argv[1:]
for i in range(0, len(args), 2):
    truth = {}
    for kv in args[i + 1].split(","):
        pair, v = kv.split("="); a, b = pair.split("-"); truth[frozenset((a, b))] = float(v)
    for t, heard in rounds(args[i]):
        for pair, d in truth.items():
            a, b = sorted(pair)
            try:
                s = (heard[a][b] - heard[a][a]) - (heard[b][b] - heard[b][a])
            except KeyError:
                continue
            for p in (a, b):
                if p not in phones: phones.append(p)
            rows.append((a, b, d, s)); labels.append("%s %s %s-%s tape %.2f m" % (args[i].split('/')[-2] if '/' in args[i] else args[i], t, a, b, d))

if not rows: sys.exit("no complete pairs found")
# unknowns: delta per phone, scale
A = np.zeros((len(rows), len(phones) + 1)); y = np.zeros(len(rows))
for r, (a, b, d, s) in enumerate(rows):
    A[r, phones.index(a)] = -1; A[r, phones.index(b)] = -1; A[r, -1] = 2 * d * K; y[r] = s
sol, *_ = np.linalg.lstsq(A, y, rcond=None)
print("scale %.3f (1.000 = sound speed right)" % sol[-1])
for p, v in zip(phones, sol[:-1]): print("  delta %-5s %+6.1f samples (%+.3f m)" % (p, v, v / K))
print("per round and pair: measured distance with these deltas vs tape")
for (a, b, d, s), lab in zip(rows, labels):
    est = (s + sol[phones.index(a)] + sol[phones.index(b)]) / (2 * K)
    print("  %-48s raw %.3f m  fitted %.3f m  residual %+5.1f samples" % (lab, s / (2 * K), est, s - (A[rows.index((a, b, d, s))] @ sol)))
# the same with scale fixed to 1
A1 = A[:, :-1]; y1 = y - 2 * K * np.array([r[2] for r in rows])
sol1, *_ = np.linalg.lstsq(A1, y1, rcond=None)
res = y1 - A1 @ sol1
print("scale fixed at 1: deltas " + ", ".join("%s %+.1f" % (p, v) for p, v in zip(phones, sol1)) + "; residual RMS %.1f samples (%.1f cm)" % (np.sqrt(np.mean(res ** 2)), 100 * np.sqrt(np.mean(res ** 2)) / K))
