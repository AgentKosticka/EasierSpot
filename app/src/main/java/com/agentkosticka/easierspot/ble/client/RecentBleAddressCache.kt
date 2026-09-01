package com.agentkosticka.easierspot.ble.client

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/** Short-lived routing hint only. Notification intents never carry a BLE address. */
class RecentBleAddressCache(context: Context) {
    companion object {
        private const val PREFS = "recent_ble_routes_v1"
        private const val TTL_MS = 15_000L
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(token: String, address: String) {
        val key = token.lowercase()
        prefs.edit {
            putString("${key}_address", address)
            putLong("${key}_elapsed", SystemClock.elapsedRealtime())
        }
    }

    fun get(token: String): String? {
        val key = token.lowercase()
        val observed = prefs.getLong("${key}_elapsed", -1L)
        val now = SystemClock.elapsedRealtime()
        if (observed < 0L || now < observed || now - observed > TTL_MS) return null
        return prefs.getString("${key}_address", null)
    }
}
