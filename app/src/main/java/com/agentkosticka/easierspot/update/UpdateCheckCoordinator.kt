package com.agentkosticka.easierspot.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

object UpdateCheckCoordinator {
    private const val TAG = "UpdateCheckCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var initialized = false
    private val listeners = CopyOnWriteArraySet<(UpdateChecker.State) -> Unit>()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            registerNetworkCallback(appContext)
            initialized = true
        }
    }

    fun triggerIfStale(context: Context) {
        val appContext = context.applicationContext
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

    private fun registerNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            LogUtils.w(TAG, "ConnectivityManager unavailable; network callback not registered")
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                    triggerIfStale(context)
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to register network callback", e)
        }
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
