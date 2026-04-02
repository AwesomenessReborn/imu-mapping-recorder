# Bluetooth Known Issues

## 1. Concurrency: Read Conflict on Bluetooth Stream
**Status:** Critical Bug
**Symptoms:** 
- Manual "Sync Clocks" hangs in the UI.
- Logcat shows `Unhandled message type: Pong(...)` while `runClockSync()` is active.

**Technical Analysis:**
The `BluetoothSyncService` has two active coroutines competing for the same `BufferedReader`:
1. `runClockSync()`: A `suspend` function that calls `reader.readLine()` in a loop.
2. `startReaderLoop()`: A long-running loop that calls `reader.readLine()` to process commands/acks.

When the connection is first established, `runClockSync()` runs to completion *before* `startReaderLoop()` is started. This works fine. 
However, when a user triggers a manual sync later, `startReaderLoop()` is already running. It wins the race to read the `Pong` message from the socket. `runClockSync()` remains blocked on `readLine()`, and the `ReaderLoop` doesn't know what to do with a `Pong` message, so it just logs an error.

**Proposed Fix:**
- Move all `readLine()` calls into the `ReaderLoop`.
- Use a `Channel` or `SharedFlow` to dispatch `Pong` messages from the `ReaderLoop` back to the `runClockSync()` caller.
- Or, use a `Mutex` to ensure only one component is reading from the stream at a time (though dispatching is cleaner).

---

## 2. Bluetooth Connection Stability
**Status:** Known Android Behavior
**Symptoms:**
- `java.io.IOException: read failed, socket might closed or timeout, read ret: -1` during `socket.connect()`.
- Error code `-1` usually indicates the remote side (Worker) rejected the connection or the RFCOMM channel wasn't ready.

**Technical Analysis:**
Android Bluetooth connections are notoriously flaky. The `INIT` state in the logs suggests the socket was created but the handshake failed immediately.

**Proposed Fix:**
- Implement a simple retry mechanism (e.g., 3 attempts with 500ms delay).
- Ensure `BluetoothServerSocket.accept()` on the Worker is called and waiting *before* the Controller attempts to connect.
- Add a small delay between `socket.connect()` and `setupStreams()`.

---

## 3. Clock Sync Precision
**Status:** Optimization
**Symptoms:**
- `stdDev` of ~16ms observed in logs.
- At 208Hz, the sample period is ~4.8ms. A 16ms error represents a ~3 sample misalignment.

**Technical Analysis:**
Bluetooth RTT (Round Trip Time) can be jittery depending on CPU load and radio interference.

**Proposed Fix:**
- Increase ping rounds from 10 to 20.
- Implement more aggressive outlier rejection (e.g., discard any sample where RTT > 2x the median RTT).
- Use `System.nanoTime()` or `SystemClock.elapsedRealtimeNanos()` consistently (already doing this, but ensure no overhead in JSON serialization is skewing results).

---

## 4. Worker Does Not Auto-Re-Listen After Disconnect
**Status:** Fixed (2026-03-31)
**Symptoms:**
- After a controller disconnects (or connection drops), the worker UI transitions back to "Waiting for Controller" but the RFCOMM server socket is never reopened.
- Controller cannot reconnect without the user manually switching the worker's tab (WORKER → CONTROLLER → WORKER), which triggers `ACTION_DISCONNECT` then `ACTION_LISTEN`.
- Logcat evidence: `bt socket closed, read return: -1` in reader loop, then no subsequent `Worker: opening RFCOMM server socket` log.

**Root Cause:**
`removePeer()` cleans up the dropped `PeerConnection` and calls `updateConnectionState()`, which correctly returns to IDLE. However, it never called `startListening()` again. The only path to re-open the listener was through an explicit `ACTION_LISTEN` intent, which is only sent when the user taps the WORKER role button.

**Fix Applied — `BluetoothSyncService.removePeer()`:**
```kotlin
if (peers.isEmpty()) {
    stopForeground(STOP_FOREGROUND_REMOVE)
    if (role == DeviceRole.WORKER) {
        Timber.i("Worker: peer disconnected, auto-restarting listener")
        startListening()
    }
}
```
`handleDisconnect()` (called on explicit `ACTION_DISCONNECT`) does NOT call `removePeer()`, so the auto-restart only triggers on unintentional drops, not user-initiated role changes.

---

## 5. Stale Socket "Already Closed" Warnings on Tab Switch
**Status:** Benign / Known Android Behavior
**Symptoms:**
```
BluetoothSocket: close() XX:XX:XX:XX:6E:5C: Already closed
System: A resource failed to call close.
```
Seen after switching the device role tab (WORKER → CONTROLLER → WORKER) while a previous socket is being cleaned up.

**Root Cause:**
`handleDisconnect()` calls `safeClose(p.socket)` on each peer. The reader loop coroutine catches the resulting `IOException` and then calls `removePeer()`, which in turn calls `safeClose()` on the same socket again. This double-close is harmless — Android logs it at VERBOSE level — but it triggers a `System` warning about an unclosed resource from the finalizer.

**Fix (if desired):**
Add a `@Volatile var closed: Boolean = false` flag to `PeerConnection` and guard each `safeClose` call. Not strictly necessary since the behavior is benign.
