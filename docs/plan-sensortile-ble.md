# SensorTile.box PRO — BLE Integration Plan

**Branch:** `feature/sensortile-ble`
**Date:** 2026-04-03
**Goal:** Stream high-frequency IMU data (target ≥ 400 Hz) from a SensorTile.box PRO over BLE into the existing recording pipeline, time-aligned with the phone's own IMU stream.

---

## Context and Constraints

### Why this approach

The sensor-data-recorder app currently records from the phone's built-in IMU at ~200 Hz and supports multi-phone synchronization via Bluetooth Classic (RFCOMM). The SensorTile.box PRO is an external hardware IMU that exceeds the phone sensor's capabilities — it supports higher ODRs, better noise characteristics, and a wider sensor selection (LSM6DSV16X).

The goal is to add it as an additional data source that records in parallel with the phone IMU, with the same clock alignment mechanism already used for multi-phone sync.

### Why open-source components only

The organization has an internal OEM app (`sensortile-oem`) that also connects to a SensorTile using a proprietary internal firmware and custom BLE protocol. This integration deliberately does not depend on any of that code or firmware, so the sensor-data-recorder app can remain public. All dependencies are open source or standard Android APIs.

### Key open-source components

| Component | Source | Version |
|---|---|---|
| **FP-SNS-DATALOG2 firmware** | `STMicroelectronics/fp-sns-datalog2` (GitHub) | v3.2.0 |
| **BlueST SDK** (Android) | `STMicroelectronics/BlueSTSDK_Android` (GitHub Packages) | BlueST-SDK_V1.2.18 |
| **PnPL protocol** | Documented in DATALOG2 repo, open standard | — |

---

## Firmware Setup (one-time, before coding)

**Board:** STEVAL-MKBOXPRO (SensorTile.box PRO)

**Binary:**
```
fp-sns-datalog2/Projects/STM32U585AI-SensorTile.boxPro/Applications/DATALOG2/Binary/DATALOG2_Release.bin
```
Direct URL:
```
https://raw.githubusercontent.com/STMicroelectronics/fp-sns-datalog2/main/Projects/STM32U585AI-SensorTile.boxPro/Applications/DATALOG2/Binary/DATALOG2_Release.bin
```

**Flashing (no tools required):**
1. Hold the USER button while plugging in USB-C — the PRO mounts as a USB mass storage drive (`DIS_U585AI`)
2. Copy `DATALOG2_Release.bin` onto the drive root
3. Board reboots automatically and advertises over BLE as `SensorTile.box Pro`
4. Verify: open MEMS Studio → it should connect and show live IMU data at 416 Hz

This is the same firmware MEMS Studio uses, which is why MEMS Studio shows 410-420 Hz — this is the expected baseline.

---

## Architecture

### How SensorTile fits into the existing design

```
┌─────────────────────────────────────────────────────┐
│                    MainActivity                      │
│  (UI polling loop, 100ms, binds to all services)    │
└───────────┬──────────────────────────────┬──────────┘
            │                              │
            ▼                              ▼
┌───────────────────────┐    ┌─────────────────────────┐
│  SensorRecorderService│    │  BluetoothSyncService   │
│  (phone IMU @ ~200Hz) │    │  (RFCOMM, multi-phone   │
│  Accel/Gyro CSV files │    │   Controller/Worker)    │
└───────────────────────┘    └─────────────────────────┘

            +  NEW:

┌───────────────────────────────────────────────────────┐
│                 SensorTileService  (NEW)               │
│  BLE GATT client for SensorTile.box PRO                │
│  • Scans and connects                                   │
│  • Negotiates MTU + 2M PHY                             │
│  • Sends PnPL JSON to configure 416 Hz IMU             │
│  • Receives batched DATALOG2 stream packets             │
│  • Parses → timestamp_ns, x, y, z (same CSV format)   │
│  • Writes ST_Accelerometer.csv, ST_Gyroscope.csv       │
│  • Exposes clockOffset for post-processing alignment   │
└───────────────────────────────────────────────────────┘
```

`SensorTileService` is a standalone foreground service — it does not depend on `BluetoothSyncService` or `SensorRecorderService`. Recording start/stop is coordinated by `SensorRecorderService` sending a local broadcast or direct binder call, the same pattern already used between `MainActivity` and other services.

### Output files (per recording session)

```
<session_dir>/
  Accelerometer.csv          ← phone IMU (existing)
  Gyroscope.csv              ← phone IMU (existing)
  Annotation.csv             ← sync taps (existing)
  Metadata.json              ← epoch offsets (existing)
  ST_Accelerometer.csv       ← SensorTile IMU (new)
  ST_Gyroscope.csv           ← SensorTile IMU (new)
  ST_Metadata.json           ← ST clock offset + firmware info (new)
```

CSV format is identical to the existing phone sensor format:
```csv
timestamp_ns,x,y,z
783012345678900,0.0234,-9.7891,0.1456
```
`timestamp_ns` is the SensorTile's device counter mapped to the phone's `elapsedRealtimeNanos` domain via the clock offset (same approach as multi-phone sync).

---

## Implementation Phases

### Phase 1 — SDK dependency setup

**Goal:** Get `st_blue_sdk` available as a Gradle dependency.

The BlueST SDK is published to GitHub Packages (not Maven Central), so it requires GitHub authentication in `gradle.properties`:

```properties
# gradle.properties (local, never commit)
GPR_USER=<github_username>
GPR_API_KEY=<github_personal_access_token_with_read:packages>
```

`build.gradle.kts` (app module):
```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/STMicroelectronics/BlueSTSDK_Android")
        credentials {
            username = providers.gradleProperty("GPR_USER").orNull
            password = providers.gradleProperty("GPR_API_KEY").orNull
        }
    }
}

dependencies {
    implementation("com.st.blue.sdk:st-blue-sdk:1.2.18")
}
```

Add `gradle.properties` to `.gitignore` if not already there.

**Alternative (no GitHub auth):** Clone `BlueSTSDK_Android`, run `publishToMavenLocal`, and depend on the local Maven artifact. More setup but no credentials required in CI.

**Decision point:** If the PnPL parsing in the SDK is sufficient for DATALOG2 packets, use the SDK. If the SDK adds too much overhead or the packet format needs custom parsing, implement raw GATT instead (the UUIDs and packet format are documented in the DATALOG2 repo). Start with the SDK.

---

### Phase 2 — BLE scanning and connection (`SensorTileService.kt`)

**New file:** `sync/SensorTileService.kt`

Responsibilities:
- Foreground service (reuses the same `BtSyncChannel` notification channel pattern)
- Scan for `SensorTile.box Pro` by device name or BlueST service UUID
- Connect via `BluetoothGatt`
- On `onServicesDiscovered`: request MTU 247, then request 2M PHY (API 26+)
- On MTU confirmed: send PnPL configuration command (Phase 3)
- Expose `connectionState: SensorTileState` (IDLE, SCANNING, CONNECTING, CONFIGURING, STREAMING, ERROR) to UI via `@Volatile` field, polled by `MainActivity` at 100ms like other services

**BLE permissions needed (already in manifest from existing BT work):**
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE` (not needed, but already requested)

**BlueST SDK device discovery** uses `BlueManager` + `BleScanCallback`. The SDK handles the service UUID filtering automatically for DATALOG2 firmware.

---

### Phase 3 — PnPL configuration

DATALOG2 firmware exposes a **PnPL (Plug and Play Like)** characteristic — a JSON-over-BLE protocol for device configuration. Commands are sent as a **queue** (not fire-and-forget) because the firmware may respond asynchronously; the SDK handles the 10-second timeout between commands.

**Reference:** `HighSpeedDataLogViewModel.kt` and `AIoTCraftHighSpeedDataLogViewModel.kt` in `Android_App_STBLESensors/st_high_speed_data_log/`.

**Command sequence on connect:**

1. **Get device status** — discover what sensors are present and their current config:
   ```
   PnPLCmd(command="get_status", request="*")
   ```

2. **Set time** — synchronise device RTC to phone wall clock (sent before every log session):
   ```
   PnPLCmd(component="log_controller", command="set_time",
           fields={"datetime": "20260403_15_45_30"})   // yyyyMMdd_HH_mm_ss
   ```

3. **Configure sensors** — sent as individual set commands per property:
   ```
   PnPLCmd(command="acc_0",  fields={"enable": true, "odr": 416.0, "fs": 4.0})
   PnPLCmd(command="gyro_0", fields={"enable": true, "odr": 416.0, "fs": 2000.0})
   ```
   All other sensors (IIS3DWB, pressure, temperature) are disabled with `{"enable": false}`.

4. **Enable BLE stream** — this is what switches the firmware from SD-card-only to BLE streaming. Required separately from `start_log`:
   ```
   PnPLCmd(command="acc_0",  request="st_ble_stream", fields={"acc_0":  {"enable": true}})
   PnPLCmd(command="gyro_0", request="st_ble_stream", fields={"gyro_0": {"enable": true}})
   ```

5. **Start log:**
   ```
   PnPLCmd.START_LOG   // log_controller.start_log
   ```

6. **Stop log** (on recording stop):
   ```
   PnPLCmd.STOP_LOG    // log_controller.stop_log
   ```
   Followed by disabling the BLE stream for each sensor.

**Max write length** is determined dynamically from the node's catalog info for the PnPL characteristic (capped at `node.maxPayloadSize`). Long JSON is chunked automatically by the SDK.

---

### Phase 4 — Data streaming and packet parsing

DATALOG2 streams batched IMU samples via the SDK's `RawControlled` feature characteristic (not a raw UUID — the SDK resolves this by name from the catalog).

**Reference:** `RawPnplViewModel.kt` in `Android_App_STBLESensors/st_raw_pnpl/`, and `AIoTCraftHighSpeedDataLogViewModel.handleRawControlledUpdate()`.

**Packet structure** (decoded by `decodeRawData()` in BlueST SDK):
```
[1 byte: stream_id] [N × interleaved_sample_frames]
```
Each frame for LSM6DSV16X IMU:
```
[int16 x] [int16 y] [int16 z]    // accel or gyro, little-endian
```
Values are fixed-point. Scale factor comes from the `RawFormat.scale` field negotiated via PnPL `get_status` response at connect time — not hardcoded. For LSM6DSV16X at ±4g: ~0.122 mg/LSB; at ±2000 dps: ~70 mdps/LSB.

**Parse flow:**
1. `RawControlledInfo` arrives as BLE notification
2. `decodeRawData(payload, rawPnPLFormat)` → extracts `streamId` from first byte, looks up the matching `RawStreamIdEntry`
3. Found entry's `formats` list describes each enabled channel. For 3-axis sensors, `format.channels = 3`, data is interleaved: `[x1,y1,z1, x2,y2,z2, ...]`
4. Chunk by `format.channels` → each chunk is one sample
5. Apply `format.scale` (fixed-point) or `format.multiplyFactor` (float) to get physical units

**Important: no per-packet timestamps.** Raw packets contain only `stream_id` + samples. There is no device counter per frame. Timestamping is handled in Phase 5.

**Buffer-and-flush:** samples accumulate in `ConcurrentLinkedQueue`, flushed to `ST_Accelerometer.csv` / `ST_Gyroscope.csv` every 1 second (same pattern as `SensorRecorderService`). Each written sample is assigned a reconstructed timestamp (see Phase 5).

---

### Phase 5 — Timestamping and clock alignment

**The problem is different from multi-phone sync.** The DATALOG2 `RawControlled` stream carries no per-sample timestamps — packets are just `stream_id + samples`. The ST app handles this by recording one epoch offset at log start and reconstructing timestamps from sample count × ODR period. We do the same.

**Approach: sample-count reconstruction + epoch anchor**

At the moment `start_log` is acknowledged:
1. Record `tStart = SystemClock.elapsedRealtimeNanos()` on the phone
2. Record `epochOffset = System.currentTimeMillis() * 1_000_000L - tStart` (wall clock → boot time mapping, same as `SensorRecorderService`)
3. Maintain a running `sampleIndex` counter per sensor, incremented on every parsed sample

Each sample's timestamp:
```kotlin
val timestampNs = tStart + (sampleIndex * 1_000_000_000L / ODR_HZ)
```

This gives monotonically increasing nanosecond timestamps in the phone's `elapsedRealtimeNanos` domain — directly comparable to the phone IMU CSV without any additional sync step.

**Accuracy:** depends on how tightly `tStart` is captured relative to when the board actually starts streaming, and on ODR jitter. At 416 Hz the per-sample period is ~2.4ms. Drift over a 60-second recording at typical crystal accuracy (±20ppm) is ~1.2ms — acceptable for motion analysis. For tighter alignment, a periodic re-anchor can be added later.

**Why not GATT NTP?** The `RawControlled` stream has no device counter to echo back, so there's nothing to do round-trip timing against. The `set_time` PnPL command (Phase 3, step 2) serves a similar purpose: it anchors the device's RTC to phone wall time, which is how the ST app handles it.

Store result in `ST_Metadata.json`:
```json
{
  "firmware": "FP-SNS-DATALOG2",
  "sensor": "LSM6DSV16X",
  "odr_hz": 416,
  "recording_start_elapsed_ns": 1234567890123,
  "epoch_offset_ns": 456789012345678
}
```

---

### Phase 6 — Recording pipeline integration

**Start recording:** `MainActivity` already sends `ACTION_START` to `SensorRecorderService`. Add a parallel call to `SensorTileService` if it's connected and streaming. Recording only starts on the SensorTile if a connection is active — it degrades gracefully if the board is absent.

**Stop recording:** same pattern — stop both services, wait for final flush. The SensorTile service sends a PnPL stop command:
```json
{ "log_controller": { "stop_log": {} } }
```

**Export:** `ExportUtils` already ZIPs everything in the session directory. The new CSV files will be included automatically since they're in the same directory.

---

### Phase 7 — UI changes

Minimal changes to keep scope focused:

1. **New card in `RecordingScreen`:** "SensorTile" status card (below the BT sync card) showing connection state, current Hz, sample count. Same polling pattern as existing BT status.

2. **Scan/connect button:** Tapping it triggers BLE scan for `SensorTile.box Pro`. Once found, auto-connects and runs the configuration + clock sync sequence.

3. **Status states displayed:**
   - `○ Not connected` → `⟳ Scanning...` → `⟳ Configuring...` → `⟳ Syncing clocks...` → `● Ready (416 Hz)` → `● Recording`

No role system needed — the SensorTile is always a peripheral, never a peer.

---

## What is NOT in scope for this branch

- Multi-SensorTile support (one board at a time for now)
- Configuring sensors other than LSM6DSV16X accel + gyro
- SD card logging on the SensorTile (BLE streaming only)
- Real-time data visualization from the SensorTile
- Merging / interleaving the SensorTile and phone CSV streams (post-processing concern)

---

## Open questions to resolve during implementation

1. **BlueST SDK vs raw GATT:** Try SDK first. If the `RawPnPL` feature parsing doesn't map cleanly to 416Hz batched packets, fall back to raw GATT with the packet format from the DATALOG2 source.

2. **GitHub Packages auth in CI:** The GPR credentials requirement is awkward for a public repo. Evaluate whether publishing the SDK to local Maven and committing the AAR is acceptable, or if there's a Maven Central mirror.

3. **Clock sync precision over GATT:** GATT round-trip is asymmetric and variable. With 20 rounds, expect ±1-5ms accuracy — acceptable for most motion analysis. Validate empirically after flashing.

4. **Android BLE connection interval floor:** `CONNECTION_PRIORITY_HIGH` requests 11.25ms but the actual negotiated interval is device-dependent. Measure real notification rate on the target test device (Pixel 8 / 8a) after connecting.

5. **SensorTile not present at recording start:** Decide behavior — warn and proceed with phone-only, or block start. Currently leaning toward warn-and-proceed (SensorTile is optional).
