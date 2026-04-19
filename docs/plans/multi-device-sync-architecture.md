# Multi-Device Sync: Bluetooth Controller/Worker Architecture

## Problem Statement

Two phones record IMU data independently. Their internal clocks have different boot times and accumulate drift over time. Post-processing needs to know the precise offset between their clocks to align signals. Currently this is solved via manual sync taps + cross-correlation — a fragile process that requires the researcher to press two buttons at the same time.

**Goal:** Replace manual coordination with a Bluetooth-based system where:
1. The phones measure their clock offset once before recording begins
2. The Controller phone can trigger start/stop/sync on all Workers over Bluetooth
3. Post-processing uses the pre-measured offset instead of (or to validate) cross-correlation

---

## Why Bluetooth (not Wi-Fi/UDP)

- Recording happens outdoors and in variable locations — no guaranteed Wi-Fi
- Bluetooth Classic has ~10–30m range, works anywhere, phone-to-phone
- One-time OS-level pairing (done once in Android Settings), then the app connects automatically
- Connection-oriented (unlike UDP) — dropped commands are detected and retried
- `BluetoothSocket` with RFCOMM is the Android equivalent of a serial cable: simple, reliable, ordered

---

## Architecture

### Two-Phase Design

**Phase 1 — Clock Sync (before recording, ~2 seconds)**
```
Controller                        Worker
    │                                │
    │──── connect BluetoothSocket ──►│
    │                                │
    │  repeat 10×:                   │
    │──── PING { t_send_ns } ───────►│
    │◄─── PONG { t_send_ns,          │
    │           t_recv_ns,           │
    │           t_reply_ns }  ───────│
    │                                │
    │  compute offset estimate       │
    │  save to Metadata.json         │
    │  connection stays open         │
```

**Phase 2 — Recording (connection stays open)**
```
Controller                        Worker
    │                                │
    │──── CMD_START ────────────────►│  → Worker calls SensorRecorderService.startRecording()
    │  [Controller also starts]      │
    │                                │
    │──── CMD_SYNC_TAP ─────────────►│  → Worker calls SensorRecorderService.logSyncTap()
    │  [Controller also marks]       │
    │                                │
    │──── CMD_STOP ─────────────────►│  → Worker calls SensorRecorderService.stopRecording()
    │  [Controller also stops]       │
```

### Component Separation

Keep Bluetooth sync in a **separate service** (`BluetoothSyncService`) from `SensorRecorderService`. Reasons:
- Different lifecycle: sync service is only active during pairing + recording; sensor service is always alive for live preview
- Different failure modes: BT disconnects should not crash sensor recording
- Easier to test in isolation
- `SensorRecorderService` already has clean `startRecording()` / `stopRecording()` / `logSyncTap()` public methods — `BluetoothSyncService` just calls them

---

## Clock Offset Math

### How to measure offset

Each phone's clock is `SystemClock.elapsedRealtimeNanos()` — nanoseconds since boot, monotonic.

The Controller sends a PING with its current timestamp. The Worker receives it, notes the receive time, sends back a PONG with:
- `t_controller_send`: timestamp from the PING
- `t_worker_recv`: Worker's clock when it received the PING
- `t_worker_reply`: Worker's clock when sending PONG

The Controller notes `t_controller_recv` when it gets the PONG.

```
network_delay = (t_controller_recv - t_controller_send) / 2   // assume symmetric
clock_offset  = t_worker_recv - t_controller_send - network_delay
              = t_worker_recv - (t_controller_send + t_controller_recv) / 2
```

Run this 10 times, discard outliers (highest/lowest), average the rest. Typical LAN Bluetooth RTT is 5–20ms, so offset precision should be ±5ms — good enough for 208Hz (4.8ms sample period).

### Where offset is stored

Added to each device's `Metadata.json`:
```json
{
  "recording_start_epoch_ms": 1234567890123,
  "epoch_offset_ns": 456789012345678,
  "accel_sample_count": 12000,
  "gyro_sample_count": 12000,
  "bt_clock_offset_ns": 3200000,
  "bt_clock_offset_samples": 10,
  "bt_offset_std_dev_ns": 450000,
  "bt_peer_device": "Pixel 8a"
}
```

- `bt_clock_offset_ns`: Controller clock minus Worker clock (signed). Positive means Controller is ahead.
- `bt_clock_offset_samples`: number of ping/pong rounds used
- `bt_offset_std_dev_ns`: standard deviation across samples (quality indicator)
- `bt_peer_device`: device name of the paired phone (for debugging)

Post-processing: instead of searching for offset via cross-correlation, use `bt_clock_offset_ns` directly as the initial alignment value. Cross-correlation can still be run as a validation check.

---

## Message Protocol

All messages are newline-delimited JSON strings sent over a `BluetoothSocket` RFCOMM channel.

```json
// Controller → Worker
{ "type": "PING", "t_ns": 1234567890 }
{ "type": "CMD_START", "session_id": "abc123" }
{ "type": "CMD_STOP",  "session_id": "abc123" }
{ "type": "CMD_SYNC_TAP", "session_id": "abc123" }

// Worker → Controller
{ "type": "PONG", "t_send_ns": 1234567890, "t_recv_ns": 1234569100, "t_reply_ns": 1234569200 }
{ "type": "ACK",  "cmd": "CMD_START", "session_id": "abc123", "t_ns": 1234571000 }
{ "type": "STATUS", "recording": true, "accel_count": 1024, "gyro_count": 1023 }
```

- Session ID: random UUID per recording session. Worker ignores commands from unknown sessions.
- ACK: Worker sends back after acting on a command. Controller can display confirmation.
- Newline-delimited JSON is trivial to parse with a `BufferedReader` line loop.

---

## Files to Create

### New files

```
imu_mapping_recorder/app/src/main/java/com/example/imu_mapping_recorder/
└── sync/
    ├── DeviceRole.kt            // enum: STANDALONE, CONTROLLER, WORKER
    ├── SyncMessage.kt           // data classes + JSON serialization for all message types
    ├── ClockOffsetEstimator.kt  // ping/pong math, outlier rejection, average
    └── BluetoothSyncService.kt  // foreground service: BT server/client, clock sync, command dispatch
```

### Files to modify

```
SensorRecorderService.kt    // add bt_clock_offset_ns fields + write to Metadata.json
MainActivity.kt             // add role picker UI + BT device selector + sync status display
AndroidManifest.xml         // add BT permissions + register BluetoothSyncService
```

---

## Detailed Implementation Steps

### Step 1 — `DeviceRole.kt`

Simple enum, no logic:

```kotlin
enum class DeviceRole { STANDALONE, CONTROLLER, WORKER }
```

Stored in `SharedPreferences` so it persists across app restarts.

---

### Step 2 — `SyncMessage.kt`

Data classes for each message type. Use `org.json.JSONObject` (already available on Android, no new dependency needed):

```kotlin
sealed class SyncMessage {
    data class Ping(val tNs: Long) : SyncMessage()
    data class Pong(val tSendNs: Long, val tRecvNs: Long, val tReplyNs: Long) : SyncMessage()
    data class Command(val type: String, val sessionId: String) : SyncMessage()
    data class Ack(val cmd: String, val sessionId: String, val tNs: Long) : SyncMessage()

    fun toJson(): String { /* serialize to JSON string */ }

    companion object {
        fun fromJson(line: String): SyncMessage? { /* parse from JSON string */ }
    }
}
```

No external JSON library needed — `org.json` is part of the Android framework.

---

### Step 3 — `ClockOffsetEstimator.kt`

```kotlin
class ClockOffsetEstimator {
    data class Sample(val offsetNs: Long, val rttNs: Long)
    private val samples = mutableListOf<Sample>()

    // Call this after receiving each PONG
    fun addSample(tControllerSend: Long, tWorkerRecv: Long, tWorkerReply: Long, tControllerRecv: Long) {
        val rttNs = tControllerRecv - tControllerSend
        val offsetNs = tWorkerRecv - (tControllerSend + tControllerRecv) / 2
        samples.add(Sample(offsetNs, rttNs))
    }

    // Call after all samples collected
    fun estimate(): Result {
        // discard highest and lowest RTT samples (outlier rejection)
        // average remaining offsetNs
        // compute std dev
        // return Result(offsetNs, stdDevNs, sampleCount)
    }

    data class Result(val offsetNs: Long, val stdDevNs: Long, val sampleCount: Int)
}
```

---

### Step 4 — `BluetoothSyncService.kt`

This is the main new component. Runs as a foreground service (separate notification from `SensorRecorderService`).

**Controller path:**
1. `onCreate()`: get `BluetoothAdapter`, set role from SharedPreferences
2. On `ACTION_CONNECT`: open `BluetoothSocket` to selected device (UUID hardcoded), connect in background coroutine
3. Once connected: run clock sync loop (10 PING/PONG exchanges) via `ClockOffsetEstimator`
4. Store result in a `SharedPreferences`-backed field accessible to `SensorRecorderService`
5. On `ACTION_SEND_START/STOP/SYNC_TAP`: write command JSON to socket's `OutputStream`
6. Read incoming ACKs from `InputStream` in a background coroutine; update UI state

**Worker path:**
1. `onCreate()`: open `BluetoothServerSocket` with same UUID, listen for incoming connection in background coroutine
2. Once connected: read lines from `InputStream` in a loop
3. On PING: record recv time, send PONG immediately with timestamps
4. On CMD_START: call `SensorRecorderService.startRecording()` directly (bind to service)
5. On CMD_STOP / CMD_SYNC_TAP: similarly dispatch
6. Send ACK back to Controller after each command

**Shared UUID** (hardcode the same UUID in both builds):
```kotlin
val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
// This is the standard SPP (Serial Port Profile) UUID — works on all Android devices
```

**Binding to SensorRecorderService from BluetoothSyncService:**
Use a standard `ServiceConnection` — both services run in the same process, so binding is local and synchronous.

---

### Step 5 — Modify `SensorRecorderService.kt`

Add fields for BT clock offset (set by `BluetoothSyncService` before recording starts):

```kotlin
var btClockOffsetNs: Long = 0          // set externally by BluetoothSyncService
var btClockOffsetStdDevNs: Long = 0
var btClockOffsetSamples: Int = 0
var btPeerDevice: String = ""
```

Modify `writeMetadata()` to include these fields:

```json
{
  "recording_start_epoch_ms": ...,
  "epoch_offset_ns": ...,
  "accel_sample_count": ...,
  "gyro_sample_count": ...,
  "bt_clock_offset_ns": ...,
  "bt_clock_offset_samples": ...,
  "bt_offset_std_dev_ns": ...,
  "bt_peer_device": "..."
}
```

No other changes to `SensorRecorderService` — its public methods (`startRecording`, `stopRecording`, `logSyncTap`) are already the right interface.

---

### Step 6 — Modify `MainActivity.kt`

**New UI sections (shown before recording starts):**

1. **Role selector** — `SegmentedButton` row: `Standalone | Controller | Worker`
   - Persisted in `SharedPreferences`
   - When changed: restart `BluetoothSyncService` with new role

2. **Controller mode extras:**
   - "Select Device" button → opens `BluetoothDevicePickerDialog` (list of already-paired devices from `BluetoothAdapter.bondedDevices`)
   - Status chip: `● Connected to Pixel 8a` / `○ Not connected`
   - "Sync Clocks" button → triggers clock offset measurement, shows result: `Clock offset: 3.2ms ± 0.4ms`

3. **Worker mode extras:**
   - Status chip: `● Listening...` / `● Connected to Pixel 8` / `○ Disconnected`
   - No additional buttons needed — Worker responds to Controller

4. **During recording** — no UI changes. All roles show same recording UI. Controller's Start/Stop/Sync buttons send BT commands to Worker AND act locally.

**Permission handling in `MainActivity.kt`:**
- Request `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` at runtime (Android 12+)
- Use `registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`

---

### Step 7 — Modify `AndroidManifest.xml`

Add permissions:
```xml
<!-- Bluetooth Classic -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Already declared, keep them -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Register the new service:
```xml
<service
    android:name=".sync.BluetoothSyncService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

Note: `foregroundServiceType="connectedDevice"` is the correct type for Bluetooth connections on Android 14+.

---

## Pre-Pairing Requirement

Before first use, the two phones must be paired via Android OS Settings → Bluetooth → Pair new device. This is a one-time step per device pair. After pairing, the app connects automatically using `BluetoothAdapter.bondedDevices`.

**Do not attempt programmatic pairing discovery** — Android 12+ restricts scanning for new devices heavily. Since you always use the same two Pixel phones, pairing once in Settings is the right call.

---

## Implementation Order Summary

| Step | File | What it does |
|------|------|-------------|
| 1 | `sync/DeviceRole.kt` | Enum only |
| 2 | `sync/SyncMessage.kt` | Message data classes + JSON |
| 3 | `sync/ClockOffsetEstimator.kt` | Ping/pong math |
| 4 | `sync/BluetoothSyncService.kt` | Core BT service (Controller + Worker paths) |
| 5 | `SensorRecorderService.kt` | Add BT offset fields + update `writeMetadata()` |
| 6 | `MainActivity.kt` | Role picker UI + device selector + sync status |
| 7 | `AndroidManifest.xml` | BT permissions + register new service |

Steps 1–3 have no Android dependencies and can be written and unit-tested first. Step 4 is the largest. Steps 5–7 are small wiring changes.

---

## Open Questions for Implementer

1. **Should BluetoothSyncService auto-reconnect on drop?** Recommended yes — use an exponential backoff loop in a coroutine.
2. **What if clock sync is skipped (Standalone mode)?** `bt_clock_offset_ns` should be written as `null` or omitted from Metadata.json — post-processing falls back to cross-correlation.
3. **Should CMD_START on the Worker also trigger the foreground service promotion?** Yes — Worker's `BluetoothSyncService` should call `startForegroundService(Intent(ACTION_START))` on `SensorRecorderService`, not just `startRecording()` directly, to ensure foreground promotion works correctly.
4. **Minimum Android version for `BLUETOOTH_CONNECT`?** This permission is only required on API 31+. On API 29–30, the old `BLUETOOTH` permission suffices. The current `minSdk` is 29, so handle both code paths.
