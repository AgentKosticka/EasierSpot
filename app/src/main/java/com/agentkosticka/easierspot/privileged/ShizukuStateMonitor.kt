package com.agentkosticka.easierspot.privileged

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY
}

/** Process-wide, event-driven source of truth for Shizuku readiness. */
object ShizukuStateMonitor {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        PrivilegedShellClient.invalidate()
        refresh()
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh(): ShizukuState {
        if (!initialized) return _state.value
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val next = when {
            !binderAlive && !isManagerInstalled() -> ShizukuState.NOT_INSTALLED
            !binderAlive -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) !=
                PackageManager.PERMISSION_GRANTED -> ShizukuState.PERMISSION_REQUIRED
            else -> ShizukuState.READY
        }
        _state.value = next
        return next
    }

    fun isReady(): Boolean = refresh() == ShizukuState.READY

    private fun isManagerInstalled(): Boolean = listOf(
        "moe.shizuku.privileged.api",
        "rikka.shizuku"
    ).any { packageName ->
        runCatching { appContext.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }
}
