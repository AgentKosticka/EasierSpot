package com.agentkosticka.easierspot.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object AppPermissions {
    enum class Role { CLIENT, SERVER, ALL }

    val requiredRuntimePermissions: List<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    val optionalRuntimePermissions: List<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    val allRuntimePermissions: List<String>
        get() = requiredRuntimePermissions + optionalRuntimePermissions

    fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasRequiredRuntimePermissions(context: Context): Boolean {
        return requiredRuntimePermissions.all { isGranted(context, it) }
    }

    fun requiredFor(role: Role): List<String> = when (role) {
        Role.CLIENT, Role.ALL -> requiredRuntimePermissions
        Role.SERVER -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }

    fun hasRequiredRuntimePermissions(context: Context, role: Role): Boolean =
        requiredFor(role).all { isGranted(context, it) }

    fun missingRuntimePermissions(context: Context): List<String> {
        return allRuntimePermissions.filterNot { isGranted(context, it) }
    }
}
