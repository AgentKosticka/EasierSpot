package com.agentkosticka.easierspot.ui.server

import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.net.toUri
import com.agentkosticka.easierspot.privileged.ShizukuState
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return ShizukuStateMonitor.refresh() != ShizukuState.NOT_INSTALLED &&
            ShizukuStateMonitor.state.value != ShizukuState.NOT_RUNNING
    }

    fun hasShizukuPermission(): Boolean {
        return ShizukuStateMonitor.isReady()
    }

    fun requestShizukuPermission(activity: AppCompatActivity, onGranted: () -> Unit, onDenied: () -> Unit) {
        when (ShizukuStateMonitor.refresh()) {
            ShizukuState.NOT_INSTALLED -> {
                showShizukuSetupDialog(activity, installed = false)
                onDenied()
                return
            }
            ShizukuState.NOT_RUNNING -> {
                showShizukuSetupDialog(activity, installed = true)
                onDenied()
                return
            }
            ShizukuState.READY -> {
                onGranted()
                return
            }
            ShizukuState.PERMISSION_REQUIRED -> Unit
        }

        // Register callback for permission result
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGranted()
                    } else {
                        onDenied()
                    }
                    Shizuku.removeRequestPermissionResultListener(this)
                }
            }
        }

        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Shizuku.removeRequestPermissionResultListener(listener)
                owner.lifecycle.removeObserver(this)
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)

        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
            .onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                onDenied()
            }
    }

    private fun showShizukuSetupDialog(activity: AppCompatActivity, installed: Boolean) {
        AlertDialog.Builder(activity)
            .setTitle(if (installed) "Start Shizuku" else "Install Shizuku")
            .setMessage(
                if (installed) {
                    "Server mode needs Shizuku running. Open Shizuku, start it, then return to EasierSpot."
                } else {
                    "Server mode needs Shizuku to control the hotspot in the background."
                }
            )
            .setPositiveButton(if (installed) "Open Shizuku" else "Installation guide") { _, _ ->
                if (installed) {
                    activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        ?.let(activity::startActivity)
                } else {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/guide/setup/".toUri())
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
