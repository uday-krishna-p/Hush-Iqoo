"""Writes ultrasweep.wav (48 kHz, 16-bit mono): 1 s silence, a 12->23.5 kHz linear sweep (2 s), then 0.5 s steady
tones at 15..23 kHz with 20 ms fades and 0.25 s gaps. Played by a phone to measure how much of the top band the
speaker and microphones pass (the gate for inaudible chirps)."""
import numpy as np, wave, sys
fs = 48000
def fade(x, ms=20):
    n = int(fs * ms / 1000); w = np.ones(len(x)); r = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, n)); w[:n] = r; w[-n:] = r[::-1]; return x * w
parts = [np.zeros(fs)]
t = np.arange(2 * fs) / fs
f0, f1 = 12000, 23500
parts.append(fade(np.sin(2 * np.pi * (f0 * t + (f1 - f0) / (2 * 2.0) * t ** 2))))
parts.append(np.zeros(fs // 2))
TONES = [15000, 17000, 18000, 19000, 20000, 21000, 22000, 23000]
for f in TONES:
    tt = np.arange(fs // 2) / fs
    parts.append(fade(np.sin(2 * np.pi * f * tt))); parts.append(np.zeros(fs // 4))
x = np.concatenate(parts) * 0.7
out = sys.argv[1] if len(sys.argv) > 1 else "ultrasweep.wav"
with wave.open(out, "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(fs); w.writeframes((x * 32767).astype("<i2").tobytes())
print(out, len(x) / fs, "s")
