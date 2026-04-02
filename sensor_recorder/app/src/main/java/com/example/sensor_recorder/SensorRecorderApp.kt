// SensorRecorderApp.kt
package com.example.sensor_recorder

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
