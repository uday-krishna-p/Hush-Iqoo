# 📋 Product Requirements Document (PRD)

# Project: Hush — Offline Spatial Acoustic Sensing & Emergency Network
**Document Version**: 2.2 (Activation Notification Update)  
**Target Audience**: Product Management, Design, Engineering, and QA Teams  
**Product Status**: In Development (iQOO Hackathon 2026)  

---

## 🎯 1. Executive Summary & Key Architecture Update

### Low-Power Discovery & Victim Activation Protocol
To prevent battery drain while maximizing rescue chances for trapped victims:
* **Passive Listener State (Near-Zero Battery Draw)**:
  Victim phones do **NOT** broadcast heavily. `SensorService` holds an **ultra-low-power passive listening port** open in the background (using $<1\%$ battery per day).
* **On-Demand Commander Probe**:
  When a rescuer arrives at a disaster site and taps `[RADIO SWEEP]` / `[ACTIVATE SENSORS]`, the Commander's phone broadcasts an active **Probe/Wake Signal** across local offline P2P channels.
* **Instant Device Activation Notification & Haptic Pulse**:
  Upon receiving the probe signal, every awakened victim phone immediately:
  1. Triggers a high-priority heads-up system notification:  
     `🚨 RESCUE NETWORK ACTIVATED` — *"Rescuers are nearby! Your phone is active as a rescue sensor. Tap 3 times or shout if you can hear this."*
  2. Wakes up the screen (if possible) displaying a high-contrast visual rescue beacon badge.
  3. Triggers a distinct **haptic vibration pulse** so trapped victims feel their phone vibrating against their body/pocket, informing them help has arrived!

---

## 👥 2. User Personas & Target Use Cases

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ PERSONA A: Commander / Rescuer (Disaster Operations)                       │
│ • Goal: Find trapped victims under collapse rubble as fast as possible.      │
│ • Action: Taps [RADIO SWEEP] to send probe signal to wake up victim phones. │
├─────────────────────────────────────────────────────────────────────────────┤
│ PERSONA B: Everyday Household User (Single Phone)                           │
│ • Goal: Locate a mysterious noise in the house or track kitchen timers.     │
│ • Action: Uses Single-Mobile Walk-to-Triangulate and Whistle Counter.       │
├─────────────────────────────────────────────────────────────────────────────┤
│ PERSONA C: Hearing-Impaired / Elderly User                                  │
│ • Goal: Know when doorbells, knocks, or distress calls occur nearby.        │
│ • Action: Gets instant screen color flashes & distinct haptic vibrations.   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 📱 3. Core Feature Requirements

### Feature 2.1: Ultra-Low-Power Passive Listener & On-Demand Probe (Victim Discovery)
* **User Story**: *As a disaster rescuer, I want to send a probe signal from my Commander phone to wake up all passive victim phone listening ports under rubble and notify trapped victims that help is nearby.*
* **Functional Workflow**:
  1. Victim phones run `SensorService` in a **passive listening port state** (ultra-low-power sleep, near-zero battery impact).
  2. Commander taps `[RADIO SWEEP]` / `[ACTIVATE SENSORS]`.
  3. Commander broadcasts an active **Probe/Wake Signal** across local offline P2P channels.
  4. Victim phone ports detect the probe, wake up, and send a response payload containing their assigned ID and RSSI signal strength.
  5. **Victim Activation Notification**: Awakened victim phone posts a high-priority system notification (`"🚨 RESCUE NETWORK ACTIVATED: Rescuers are nearby!"`) and fires a distinct haptic vibration pulse to inform trapped victims.
  6. Commander calculates proximity distance ($d \approx 10^{\frac{\text{TxPower} - \text{RSSI}}{10 \cdot n}}$) and alerts the rescuer: `"VICTIM PHONE DISCOVERED: Sensor E (~3.5m away under rubble)"`.
* **Acceptance Criteria**:
  * Passive listener state consumes $<1\%$ battery per day on victim phones.
  * Commander probe signal wakes up 100% of listening victim phones within 15 meters in $<2\text{ seconds}$.
  * Posts high-priority local notification & triggers haptic vibration pulse on victim device.
  * Displays estimated proximity distance in meters on Commander screen.

#### Feature 2.2: Synchronized Hush Scan (20-Second Silence Window)
* **Functional Behavior**:
  1. Commander taps `[TRIGGER HUSH (20s)]`.
  2. Broadcasts `HUSH` command to all awakened sensor phones.
  3. All phones vibrate once and display a 20-second countdown.
  4. Phones measure background noise floor baselines and stream 1-second telemetry to Commander.

#### Feature 2.3: Spatial Loudness Ranking & Rescuer Brief
* **Functional Behavior**:
  1. Calculates rank score per phone: $\text{Rank} = \text{mean}\left(\text{5 best } \max(0, \text{RMS} - \text{Floor}) \times \text{HumanConfidence}\right)$.
  2. Highlights the closest sensor phone row in glowing gold/cyan.
  3. Synthesizes Rescuer Brief: `"Human tapping · 92% · strongest at Sensor B"`.

#### Feature 2.4: Interactive Tactical Radar Canvas & Direction Vector
* **Functional Behavior**:
  1. Canvas displays sensor pins (A, B, C...).
  2. Highlights target node with a glowing aura.
  3. Draws dynamic directional vector arrow pointing from Commander pin ('A') toward target victim node ('B').

---

## 🔒 4. Non-Functional & Battery Requirements

| Parameter | Specifications |
|---|---|
| **Victim Notification** | High-Priority System Notification + Screen Wake + Haptic Buzz upon Commander Probe |
| **Victim Battery Impact** | <1% battery per day in passive listener state |
| **Discovery Latency** | <2s upon Commander Probe broadcast |
| **Data Privacy** | Zero raw audio sent across network; only lightweight JSON telemetry |
