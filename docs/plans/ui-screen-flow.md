# Plan: UI Screen Flow Restructure

**Branch:** `feature/bluetooth-sync`
**File to modify:** `imu_mapping_recorder/app/src/main/java/com/example/imu_mapping_recorder/MainActivity.kt`
**No new files needed** — all changes are within MainActivity.kt (the app is 100% Jetpack Compose, single-file UI).

---

## Background

The app currently uses a **single-screen layout** where role selector, device picker dialog, BT status card, and recording controls are all stacked vertically at once. This was inspired by the STMicroelectronics SensorTile.box OEM app, which uses a **staged navigation flow**:

1. Bluetooth scan screen → user picks a device → connection happens → demo/control screen appears

The goal is to replicate this staged flow within the CONTROLLER and WORKER role tabs of this app, using the existing Bluetooth state machine (no service changes needed).

---

## Current State

`MainActivity.kt` contains one top-level composable, `RecorderScreen`, that renders everything on one screen. Key state variables:

- `deviceRole: DeviceRole` — STANDALONE | CONTROLLER | WORKER (persisted in SharedPrefs)
- `btState: BtConnectionState` — IDLE | CONNECTING | SYNCING | READY | RECORDING | ERROR (polled from `BluetoothSyncService` every 200ms)
- `showDevicePicker: Boolean` — controls an `AlertDialog` that lists bonded devices

The `BluetoothSyncService` (`sync/BluetoothSyncService.kt`) state machine already drives all connection phases — the UI just needs to render different content based on `(deviceRole, btState)`.

---

## Target Flow

```
App opens
    │
    ├─ STANDALONE ──────────────────────────────────────► RecordingScreen
    │
    ├─ CONTROLLER
    │       ├─ IDLE / ERROR ────────────────────────────► DevicePickerScreen
    │       ├─ CONNECTING / SYNCING ──────────────────► ConnectingScreen
    │       └─ READY / RECORDING ──────────────────────► RecordingScreen
    │
    └─ WORKER
            ├─ IDLE / CONNECTING / SYNCING ─────────────► ListenerWaitingScreen
            └─ READY / RECORDING ────────────────────────► RecordingScreen
```

---

## Implementation Steps

### Step 1 — Replace `RecorderScreen` with a top-level switcher

Change `RecorderScreen` from a single layout to a `when` expression that delegates to sub-composables:

```kotlin
@Composable
fun RecorderScreen(...) {
    // keep all existing state/LaunchedEffect blocks here (polling, countdown, etc.)

    when {
        deviceRole == DeviceRole.STANDALONE -> RecordingScreen(...)
        deviceRole == DeviceRole.CONTROLLER && btState in listOf(BtConnectionState.IDLE, BtConnectionState.ERROR) ->
            DevicePickerScreen(...)
        deviceRole == DeviceRole.CONTROLLER && btState in listOf(BtConnectionState.CONNECTING, BtConnectionState.SYNCING) ->
            ConnectingScreen(...)
        deviceRole == DeviceRole.CONTROLLER ->  // READY or RECORDING
            RecordingScreen(...)
        deviceRole == DeviceRole.WORKER && btState in listOf(BtConnectionState.IDLE, BtConnectionState.CONNECTING, BtConnectionState.SYNCING) ->
            ListenerWaitingScreen(...)
        else ->  // WORKER READY or RECORDING
            RecordingScreen(...)
    }
}
```

All state vars (`isRecording`, `btState`, `deviceRole`, `accelCount`, etc.) and `LaunchedEffect` blocks remain at the `RecorderScreen` level and are passed down as parameters.

---

### Step 2 — `DevicePickerScreen` composable (replaces the dialog)

This is the "scan screen" equivalent. Instead of a small `AlertDialog`, render a full screen that lists bonded (already-paired) devices. Note: RFCOMM requires devices to be paired first in Android Settings — this is equivalent to the ST app's scan list but filtered to already-paired devices.

**What to show:**
- Title: "Select Controller Target" or similar
- A role selector row at the top (STANDALONE / CONTROLLER / WORKER segmented buttons) — so the user can switch away
- A `LazyColumn` of bonded devices (device name + MAC address as subtext), each row tappable
- A helper text if no bonded devices found: "No paired devices. Pair in Android Settings first."
- If `btState == ERROR`: show the `errorMessage` from the service in a red card at the top, with a "Retry" hint

**On device tap:**
```kotlin
Intent(context, BluetoothSyncService::class.java).also {
    it.action = BluetoothSyncService.ACTION_CONNECT
    it.putExtra(BluetoothSyncService.EXTRA_DEVICE_ADDRESS, device.address)
    context.startForegroundService(it)
}
```
This is exactly the same logic currently inside the `AlertDialog` click handler (MainActivity.kt line 232–236). Just move it here.

**Remove:** `var showDevicePicker` state var and the `if (showDevicePicker) { AlertDialog(...) }` block (lines 170, 208–247).

---

### Step 3 — `ConnectingScreen` composable

Shown while `btState == CONNECTING` or `SYNCING`.

**What to show:**
- A `CircularProgressIndicator`
- Status text derived from `btState`:
  - CONNECTING → "Connecting to $peerDeviceName..."
  - SYNCING → "Syncing clocks with $peerDeviceName..."
- A "Cancel" button that fires `BluetoothSyncService.ACTION_DISCONNECT`

This replaces the "⟳ Connecting..." and "⟳ Syncing clocks..." text currently in `BluetoothStatusCard` (lines 502–503).

---

### Step 4 — `ListenerWaitingScreen` composable

Shown for WORKER when `btState` is IDLE, CONNECTING, or SYNCING.

**What to show:**
- A role selector at the top (so user can switch back to STANDALONE/CONTROLLER)
- A `CircularProgressIndicator`
- Text: "Waiting for controller..." (IDLE/CONNECTING) or "Syncing clocks..." (SYNCING)
- Subtext: "Make sure the controller device selects this phone."
- The `peerDeviceName` once a controller connects (during SYNCING)

**On entering WORKER role**, `ACTION_LISTEN` is already auto-fired (MainActivity.kt line 284–287) — no change needed.

---

### Step 5 — `RecordingScreen` composable

Extract the existing recording controls (currently lines 293–470) into a new composable. This is shown for all roles once `btState` is READY or RECORDING, and always for STANDALONE.

**Parameters it needs:**
- All sensor stats: `isRecording`, `durationSeconds`, `accelCount`, `gyroCount`, `accelRate`, `gyroRate`
- `deviceRole`, `btState`, `peerDeviceName`, `syncReport`
- `isCountingDown`, `countdownValue`, `startDelaySeconds`
- `lastRecordingDir`, `isExporting`
- Callbacks: `onStartDelayChange`, `onRecordToggle`, `onSyncTap`, `onShareRecording`
- A role selector at the top (so user can still see/switch roles when not recording)

**Key behaviors to preserve:**
- START button is disabled for CONTROLLER unless `btState == READY && syncReport != null` (line 379–383)
- WORKER's `RecordingScreen` should show the recording status but disable START/STOP buttons (controller drives those)
- Sync report card only shown for CONTROLLER (line 350)

---

### Step 6 — Role selector placement

The `DeviceRoleSelector` segmented button currently appears at the top of `RecorderScreen` when not recording (lines 275–291). After refactoring:

- Show it at the top of `DevicePickerScreen`, `ListenerWaitingScreen`, and `RecordingScreen` (when not recording)
- Hide it during `ConnectingScreen` (connection in progress — don't let user change role mid-connect)
- `onRoleSelected` callback stays the same: persists to SharedPrefs, fires `ACTION_LISTEN` if WORKER selected

---

## Files Changed

| File | Change |
|---|---|
| `MainActivity.kt` | All changes — restructure `RecorderScreen` into a switcher + 4 sub-composables |

No changes to services, sync logic, or data layer.

---

## What Does NOT Change

- All service binding logic (`sensorServiceConnection`, `btSyncServiceConnection`) — unchanged
- All `LaunchedEffect` polling loops — unchanged, stay in `RecorderScreen`
- `BluetoothSyncService.kt` — no changes
- `SensorRecorderService.kt` — no changes
- The countdown logic — stays in `RecorderScreen`, passed into `RecordingScreen` as callbacks
- Permission request logic — unchanged

---

## Key Enums / Classes the Developer Needs to Know

**`BtConnectionState`** (`sync/BtConnectionState.kt`):
```kotlin
enum class BtConnectionState { IDLE, CONNECTING, SYNCING, READY, RECORDING, ERROR }
```

**`DeviceRole`** (`sync/DeviceRole.kt`):
```kotlin
enum class DeviceRole { STANDALONE, CONTROLLER, WORKER }
```

**`BluetoothSyncService` actions** (companion object in `sync/BluetoothSyncService.kt`):
```kotlin
ACTION_CONNECT    // + EXTRA_DEVICE_ADDRESS — controller connects to a bonded device
ACTION_LISTEN     // worker starts listening for incoming RFCOMM connection
ACTION_DISCONNECT // disconnect and reset to IDLE
ACTION_SYNC_CLOCKS // re-run clock sync (already connected)
```

**Service volatile state** (polled every 200ms):
```kotlin
btSyncService.connectionState   // BtConnectionState
btSyncService.peerDeviceName    // String? — name of connected peer
btSyncService.lastSyncReport    // SyncReport? — clock offset result
btSyncService.errorMessage      // String? — last error
```
