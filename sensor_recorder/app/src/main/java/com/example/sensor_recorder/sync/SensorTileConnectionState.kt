package com.example.sensor_recorder.sync

enum class SensorTileConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONFIGURING,
    READY,
    RECORDING,
    ERROR
}
