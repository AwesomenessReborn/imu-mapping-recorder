// SensorRecorderApp.kt
package com.example.imu_mapping_recorder

import android.app.Application
import timber.log.Timber

class SensorRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
