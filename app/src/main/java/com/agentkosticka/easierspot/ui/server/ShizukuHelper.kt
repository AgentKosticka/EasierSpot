package com.agentkosticka.easierspot.ui.server

import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return if (isShizukuAvailable()) {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    fun requestShizukuPermission(activity: AppCompatActivity, onGranted: () -> Unit, onDenied: () -> Unit) {
        if (!isShizukuAvailable()) {
            showShizukuNotInstalledDialog(activity)
            onDenied()
            return
        }

        if (hasShizukuPermission()) {
            onGranted()
            return
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

        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    private fun showShizukuNotInstalledDialog(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Shizuku Not Available")
            .setMessage("Server mode requires Shizuku to control the hotspot.\n\n" +
                    "Please install Shizuku from F-Droid or GitHub and grant it permissions.")
            .setPositiveButton("OK", null)
            .show()
    }
}
