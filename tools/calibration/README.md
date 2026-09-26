# Calibration with a played knock signal (26 Sep, late evening)

Real door-knock recordings from ESC-50 (class door_wood_knock, CC BY-NC 3.0, fetched from
https://github.com/karolpiczak/ESC-50) are cut into single hits, high-passed at 700 Hz (a phone speaker
radiates nothing below ~600 Hz) and laid out on an exact schedule (`make_signal.py` → `knocks.wav`,
`schedule.json`: 8 s silence, 20 knocks at 1/s, 6 × a 3-2 pattern, 25 knocks at 2.5/s).

One phone plays it as a sensor (`adb shell am start -n com.hush/.MainActivity --es play knocks.wav --ef level 1.0`
after `adb push knocks.wav /data/local/tmp/ && adb shell run-as com.hush cp /data/local/tmp/knocks.wav files/`;
in Git Bash prefix adb with `MSYS_NO_PATHCONV=1`). Its `PLAY: started … at sample S` log line pins every scheduled
knock to its audio clock. Pull the three logs (commander = A) to `log_A.txt`, `log_B.txt`, `log_C.txt`
(file letter = which phone, not the Hush letter) and run `analyze.py` (detection rate, timing jitter, two-mic
delay, rhythm labels, locator) and `latency.py` (command latency per sensor, assumes the OLD 1.8 s chirp schedule;
update it for the serialised protocol).

`log_*.txt` here are from the run at 23:20 on 26 Sep (chirp 0.4, knocks at level 1.0). Findings are in CLAUDE.md.
