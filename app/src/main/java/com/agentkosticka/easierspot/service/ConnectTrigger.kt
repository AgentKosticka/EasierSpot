package com.agentkosticka.easierspot.service

import android.content.Context
import androidx.core.content.edit

enum class ConnectTrigger {
    SYSTEM_WIFI_PICKER,
    NEARBY_NOTIFICATION,
    CLIENT_ACTIVITY,
    RETRY
}

object TrustedConnectLauncher {
    fun connect(context: Context, token: String, trigger: ConnectTrigger) {
        ConnectTriggerStore.record(context, trigger)
        BleClientService.connectTrusted(context.applicationContext, token)
    }
}

internal object ConnectTriggerStore {
    private const val PREFS = "connect_trigger_v1"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_AT = "at"

    fun record(context: Context, trigger: ConnectTrigger) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_TRIGGER, trigger.name)
            putLong(KEY_AT, System.currentTimeMillis())
        }
    }

    fun consume(context: Context): ConnectTrigger? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        val raw = prefs.getString(KEY_TRIGGER, null)
        prefs.edit { remove(KEY_TRIGGER); remove(KEY_AT) }
        if (at <= 0L || System.currentTimeMillis() - at > 30_000L) return null
        return raw?.let { runCatching { ConnectTrigger.valueOf(it) }.getOrNull() }
    }
}
