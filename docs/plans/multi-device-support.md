# Plan: Multi-Device Support (1 Controller + N Workers)

**Branch to create from:** `feature/bluetooth-sync`
**Target topology:** 1 CONTROLLER phone + up to 2 WORKER phones (extensible to N)

---

## Background

The app records high-frequency IMU data (~200Hz) from multiple Android phones simultaneously. Devices must start and stop recording in sync, with their clocks aligned via a ping-pong algorithm. Currently only 1 controller ↔ 1 worker is supported. This plan extends it to 1 controller ↔ 2 (or more) workers.

Transport: **Bluetooth Classic RFCOMM** (not BLE). RFCOMM requires devices to be paired in Android Settings before connecting. The service UUID is `00001101-0000-1000-8000-00805F9B34FB`.

---

## Current Architecture (1:1)

`BluetoothSyncService.kt` maintains a single connection:

```kotlin
// Single connection state
private var socket: BluetoothSocket? = null          // one socket
private var serverSocket: BluetoothServerSocket? = null
private var reader: BufferedReader? = null            // one reader
private var writer: BufferedWriter? = null            // one writer
private val estimator = ClockOffsetEstimator()        // one estimator
private val pongChannel = Channel<SyncMessage.Pong>  // one pong channel
private var isConnected = false
```

`SensorRecorderService.kt` has flat Bluetooth metadata fields:

```kotlin
var btClockOffsetNs: Long? = null         // single offset
var btClockOffsetStdDevNs: Long? = null
var btClockOffsetSamples: Int? = null
var btPeerDevice: String? = null
```

`SyncReport.kt` holds sample counts for one worker:

```kotlin
data class SyncReport(
    val offsetNs: Long,
    val stdDevNs: Long,
    val sampleCount: Int,
    val workerAccelCount: Long,  // single worker
    val workerGyroCount: Long
)
```

---

## Target Architecture (1:N)

### New data structure: `PeerConnection`

Introduce a data class to hold the state of one peer connection:

```kotlin
data class PeerConnection(
    val device: BluetoothDevice,
    val socket: BluetoothSocket,
    val reader: BufferedReader,
    val writer: BufferedWriter,
    val pongChannel: Channel<SyncMessage.Pong>,
    val estimator: ClockOffsetEstimator,
    @Volatile var clockOffsetResult: ClockOffsetEstimator.Result? = null,
    @Volatile var lastStatusReport: SyncMessage.Status? = null
)
```

`BluetoothSyncService` replaces all single-connection fields with:

```kotlin
private val peers = CopyOnWriteArrayList<PeerConnection>()
```

---

## Files to Modify

| File | Changes |
|---|---|
| `sync/BluetoothSyncService.kt` | Major refactor — multi-peer connection list, fan-out commands, aggregate status |
| `sync/SyncReport.kt` | Replace single-worker fields with per-peer list |
| `SensorRecorderService.kt` | Replace flat BT offset fields with a map keyed by device name |

---

## Step-by-Step Changes

### Step 1 — `BluetoothSyncService.kt`: Replace single-socket fields

**Remove:**
```kotlin
private var socket: BluetoothSocket? = null
private var serverSocket: BluetoothServerSocket? = null
private var reader: BufferedReader? = null
private var writer: BufferedWriter? = null
private val estimator = ClockOffsetEstimator()
private val pongChannel = Channel<SyncMessage.Pong>(Channel.BUFFERED)
private var isConnected = false
```

**Add:**
```kotlin
private val peers = CopyOnWriteArrayList<PeerConnection>()
private var serverSocket: BluetoothServerSocket? = null  // kept for worker listener
private val MAX_PEERS = 2  // configurable
```

Update existing volatile UI state:
```kotlin
// peerDeviceName becomes a summary string (e.g. "Phone A, Phone B")
@Volatile var peerDeviceNames: List<String> = emptyList()
// connectionState reflects "worst" state across all peers
// (IDLE if none, CONNECTING if any connecting, READY if all ready, etc.)
```

---

### Step 2 — `BluetoothSyncService.kt`: Controller — connect to multiple devices

**Current** `connectToDevice(device: BluetoothDevice)` connects to one device.

**New** approach: The UI sends `ACTION_CONNECT` with one device address at a time (called once per worker). Each call creates a new `PeerConnection` and appends it to `peers`. The controller can call this multiple times before starting a session.

```kotlin
private fun connectToDevice(device: BluetoothDevice) {
    serviceScope.launch {
        // ... existing retry loop (3 attempts, 500ms delay) ...
        val btSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        btSocket.connect()

        val peer = PeerConnection(
            device = device,
            socket = btSocket,
            reader = BufferedReader(InputStreamReader(btSocket.inputStream)),
            writer = BufferedWriter(OutputStreamWriter(btSocket.outputStream)),
            pongChannel = Channel(Channel.BUFFERED),
            estimator = ClockOffsetEstimator()
        )
        peers.add(peer)
        startReaderLoop(peer)  // reader loop per peer

        // Run clock sync for this peer
        runClockSync(peer)
        updateConnectionState()  // recompute aggregate state
    }
}
```

**`updateConnectionState()`** — compute aggregate `connectionState` from all peers:
- No peers → IDLE
- Any peer CONNECTING → CONNECTING
- Any peer SYNCING → SYNCING
- All peers READY → READY
- Any peer RECORDING → RECORDING
- Any peer ERROR (and no others connecting) → ERROR

---

### Step 3 — `BluetoothSyncService.kt`: Worker — accept multiple connections sequentially

**Current** `startListening()` accepts one connection then closes the server socket.

**New** approach: After accepting one connection, re-open the server socket to accept the next one (up to `MAX_PEERS` concurrent connections):

```kotlin
private fun startListening() {
    serviceScope.launch {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // Accept connections until MAX_PEERS reached
        while (peers.size < MAX_PEERS) {
            val ss = btManager.adapter.listenUsingRfcommWithServiceRecord("IMUSyncService", SERVICE_UUID)
            serverSocket = ss
            connectionState = BtConnectionState.CONNECTING

            val btSocket = withContext(Dispatchers.IO) { ss.accept() }
            ss.close()
            serverSocket = null

            val peer = PeerConnection(
                device = btSocket.remoteDevice,
                socket = btSocket,
                reader = BufferedReader(InputStreamReader(btSocket.inputStream)),
                writer = BufferedWriter(OutputStreamWriter(btSocket.outputStream)),
                pongChannel = Channel(Channel.BUFFERED),
                estimator = ClockOffsetEstimator()
            )
            peers.add(peer)
            startReaderLoop(peer)  // independent reader per controller
            // Worker doesn't initiate clock sync — controller drives it via PINGs
        }
    }
}
```

**Note on multiple controllers connecting to one worker:** RFCOMM server sockets accept one connection per `listenUsing...` call. To accept a second connection, you must call `listenUsing...` again with the same UUID. This is supported on Android and is what the loop above does.

---

### Step 4 — `BluetoothSyncService.kt`: Per-peer reader loop

Change `startReaderLoop()` to accept a `PeerConnection` parameter:

```kotlin
private fun startReaderLoop(peer: PeerConnection) {
    serviceScope.launch(Dispatchers.IO) {
        try {
            while (peer.socket.isConnected) {
                val line = peer.reader.readLine() ?: break
                val msg = SyncMessage.fromJson(line) ?: continue
                handleMessage(msg, peer)  // pass peer so handler knows the source
            }
        } catch (e: IOException) {
            Timber.e(e, "Reader loop error for ${peer.device.name}")
        }
        removePeer(peer)
    }
}
```

---

### Step 5 — `BluetoothSyncService.kt`: `handleMessage` receives source peer

Change signature:
```kotlin
private fun handleMessage(msg: SyncMessage, peer: PeerConnection)
```

**For WORKER role:**
- PING → reply PONG using `peer.writer` (not the global `writer`)
- Commands → same as before, but `sendLine` targets the source peer

**For CONTROLLER role:**
- PONG → send to `peer.pongChannel` (not the global `pongChannel`)
- STATUS → store in `peer.lastStatusReport`; check if all peers have reported → then aggregate into `lastSyncReport`

**Aggregating STATUS across all peers:**
```kotlin
is SyncMessage.Status -> {
    peer.lastStatusReport = msg
    val allReported = peers.all { it.lastStatusReport != null }
    if (allReported) {
        lastSyncReport = buildAggregatedSyncReport()
        connectionState = BtConnectionState.READY
        // clear status for next session
        peers.forEach { it.lastStatusReport = null }
    }
}
```

---

### Step 6 — `BluetoothSyncService.kt`: Clock sync per peer

Change `runClockSync()` to accept a `PeerConnection`:

```kotlin
private suspend fun runClockSync(peer: PeerConnection) {
    // Clear old pongs for this peer
    while (peer.pongChannel.tryReceive().isSuccess) {}

    repeat(10) { i ->
        val tSend = SystemClock.elapsedRealtimeNanos()
        sendLineToPeer(peer, SyncMessage.Ping(tSend).toJson())

        val pong = withTimeoutOrNull(2000) { peer.pongChannel.receive() } ?: return
        val tRecv = SystemClock.elapsedRealtimeNanos()
        peer.estimator.addSample(tSend, pong.tRecvNs, pong.tReplyNs, tRecv)
        delay(100)
    }

    val result = peer.estimator.estimate() ?: return
    peer.clockOffsetResult = result

    // Push into SensorRecorderService metadata map
    sensorService?.btClockOffsets?.put(peer.device.name ?: peer.device.address, result)
}
```

---

### Step 7 — `BluetoothSyncService.kt`: Fan-out `sendCommand`

Change `sendCommand()` to broadcast to all peers:

```kotlin
fun sendCommand(commandType: String) {
    val sessionId = currentSessionId ?: run {
        val id = UUID.randomUUID().toString().take(8)
        currentSessionId = id
        id
    }
    val json = SyncMessage.Command(commandType, sessionId).toJson()
    peers.forEach { peer ->
        sendLineToPeer(peer, json)
    }
    if (commandType == "CMD_START") connectionState = BtConnectionState.RECORDING
}
```

Add a `sendLineToPeer(peer, json)` helper that uses `peer.writer` instead of the global `writer`.

---

### Step 8 — `BluetoothSyncService.kt`: Disconnect a single peer

Add `removePeer(peer: PeerConnection)`:

```kotlin
private fun removePeer(peer: PeerConnection) {
    peers.remove(peer)
    try { peer.reader.close() } catch (_: Exception) {}
    try { peer.writer.close() } catch (_: Exception) {}
    try { peer.socket.close() } catch (_: Exception) {}
    updateConnectionState()
    if (peers.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
}
```

Update `handleDisconnect()` to close all peers:
```kotlin
private fun handleDisconnect() {
    peers.toList().forEach { removePeer(it) }
    try { serverSocket?.close() } catch (_: Exception) {}
    serverSocket = null
    connectionState = BtConnectionState.IDLE
    currentSessionId = null
}
```

---

### Step 9 — `SyncReport.kt`: Per-peer worker data

Replace the two flat fields with a list:

```kotlin
data class WorkerReport(
    val deviceName: String,
    val accelCount: Long,
    val gyroCount: Long,
    val clockOffsetNs: Long,
    val clockOffsetStdDevNs: Long,
    val clockOffsetSamples: Int
)

data class SyncReport(
    val workers: List<WorkerReport>
)
```

Update `SyncReportCard` in `MainActivity.kt` to iterate over `report.workers` and show one row per worker.

---

### Step 10 — `SensorRecorderService.kt`: Map of clock offsets

Replace flat BT fields:

```kotlin
// Remove:
var btClockOffsetNs: Long? = null
var btClockOffsetStdDevNs: Long? = null
var btClockOffsetSamples: Int? = null
var btPeerDevice: String? = null

// Add:
val btClockOffsets: ConcurrentHashMap<String, ClockOffsetEstimator.Result> = ConcurrentHashMap()
```

Update `stopRecording()` where `Metadata.json` is written. The current metadata format:

```json
{
  "recording_start_epoch_ms": 1234567890123,
  "epoch_offset_ns": 456789012345678,
  "accel_sample_count": 12000,
  "gyro_sample_count": 12000
}
```

Extend to:

```json
{
  "recording_start_epoch_ms": 1234567890123,
  "epoch_offset_ns": 456789012345678,
  "accel_sample_count": 12000,
  "gyro_sample_count": 12000,
  "bt_clock_offsets": {
    "Pixel 7 Pro": {
      "offset_ns": 123456,
      "std_dev_ns": 4567,
      "sample_count": 8
    },
    "Samsung Galaxy S23": {
      "offset_ns": -23456,
      "std_dev_ns": 3210,
      "sample_count": 8
    }
  }
}
```

---

### Step 11 — `MainActivity.kt` UI changes for multi-device

**Controller device picker:** The current flow lets you select one device. For multi-device, after selecting the first device and it connecting successfully (btState → READY), show an "Add another device" button that triggers `ACTION_CONNECT` for a second device. Or, allow multi-select in the device picker list.

**Connection state display:** Instead of `peerDeviceName` (single string), display a list. `BluetoothStatusCard` should show each peer's name and its individual sync status.

**Start button gating:** Currently requires `btState == READY && syncReport != null`. With multiple peers: require all peers to be READY (i.e., all clock syncs complete). The aggregate `connectionState` handles this if `updateConnectionState()` is implemented correctly.

---

## Key Classes Referenced

| Class | Path | Notes |
|---|---|---|
| `BluetoothSyncService` | `sync/BluetoothSyncService.kt` | Main refactor target |
| `SyncReport` | `sync/SyncReport.kt` | Restructure for N workers |
| `SyncMessage` | `sync/SyncMessage.kt` | No changes needed |
| `ClockOffsetEstimator` | `sync/ClockOffsetEstimator.kt` | No changes needed — instantiate one per peer |
| `BtConnectionState` | `sync/BtConnectionState.kt` | No changes needed |
| `DeviceRole` | `sync/DeviceRole.kt` | No changes needed |
| `SensorRecorderService` | `SensorRecorderService.kt` | Change flat BT fields to map |
| `MainActivity` | `MainActivity.kt` | Update UI for multi-peer display |

---

## Concurrency Notes

- `CopyOnWriteArrayList<PeerConnection>` for `peers` — safe for iteration during fan-out sends while connections are being added/removed
- Each `PeerConnection` has its own `Channel<Pong>` — no cross-peer pong routing issues
- `ConcurrentHashMap` for `btClockOffsets` in `SensorRecorderService` — safe for concurrent writes from `BluetoothSyncService`
- `serviceScope` (`Dispatchers.IO + Job()`) already used for all coroutines — continue using it, launching one coroutine per peer for reader loops

---

## Testing Checklist

- [ ] CONTROLLER connects to 1 worker — existing behavior unchanged
- [ ] CONTROLLER connects to 2 workers sequentially — both appear as peers
- [ ] Clock sync runs independently per peer (offsets differ between devices as expected)
- [ ] CMD_START sent to both workers simultaneously — both start recording
- [ ] CMD_STOP sent to both workers — both stop, STATUS received from both before READY state
- [ ] Metadata.json on controller contains clock offsets for both peers
- [ ] If one worker disconnects mid-session, remaining peer continues recording
- [ ] Worker role: accepts 2 controller connections in sequence (unusual but should not crash)
