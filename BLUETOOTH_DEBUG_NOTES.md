# Bluetooth Sync Debug Notes

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
