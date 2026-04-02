# Project Context: Android IMU Recorder

## Overview
The **Android IMU Recorder** is a high-performance Android application designed to record accelerometer and gyroscope data at high frequencies (targeting ~200Hz or the maximum supported hardware rate). It is specifically built for multi-device IMU research to enable precise signal alignment and mapping between different body locations.

## Architecture
The project is a modern Android application built using **Kotlin**, **Jetpack Compose**, and **Gradle (Kotlin DSL)**.

### Key Components
- **`SensorRecorderService.kt`**: A Foreground Service that handles the core recording logic.
    - Uses `SENSOR_DELAY_FASTEST` for maximum sampling rate.
    - Buffers sensor events in memory using `ConcurrentLinkedQueue` to avoid I/O bottlenecks.
    - Flushes data to disk every 1 second via a background coroutine.
    - Employs a `PARTIAL_WAKE_LOCK` to ensure recording continues when the screen is off.
    - Captures monotonic `event.timestamp` (nanoseconds since boot) for timing integrity.
- **`MainActivity.kt`**: The user interface built with Jetpack Compose.
    - Provides real-time visualization of sensor data and sampling rates.
    - Includes a "Sync Tap" feature for manual timestamp marking (used for multi-device alignment).
- **`ExportUtils.kt`**: Utility for packaging recording sessions (CSVs and Metadata) into ZIP files and sharing them via the Android share sheet.

## Building and Running
All build commands should be executed from the `sensor_recorder/` directory:

```bash
cd sensor_recorder

# Build the debug APK
./gradlew assembleDebug

# Install the debug APK to a connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented (on-device) tests
./gradlew connectedAndroidTest

# Clean the project
./gradlew clean
```

## Data Structure & Format
Recording sessions are stored in `getExternalFilesDir(null)/Recordings/` and contain:
- **`Accelerometer.csv`**: `timestamp_ns,x,y,z`
- **`Gyroscope.csv`**: `timestamp_ns,x,y,z`
- **`Annotation.csv`**: `timestamp_ns,label` (e.g., `sync_tap`)
- **`Metadata.json`**: Contains session details, including `recording_start_epoch_ms` and `epoch_offset_ns` for wall-clock mapping.

## Development Conventions
- **Language**: Kotlin with Coroutines for asynchronous tasks.
- **UI**: Jetpack Compose with Material 3.
- **Service Type**: `foregroundServiceType="dataSync"` for background sensor access.
- **Timing**: Always prioritize `event.timestamp` over `System.currentTimeMillis()` for sensor intervals. Use `epoch_offset_ns` captured at start for absolute time references.

## Key Technical Decisions
- **Buffer-and-Flush Pattern**: Critical for maintaining ~200Hz+ sampling rates without dropping samples due to disk I/O latency.
- **Monotonic Clock**: Uses `SystemClock.elapsedRealtimeNanos()` references to remain immune to NTP time jumps during recording.
- **Target SDK**: Configured for Android 15 (SDK 35) with appropriate foreground service permissions.
