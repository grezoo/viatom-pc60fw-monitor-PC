# Viatom PC-60FW Bedside ICU & Overnight Sleep Analysis Monitor

[![Python 3.10+](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/Backend-FastAPI-009688.svg)](https://fastapi.tiangolo.com/)
[![WebSockets](https://img.shields.io/badge/Streaming-WebSockets-orange.svg)](https://websockets.readthedocs.io/)
[![Bleak BLE](https://img.shields.io/badge/Bluetooth-Bleak%20WinRT-darkblue.svg)](https://github.com/hbldh/bleak)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%20%2F%2011-0078D6.svg)](https://microsoft.com)
[![Revolut Donate](https://img.shields.io/badge/Donate-Revolut%20%40grezoo-0075eb.svg)](https://revolut.me/grezoo)

A hospital-grade, real-time bedside telemetry workstation, overnight sleep apnea analyzer, and peripheral circulation monitor designed for the **Viatom / Lepu PC-60FW** wireless Bluetooth pulse oximeter.

---

## 📸 Key Features

### 1. 🫀 60 FPS Photoplethysmogram (PPG) Oscilloscope with Dynamic AGC
- Ultra-smooth, canvas-rendered clinical plethysmogram waveform updated at **60 frames per second**.
- **Dynamic Automatic Gain Control (AGC)** with continuous peak-to-peak tracking and 35% adaptive headroom/footroom margins. Eliminates digital flatlining, waveform clipping, and saturated plateaus during motion or perfusion shifts.
- Distinct visualization of the systolic peak, dicrotic notch, and diastolic runoff.

### 2. 🔍 3-Cycle Clinical Morphological & Arrhythmia Magnifier HUD
- Continuously buffers optical pulse cycles and computes rolling baseline intervals.
- Detects irregular beats, extrasystolic pauses, bigeminy patterns, and sudden RR variability (atrial fibrillation suspicion).
- **12-Second Interactive Freeze Latch**: Automatically latches and magnifies the 3 offending cardiac cycles upon detection, displaying an amber countdown overlay while the real-time monitor continues running underneath.
- **Permanent Event History**: Logs every detected anomaly to a timestamped clinical event table with cycle duration and delta variance.

### 3. 🌙 Overnight Sleep Apnea Analyzer (AASM Criteria)
- Implements clinical **Oxygen Desaturation Index (ODI)** algorithms according to American Academy of Sleep Medicine (AASM) standards:
  - $\ge 3\%$ relative desaturation drop from rolling baseline, or
  - Absolute blood oxygenation $\text{SpO}_2 < 91\%$.
- Tracks cumulative hypoxemia duration, nadir $\text{SpO}_2$, and total apnea episodes.
- High-resolution master overnight timeline with cyan desaturation episode shading.

### 4. ✋ Peripheral Hypoperfusion & Numbness Alert ($\text{PI} < 0.8\%$)
- Specifically tuned to monitor peripheral vascular compression, carpal tunnel syndrome (CTS), thoracic outlet syndrome, Raynaud's phenomenon, and sleep-induced limb numbness.
- High-sensitivity acoustic warning system synthesized via Web Audio API:
  - Soft harmonic pulse tone synchronized with heartbeats.
  - Urgent pulsed alarms during hypoperfusion ($\text{PI} < 0.8\%$).
  - 980 Hz continuous acoustic flatline alarm upon optical signal loss / asystole.

### 5. ⚡ Sub-Second Bluetooth Fast Reconnect Engine
- Automatically caches the device's Bluetooth MAC address to local disk (`device_cache.json`).
- Reconnection takes **$< 300\text{ ms}$** without requiring repetitive, battery-draining Windows BLE discovery scans.
- Built-in Windows WinRT radio controller enforces adapter state (`RadioState.ON`).

### 6. 🚀 Native Zero-Install Windows Executable (`Viatom_Monitor.exe`)
- 25 KB native Windows GUI launcher written in C# (`/target:winexe`).
- Automatically starts the backend server in background without noisy DOS/cmd command prompt windows.
- Auto-opens your default browser to `http://localhost:8000`.
- Zero temp-directory extraction overhead; launches in under a second.

---

## 🏛 Architecture

```text
[ Viatom PC-60FW Pulse Oximeter ]
              │ (Nordic UART Service BLE)
              ▼
[ Python Bleak / WinRT Radio Subsystem ]
              │
[ FastAPI Asynchronous Core (app.py) ]
       │                         │
       ├─► [ CSV Logger ]        ├─► [ WebSocket Telemetry Broadcaster ]
       │   (meresi_naplo.csv)    │   (ws://localhost:8000/ws)
                                 │
                                 ▼
                     [ HTML5 / Canvas Bedside UI ]
                     - 60 FPS PPG Waveform (Dynamic AGC)
                     - Arrhythmia Magnifier HUD (3-cycle freeze)
                     - Sleep Apnea ODI / Desaturation Engine
                     - Web Audio Medical Synthesizers
```

---

## 🚀 Quick Start

### Option A: Portable Standalone Executable (Recommended)
1. Clone or download this repository.
2. Ensure you have the `runtime/` folder or a system Python installation with requirements installed.
3. Double-click **`Viatom_Monitor.exe`**.
4. The monitor will initialize the Bluetooth bridge and open your browser automatically at `http://localhost:8000`.

### Option B: Running from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/grezoo/viatom-pc60fw-monitor-PC.git
   cd viatom-pc60fw-monitor-PC
   ```
2. Install Python dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Run the application:
   ```bash
   python app.py
   ```
4. Open `http://localhost:8000` in Google Chrome, Microsoft Edge, or Firefox.

---

## 📊 Telemetry Data & Logging

Measurements are logged with millisecond timestamps to `meresi_naplo.csv`:

| Oszlop | Leírás |
| :--- | :--- |
| `Időbélyeg` | Helyi időbélyeg (`YYYY-MM-DD HH:MM:SS`) |
| `SpO2 (%)` | Artériás véroxigén-szaturáció ($0 - 100\%$) |
| `Pulzus (bpm)`| Percenkénti pulzusszám |
| `PI (%)` | Perfúziós Index ($0.1 - 20.0\%$) |
| `Állapot` | Klinikai állapot: `NORMÁL`, `ZSIBBADÁS (PI < 0.8%)`, `APNOE GYERMEK/FELNŐTT`, `LEVÉVE` |

---

## ☕ Support / Donate

Ha hasznosnak találod a projektet vagy segített az alvási apnoé / zsibbadás mérésében, meghívhatsz egy kávéra:

[![Revolut](https://img.shields.io/badge/Revolut-@grezoo-0075eb?style=for-the-badge&logo=revolut&logoColor=white)](https://revolut.me/grezoo)

**Revolut tag:** `@grezoo`  
**Közvetlen link:** [revolut.me/grezoo](https://revolut.me/grezoo)

---

## ⚠️ Disclaimer
Ez a szoftver és hardverintegráció mérnöki, kísérleti és életmód-monitorozási célt szolgál. Nem minősül hivatalos orvosi diagnosztikai műszernek. Egészségügyi panaszok, szívritmuszavar vagy tartós alvási apnoé gyanúja esetén mindig forduljon szakorvoshoz!
