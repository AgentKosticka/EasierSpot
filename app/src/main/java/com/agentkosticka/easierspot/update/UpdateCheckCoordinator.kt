package com.agentkosticka.easierspot.update

import android.content.Context
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

object UpdateCheckCoordinator {
    private const val TAG = "UpdateCheckCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArraySet<(UpdateChecker.State) -> Unit>()

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Update checks are initiated at app start and while the main screen is active. Avoid a
        // process-lifetime network callback: it wakes the app for unrelated network changes and
        // provides no benefit while the UI is not in use.
    }

    fun triggerIfStale(context: Context) {
        val appContext = context.applicationContext
        
        // Check if update checking is enabled in preferences
        if (!com.agentkosticka.easierspot.ui.settings.AppPreferences.isUpdateCheckEnabled(appContext)) {
            LogUtils.d(TAG, "Update checking disabled in preferences, skipping check")
            return
        }
        
        scope.launch {
            val state = UpdateChecker.refreshIfStale(appContext)
            notifyListeners(state)
        }
    }

    fun addListener(listener: (UpdateChecker.State) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (UpdateChecker.State) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(state: UpdateChecker.State) {
        listeners.forEach { listener ->
            try {
                listener(state)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Update listener callback failed", e)
            }
        }
    }
}
