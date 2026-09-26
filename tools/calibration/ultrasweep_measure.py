"""Measures an ultrasweep.wav playback in a phone's debug.wav (48 kHz stereo 16-bit, header ignored).
usage: ultrasweep_measure.py debug.wav <play start sample on the PLAYER's clock or -1 to search> [label]
For each steady tone: level at that frequency in its 0.5 s slot vs. the same band in the silence before (SNR),
per microphone. Also the sweep's level per 1 kHz band. With -1 the start is found from the 20 kHz... no: the
strongest broadband onset of the sweep (energy above 11 kHz)."""
import numpy as np, sys
path = sys.argv[1]; start = int(sys.argv[2]); label = sys.argv[3] if len(sys.argv) > 3 else path
raw = np.fromfile(path, dtype="<i2")[22:]
raw = raw[: len(raw) // 2 * 2].reshape(-1, 2).astype(np.float64) / 32768.0
fs = 48000
def band_level(x, f, bw=200):
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ; fr = np.fft.rfftfreq(len(x), 1 / fs)
    m = (fr > f - bw) & (fr < f + bw); return 20 * np.log10(np.sqrt(np.mean(X[m] ** 2)) + 1e-12)
if start < 0:
    # find the sweep: high-passed energy (> 11 kHz) in 50 ms frames, first frame 20 dB above the median
    hp = raw[:, 0] - np.convolve(raw[:, 0], np.ones(4) / 4, "same")
    fr = 2400; e = np.array([np.sum(hp[i:i + fr] ** 2) for i in range(0, len(hp) - fr, fr)])
    idx = np.argmax(e > np.median(e) * 100); start = idx * fr - fs   # the file starts with 1 s silence
    print("found sweep near sample", idx * fr)
TONES = [15000, 17000, 18000, 19000, 20000, 21000, 22000, 23000]
off = fs + 2 * fs + fs // 2
print(label, "(tone: dB in slot / dB in silence / SNR) mic0 | mic1")
for i, f in enumerate(TONES):
    a = start + off + i * (fs // 2 + fs // 4) + fs // 20; b = a + fs // 2 - fs // 10
    q0 = start + fs // 10; q1 = q0 + (b - a)
    row = []
    for ch in (0, 1):
        s = band_level(raw[a:b, ch], f); n = band_level(raw[q0:q1, ch], f); row.append("%6.1f %6.1f %5.1f" % (s, n, s - n))
    print("%5d Hz  %s | %s" % (f, row[0], row[1]))
