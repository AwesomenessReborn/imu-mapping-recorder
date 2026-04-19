package com.example.imu_mapping_recorder.sync

enum class SensorTileConnectionState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONFIGURING,
    READY,
    RECORDING,
    ERROR
}
