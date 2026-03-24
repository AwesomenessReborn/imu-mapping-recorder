// SyncReport.kt
package com.example.sensor_recorder.sync

data class SyncReport(
    val offsetNs: Long,
    val stdDevNs: Long,
    val sampleCount: Int,
    val workerAccelCount: Long,
    val workerGyroCount: Long
)
