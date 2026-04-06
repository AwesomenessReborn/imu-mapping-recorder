# IMU Mapping Recorder

Research recorder for a multi-device IMU mapping experiment. Records synchronized accelerometer and gyroscope data from two Android phones and a SensorTile.box PRO hardware IMU, then aligns the signals in post-processing to build a function that maps IMU data from one body location to another.

> **General-purpose variant:** For a multi-Android-phone recorder without SensorTile support, see [sensor-data-recorder](https://github.com/AwesomenessReborn/sensor-data-recorder).

---

## Hardware

| Device | Role | IMU |
|---|---|---|
| Pixel 8 (Phone A) | Controller | STMicro LSM6DSO |
| Pixel 8a (Phone B) | Worker | STMicro LSM6DSO |
| SensorTile.box PRO | Peripheral | LSM6DSV16X @ 480Hz |

---

## What It Does

- Records IMU data from two phones at ~200Hz using Android's `SENSOR_DELAY_FASTEST`
- Synchronizes phone clocks via Bluetooth Classic (ping-pong, ±1–5ms accuracy)
- Coordinates start/stop across phones with sub-millisecond precision
- SensorTile records to SD card at 480Hz (DATALOG2 firmware); aligned in post-processing via tap cross-correlation
- Exports ZIP archives (CSV + metadata) for each phone session

---

## Recording Workflow

See `CLAUDE.md` for the full per-session procedure. Short version:

1. Set roles (Controller / Worker) on each phone
2. Connect phones via BT — clock sync runs automatically (~1 second)
3. Sharp table tap → start recording → another sharp table tap → stop
4. Export ZIPs from both phones + copy SensorTile SD card data
5. Run alignment pipeline (`gait-research/projects/imu-mapping/align_and_verify.py`)

---

## Implementation Status

| Feature | Status |
|---|---|
| Phone IMU recording | ✅ Done |
| Multi-device BT Classic sync | ✅ Done |
| Coordinated start/stop | ✅ Done |
| Export (ZIP via share sheet) | ✅ Done |
| SensorTileService (BLE connect + PnPL control) | 🔜 Next — see `docs/plan-sensortile-ble.md` |

---

## Output Format

```
<session_dir>/
  Accelerometer.csv     # timestamp_ns, x, y, z
  Gyroscope.csv
  Annotation.csv        # sync tap markers
  Metadata.json         # epoch offset + bt_clock_offsets per peer
```

SensorTile SD card output: DATALOG2 format (HSD2 or CSV depending on firmware config).

---

## Requirements

- Android 10+ (Min SDK 29)
- Bluetooth Classic paired between phones before first use
- SensorTile.box PRO with DATALOG2 v3.2.0 firmware flashed

See `CLAUDE.md` for flashing instructions.
