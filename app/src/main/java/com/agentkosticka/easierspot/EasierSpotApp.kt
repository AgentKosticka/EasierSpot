package com.agentkosticka.easierspot

import android.app.Application
import com.agentkosticka.easierspot.update.UpdateCheckCoordinator
import com.agentkosticka.easierspot.ui.settings.AppLanguageManager
import com.agentkosticka.easierspot.ui.settings.ThemePreferences
import com.agentkosticka.easierspot.util.LogUtils
import com.agentkosticka.easierspot.ble.client.BleDiscoveryRegistrar
import com.agentkosticka.easierspot.privileged.PrivilegedShellClient
import com.agentkosticka.easierspot.privileged.ShizukuState
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import com.agentkosticka.easierspot.shared.SharedConnectivityBackends
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EasierSpotApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LogUtils.appContext = applicationContext
        AppLanguageManager.applySavedLanguage(this)
        ThemePreferences.applyThemeMode(this)
        // Allow access to hidden APIs for tethering controls (Shizuku/ITetheringConnector).
        HiddenApiBypass.setHiddenApiExemptions("L")
        PrivilegedShellClient.initialize(this)
        ShizukuStateMonitor.initialize(this)
        UpdateCheckCoordinator.initialize(this)
        UpdateCheckCoordinator.triggerIfStale(this)
        appScope.launch {
            BleDiscoveryRegistrar.reconcile(this@EasierSpotApp)
            SharedConnectivityBackends.current.reconcile(this@EasierSpotApp)
        }
        appScope.launch {
            ShizukuStateMonitor.state.collect { state ->
                if (state == ShizukuState.READY) {
                    SharedConnectivityBackends.current.reconcile(this@EasierSpotApp)
                }
            }
        }
    }
}
