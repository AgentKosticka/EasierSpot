package com.agentkosticka.easierspot

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class EasierSpotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allow access to hidden APIs for tethering controls (Shizuku/ITetheringConnector).
        HiddenApiBypass.setHiddenApiExemptions("L")
    }
}
