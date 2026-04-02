package com.agentkosticka.easierspot

import android.app.Application
import com.agentkosticka.easierspot.update.UpdateCheckCoordinator
import com.agentkosticka.easierspot.ui.settings.ThemePreferences
import org.lsposed.hiddenapibypass.HiddenApiBypass

class EasierSpotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applyThemeMode(this)
        // Allow access to hidden APIs for tethering controls (Shizuku/ITetheringConnector).
        HiddenApiBypass.setHiddenApiExemptions("L")
        UpdateCheckCoordinator.initialize(this)
        UpdateCheckCoordinator.triggerIfStale(this)
    }
}
