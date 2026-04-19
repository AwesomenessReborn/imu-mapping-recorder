// DeviceRole.kt
package com.example.imu_mapping_recorder.sync

/**
 * Defines the role of the device in a multi-device recording session.
 */
enum class DeviceRole {
    /** The device is not participating in a sync session. */
    STANDALONE,

    /** The device that controls the recording session (sends commands). */
    CONTROLLER,

    /** The device that is controlled by the Controller (receives commands). */
    WORKER
}
