package com.example.imu_mapping_recorder.sync

data class WorkerReport(
    val deviceName: String,
    val offsetNs: Long,
    val stdDevNs: Long,
    val sampleCount: Int,
    val workerAccelCount: Long,
    val workerGyroCount: Long
)

data class SyncReport(
    val workers: List<WorkerReport>
)
