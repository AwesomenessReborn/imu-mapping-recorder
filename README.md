# IMU Data Recorder for Android

> **Research fork:** For the variant that adds a SensorTile.box PRO as a third IMU device (2 phones + SensorTile, tap-based post-hoc alignment, 208Hz inference pipeline), see [imu-mapping-recorder](https://github.com/AwesomenessReborn/imu-mapping-recorder).

## Purpose

A general-purpose Android app to record high-frequency IMU data (accelerometer + gyroscope) from **multiple Android phones** simultaneously at **~200 Hz** and export to CSV. Uses Bluetooth Classic for sub-5ms clock synchronization and coordinated start/stop across devices. Built to overcome limitations in existing tools that could not reliably sustain high sampling rates on modern Android devices.

## Features

- **High-Frequency Recording:** Targets `SENSOR_DELAY_FASTEST` to achieve maximum hardware sampling rates (typically ~200-400Hz on modern devices).
- **Foreground Service:** Ensures recording continues uninterrupted even when the screen is off or the app is in the background.
- **Monotonic Timestamps:** Uses `event.timestamp` (nanoseconds since boot) to avoid jitter from NTP clock updates.
- **CSV Export:** Saves data to standard CSV format for easy analysis in Python/Pandas.
- **Live Preview:** Displays real-time sensor data and effective sampling rates.
- **Sync Markers:** Includes a "Sync Tap" button to insert manual markers in the data stream for synchronizing with other devices or video.

## Multi-Device Sync Support

The app includes built-in support for high-precision synchronization across multiple devices using **Bluetooth Classic (RFCOMM)**.

### Network Topology

- **Controller Role:** One device acts as the master. It initiates connections, runs the clock synchronization algorithm, and triggers START/STOP/MARK commands for all workers.
- **Worker Role:** Up to **3 worker devices** can connect to a single controller. They listen for incoming connections and respond to commands.
- **Synchronization:** Uses a ping-pong algorithm to estimate the monotonic clock offset between the controller and each worker with sub-millisecond precision.

### Usage (Sync Mode)

1. **Prepare Devices:** On all devices, select the appropriate role (**CONTROLLER** or **WORKER**) using the segmented buttons at the top.
2. **Pairing:** Ensure all worker phones are paired with the controller phone in Android System Settings.
3. **Connection:** On the Controller, tap "Select Device" and pick a worker from the list. Repeat to add up to 3 workers.
4. **Syncing:** The controller automatically runs a 10-round clock sync once connected. You can manually re-sync by tapping "Sync Clocks".
5. **Control:** Press "START RECORDING" on the controller. All connected workers will start recording simultaneously. "SYNC TAP" and "STOP RECORDING" are also synchronized.

## Technical Details

### Sampling Rate

The app uses `SENSOR_DELAY_FASTEST`. On modern Android devices, this typically yields:

- **Accelerometer:** ~200-400 Hz (depending on hardware and OS scheduling)
- **Gyroscope:** ~200-400 Hz

### Data Format

The app exports three CSV files and a metadata JSON for each session:

#### Accelerometer.csv / Gyroscope.csv

```csv
timestamp_ns,x,y,z
783012345678900,0.0234,-9.7891,0.1456
...
```

**Annotation.csv** (Markers)

```csv
timestamp_ns,label
783012345678900,sync_tap
```

**Metadata.json** (New Multi-Device Schema)
Contains session details, including a `bt_clock_offsets` map for aligning data from multiple devices.

```json
{
  "recording_start_epoch_ms": 1711891200000,
  "epoch_offset_ns": 456789012345678,
  "accel_sample_count": 12000,
  "gyro_sample_count": 12000,
  "bt_clock_offsets": {
    "Pixel 7 Pro": {
      "offset_ns": 123456,
      "std_dev_ns": 4567,
      "sample_count": 10
    }
  }
}
```

## Usage

1. **Start Recording:** Press the "Start Recording" button. The app will lock the CPU awake and begin logging data.
2. **Sync (Optional):** If recording with multiple devices, you can perform a "clap" or tap the devices together and press "Sync Tap" to mark the event.
3. **Stop & Export:** Press "Stop Recording". Use the "Share Recording" button to export the session data as a ZIP file via email, Google Drive, or other apps.

## Requirements

- **Min SDK:** 29 (Android 10)
- **Target SDK:** 36 (Android 15)
- **Permissions:**
  - `FOREGROUND_SERVICE`: To record in background.
  - `FOREGROUND_SERVICE_DATA_SYNC`: Service type for foreground data sync.
  - `POST_NOTIFICATIONS`: To show the recording status.
  - `HIGH_SAMPLING_RATE_SENSORS`: To access 200Hz+ data.
  - `WAKE_LOCK`: To keep CPU active during recording.
