package com.example.sscan

import android.app.Application
import com.example.sscan.core.settings.AppSettings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyAppPreferences(this)
        AppSettings.sanitizeLegacyThrottle(this)
    }
}
