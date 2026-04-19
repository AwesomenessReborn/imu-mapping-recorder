# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hardcoded research recorder for a specific IMU mapping experiment. Records accelerometer and gyroscope data from two Android phones and a SensorTile.box PRO simultaneously, then aligns the signals in post-processing to build a mapping function that correlates IMU data from one body location to another.

**This is not a general-purpose tool.** Hardware is fixed. Do not add generalization, configuration options, or support for other devices.

**Downstream goal:** An inference engine consumes aligned multi-device IMU data at 208Hz to perform location-to-location IMU signal mapping (e.g., wrist → ankle).

**Related repo:** [sensor-data-recorder](https://github.com/AwesomenessReborn/sensor-data-recorder) — the general-purpose multi-Android variant this project was forked from.

---

## Hardware (hardcoded — do not generalize)

| Device | Role | IMU | Notes |
|---|---|---|---|
| **Pixel 8** | Controller (Phone A) | STMicro LSM6DSO | Initiates BT sync, triggers start/stop |
| **Pixel 8a** | Worker (Phone B) | STMicro LSM6DSO | Receives commands from Controller |
| **SensorTile.box PRO** (STEVAL-MKBOXPRO) | Peripheral | LSM6DSV16X @ 480Hz | DATALOG2 v3.2.0 firmware, logs to SD card |

---

## Experiment Recording Workflow

This is the required procedure for every data collection session:

### Before First Use
1. Flash SensorTile with DATALOG2 v3.2.0 firmware (one-time, see Firmware section below)
2. Pair Phone A and Phone B in Android Bluetooth Settings

### Per-Session Procedure
1. **Set roles:** Open app on Phone A → set role **CONTROLLER**. Open app on Phone B → set role **WORKER**.
2. **Connect phones:** On Phone A, tap "Select Device" → pick Phone B. App runs 10-round clock sync automatically (~1 second). Expected accuracy: ±1–5ms.
3. **[Future] Connect SensorTile:** Phone A connects to SensorTile via BLE → sends PnPL config (480Hz, LSM6DSV16X accel + gyro, all others disabled).
4. **Sync tap:** Sharp table tap — visible on all IMU streams simultaneously. This is the pre-recording alignment anchor.
5. **Start recording:** Press START on Phone A (Controller). Phone B starts simultaneously. SensorTile starts SD card log (via PnPL `start_log` command over BLE, or manually via MEMS Studio until BLE control is implemented).
6. **Record:** Sessions ≤10 minutes (crystal drift limit: ~40–50ms raw, corrected to <3ms with tap anchors at both ends).
7. **Sync tap at end:** Another sharp table tap before stopping.
8. **Stop:** Press STOP on Phone A. Coordinated stop on both phones. Send PnPL `stop_log` to SensorTile.
9. **Export:**
   - Phone A: Share Recording → ZIP (Accelerometer.csv, Gyroscope.csv, Annotation.csv, Metadata.json)
   - Phone B: Share Recording → ZIP (same format)
   - SensorTile: Eject SD card, copy DATALOG2 output files
10. **Run alignment pipeline:** See `gait-research/projects/imu-mapping/align_and_verify.py`
    - Phone A ↔ Phone B: already aligned via `bt_clock_offsets` in Metadata.json
    - SensorTile ↔ Phone A: tap cross-correlation on acceleration magnitude (rotation-invariant)

---

## Clock Alignment Strategy

| Pair | Method | Expected Accuracy |
|---|---|---|
| Phone A ↔ Phone B | BT NTP ping-pong (10 rounds, `ClockOffsetEstimator`) | ±1–5ms |
| SensorTile ↔ Phone A | Post-hoc tap cross-correlation on accel magnitude | <3ms after linear drift correction |

SensorTile timestamps are reconstructed from sample index × ODR period, anchored to the `start_log` ACK time. Drift is bounded by ±10-minute segment limit and corrected linearly between the two tap anchors.

---

## Sync Tap Procedure

The **sync tap** is the primary alignment anchor between Phone A and the SensorTile.box PRO. Use it at the start and end of every recording session.

### What it does

1. You physically tap the phone and the SensorTile sharply together against a hard surface (table, desk).
2. This creates a simultaneous, sharp acceleration spike in **both** IMU streams at the same physical moment.
3. Immediately press **SYNC TAP ⚡** in the app.
4. The app writes a timestamped annotation entry to `Annotation.csv` with label `sync_tap`.

### Why two steps

The **physical tap** is the precision alignment anchor — the IMU spike is visible in both streams and can be cross-correlated to sub-millisecond accuracy. The **button press** gives you a coarse software timestamp (within ~500ms of the tap) so you know roughly where in the recording to look. In post-processing, you search for the IMU spike within a window around the software timestamp.

### Per-session procedure

1. Press **SYNC TAP ⚡** immediately after pressing **START RECORDING** (pre-recording anchor).
2. Do the actual recording session (≤10 minutes).
3. Press **SYNC TAP ⚡** immediately before pressing **STOP RECORDING** (post-recording anchor).

Two anchors per session allow linear drift correction over the full recording window. Expected final alignment accuracy: **<3ms** after drift correction.

### How it appears in output files

**Phone A `Annotation.csv`:**
```csv
timestamp_ns,label
783012345678900,sync_tap   ← pre-recording tap
983456789012300,sync_tap   ← post-recording tap
```

**SensorTile SD card:** contains the raw IMU spike(s) at the same physical moments. The alignment pipeline finds these by cross-correlating `sqrt(ax²+ay²+az²)` from both devices — magnitude is rotation-invariant so orientation doesn't matter.

**Post-processing:** see `gait-research/projects/imu-mapping/align_and_verify.py`.

---

## Implementation Status

| Feature | Status | Location |
|---|---|---|
| Phone IMU recording @ SENSOR_DELAY_FASTEST (~200Hz) | ✅ Done | `SensorRecorderService.kt` |
| Multi-device BT Classic sync (RFCOMM, Controller/Worker) | ✅ Done | `sync/BluetoothSyncService.kt` |
| Clock sync protocol (ping-pong, 10 rounds) | ✅ Done | `sync/ClockOffsetEstimator.kt` |
| Coordinated start/stop (CMD_START / CMD_STOP + ACK) | ✅ Done | `sync/BluetoothSyncService.kt` |
| Export via Android share sheet (ZIP) | ✅ Done | `ExportUtils.kt` |
| Sync tap annotation (button → `Annotation.csv`) | ✅ Done | `SensorRecorderService.kt` |
| SensorTileService — BLE scan + connect | ✅ Done | `sync/SensorTileService.kt` |
| SensorTileService — PnPL start/stop (coordinated with START/STOP button) | ✅ Done | `sync/SensorTileService.kt` |
| SensorTile status card in Controller UI | ✅ Done | `MainActivity.kt` → `SensorTileCard` |
| SensorTile BLE UUID verification (first run) | ⚠️ Verify | Check Timber logcat for `SERVICE:` / `CHAR:` lines on first connect |
| SD card fetch + alignment pipeline | 🔜 Separate | `gait-research/projects/imu-mapping/` |

---

## SensorTile Firmware

- **Board:** STEVAL-MKBOXPRO (SensorTile.box PRO)
- **Firmware:** FP-SNS-DATALOG2 v3.2.0
- **Config:** LSM6DSV16X accel + gyro only, ODR 480Hz, all other sensors disabled

**Flashing (CLI — MEMS Studio GUI has file-picker issue on macOS):**
```bash
/Applications/STMicroelectronics/STM32Cube/STM32CubeProgrammer/STM32CubeProgrammer.app/Contents/Resources/bin/STM32_Programmer_CLI \
  -c port=USB1 \
  -w "<path_to>/DATALOG2_Release.bin" \
  0x08000000 -v -rst
```
Binary location after unzip:
```
fp-sns-datalog2/STM32CubeFunctionPack_DATALOG2_V3.2.0/Projects/STM32U585AI-SensorTile.boxPro/Applications/DATALOG2/Binary/DATALOG2_Release.bin
```
The `-rst` error at the end is expected (DFU limitation) — unplug and replug manually.

---

## Build and Run

The project uses Gradle with Kotlin DSL. All commands run from the `imu_mapping_recorder/` directory:

```bash
cd imu_mapping_recorder

# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Clean build
./gradlew clean
```

---

## Architecture

### Core Components

**SensorRecorderService** (`SensorRecorderService.kt`):
- Foreground service, phone IMU recording at SENSOR_DELAY_FASTEST
- Buffers in `ConcurrentLinkedQueue`, flushes to disk every 1 second
- Maintains wake lock; tracks Hz via circular buffer of last 50 inter-sample intervals
- Records epoch offset: `System.currentTimeMillis() * 1e6 - SystemClock.elapsedRealtimeNanos()`
- Outputs: `Accelerometer.csv`, `Gyroscope.csv`, `Annotation.csv`, `Metadata.json`

**BluetoothSyncService** (`sync/BluetoothSyncService.kt`):
- Foreground service, RFCOMM over Bluetooth Classic
- Controller: connects to Workers, runs clock sync, sends CMD_START / CMD_STOP
- Worker: listens, auto-relistens on disconnect; ACKs commands, calls SensorRecorderService
- State machine: IDLE → CONNECTING → SYNCING → READY → RECORDING

**SensorTileService** (`sync/SensorTileService.kt`) — *not yet implemented:*
- BLE GATT client for SensorTile.box PRO
- Sends PnPL JSON commands (configure sensors, start/stop log)
- See `docs/plan-sensortile-ble.md` for the full 7-phase implementation plan

**MainActivity** (`MainActivity.kt`):
- Compose UI, binds to all services, polls state every 100ms

**ExportUtils** (`ExportUtils.kt`):
- Creates ZIP, shares via Android share sheet, cleans old ZIPs on start

### Output Format

**CSV Files** (`Accelerometer.csv`, `Gyroscope.csv`):
```csv
timestamp_ns,x,y,z
783012345678900,0.0234,-9.7891,0.1456
```

**Metadata.json:**
```json
{
  "recording_start_epoch_ms": 1234567890123,
  "epoch_offset_ns": 456789012345678,
  "accel_sample_count": 12000,
  "gyro_sample_count": 12000,
  "bt_clock_offsets": {
    "Pixel 8a": {"offset_ns": 123456, "std_dev_ns": 4321, "sample_count": 10}
  }
}
```

---

## Requirements

- Min SDK 29 (Android 10)
- Target SDK 36 (Android 15)
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`

## Docs

- `docs/plan-sensortile-ble.md` — 7-phase SensorTile BLE integration design (next implementation target)
