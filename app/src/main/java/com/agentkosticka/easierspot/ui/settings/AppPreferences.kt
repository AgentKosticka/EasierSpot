package com.agentkosticka.easierspot.ui.settings

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object AppPreferences {
    const val PREFS_NAME = "app_preferences"

    // Keys
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_UPDATE_CHECK_ENABLED = "update_check_enabled"
    private const val KEY_APP_LANGUAGE = "app_language"
    private const val KEY_APPROVAL_NOTIFICATION_ENABLED = "approval_notification_enabled"
    private const val KEY_CONNECTION_NOTIFICATION_ENABLED = "connection_notification_enabled"
    private const val KEY_NOTIFICATION_SOUND_ENABLED = "notification_sound_enabled"
    private const val KEY_NOTIFICATION_VIBRATION_ENABLED = "notification_vibration_enabled"
    private const val KEY_BLE_ADVERTISING_INTERVAL = "ble_advertising_interval"
    private const val KEY_BROADCAST_STRENGTH = "broadcast_strength"
    private const val KEY_SCAN_TIMEOUT_MS = "scan_timeout_ms"
    private const val KEY_AUTO_RETRY_ENABLED = "auto_retry_enabled"
    private const val KEY_DEFAULT_APPROVAL_POLICY = "default_approval_policy"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_MIGRATED_FROM_DEFAULT_PREFS = "migrated_from_default_prefs"

    // Default values
    private const val DEFAULT_UPDATE_CHECK_ENABLED = true
    private const val DEFAULT_APP_LANGUAGE = "system"
    private const val DEFAULT_APPROVAL_NOTIFICATION_ENABLED = true
    private const val DEFAULT_CONNECTION_NOTIFICATION_ENABLED = true
    private const val DEFAULT_NOTIFICATION_SOUND_ENABLED = true
    private const val DEFAULT_NOTIFICATION_VIBRATION_ENABLED = true
    private const val DEFAULT_ADVERTISING_INTERVAL = "balanced"
    private const val DEFAULT_BROADCAST_STRENGTH = "medium"
    private const val DEFAULT_SCAN_TIMEOUT_MS = 30000L
    private const val DEFAULT_AUTO_RETRY_ENABLED = true
    private const val DEFAULT_APPROVAL_POLICY = "ask"
    private const val DEFAULT_DEBUG_LOGGING_ENABLED = false
    private const val DEFAULT_KEEP_SCREEN_ON = false

    enum class AdvertisingInterval(val value: String) {
        SLOW("slow"),
        BALANCED("balanced"),
        FREQUENT("frequent");

        companion object {
            fun fromValue(value: String?): AdvertisingInterval {
                return entries.firstOrNull { it.value == value } ?: BALANCED
            }
        }
    }

    enum class BroadcastStrength(val value: String) {
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high");

        companion object {
            fun fromValue(value: String?): BroadcastStrength {
                return entries.firstOrNull { it.value == value } ?: MEDIUM
            }
        }
    }

    enum class ApprovalPolicy(val value: String) {
        ASK("ask"),
        APPROVE("approve"),
        DENY("deny");

        companion object {
            fun fromValue(value: String?): ApprovalPolicy {
                return entries.firstOrNull { it.value == value } ?: ASK
            }
        }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val migratableKeys = setOf(
        KEY_THEME_MODE,
        KEY_UPDATE_CHECK_ENABLED,
        KEY_APP_LANGUAGE,
        KEY_APPROVAL_NOTIFICATION_ENABLED,
        KEY_CONNECTION_NOTIFICATION_ENABLED,
        KEY_NOTIFICATION_SOUND_ENABLED,
        KEY_NOTIFICATION_VIBRATION_ENABLED,
        KEY_BLE_ADVERTISING_INTERVAL,
        KEY_BROADCAST_STRENGTH,
        KEY_SCAN_TIMEOUT_MS,
        KEY_AUTO_RETRY_ENABLED,
        KEY_DEFAULT_APPROVAL_POLICY,
        KEY_DEBUG_LOGGING_ENABLED,
        KEY_KEEP_SCREEN_ON
    )

    // Update Check
    fun isUpdateCheckEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_UPDATE_CHECK_ENABLED, DEFAULT_UPDATE_CHECK_ENABLED)
    }

    // App Language
    fun getAppLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE)
            ?: DEFAULT_APP_LANGUAGE
    }

    fun setAppLanguage(context: Context, languageTag: String) {
        getPrefs(context).edit { putString(KEY_APP_LANGUAGE, languageTag) }
    }

    // Notification Sound
    fun isNotificationSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NOTIFICATION_SOUND_ENABLED, DEFAULT_NOTIFICATION_SOUND_ENABLED)
    }

    // Notification Vibration
    fun isNotificationVibrationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NOTIFICATION_VIBRATION_ENABLED, DEFAULT_NOTIFICATION_VIBRATION_ENABLED)
    }

    // BLE Advertising Interval
    fun getBleAdvertisingInterval(context: Context): AdvertisingInterval {
        val value = getPrefs(context).getString(KEY_BLE_ADVERTISING_INTERVAL, DEFAULT_ADVERTISING_INTERVAL)
        return AdvertisingInterval.fromValue(value)
    }

    fun setBleAdvertisingInterval(context: Context, interval: AdvertisingInterval) {
        getPrefs(context).edit { putString(KEY_BLE_ADVERTISING_INTERVAL, interval.value) }
    }

    // BLE Broadcast Strength
    fun getBroadcastStrength(context: Context): BroadcastStrength {
        val value = getPrefs(context).getString(KEY_BROADCAST_STRENGTH, DEFAULT_BROADCAST_STRENGTH)
        return BroadcastStrength.fromValue(value)
    }

    fun setBroadcastStrength(context: Context, strength: BroadcastStrength) {
        getPrefs(context).edit { putString(KEY_BROADCAST_STRENGTH, strength.value) }
    }

    // Scan Timeout
    fun getScanTimeoutMs(context: Context): Long {
        return getLongPreference(context, KEY_SCAN_TIMEOUT_MS, DEFAULT_SCAN_TIMEOUT_MS)
    }

    // Auto Retry
    fun isAutoRetryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_RETRY_ENABLED, DEFAULT_AUTO_RETRY_ENABLED)
    }

    // Default Approval Policy
    fun getDefaultApprovalPolicy(context: Context): ApprovalPolicy {
        val value = getPrefs(context).getString(KEY_DEFAULT_APPROVAL_POLICY, DEFAULT_APPROVAL_POLICY)
        return ApprovalPolicy.fromValue(value)
    }

    fun setDefaultApprovalPolicy(context: Context, policy: ApprovalPolicy) {
        getPrefs(context).edit { putString(KEY_DEFAULT_APPROVAL_POLICY, policy.value) }
    }

    // Debug Logging
    fun isDebugLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_LOGGING_ENABLED, DEFAULT_DEBUG_LOGGING_ENABLED)
    }

    // Keep Screen On
    fun isKeepScreenOnEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)
    }

    // Reset all preferences to defaults
    fun resetToDefaults(context: Context) {
        getPrefs(context).edit { clear() }
    }

    fun migrateLegacyDefaultPreferences(context: Context) {
        val targetPrefs = getPrefs(context)
        if (targetPrefs.getBoolean(KEY_MIGRATED_FROM_DEFAULT_PREFS, false)) {
            return
        }

        val sourcePrefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (sourcePrefs === targetPrefs) {
            targetPrefs.edit { putBoolean(KEY_MIGRATED_FROM_DEFAULT_PREFS, true) }
            return
        }

        val sourceValues = sourcePrefs.all
        targetPrefs.edit {
            for (key in migratableKeys) {
                if (targetPrefs.contains(key) || !sourceValues.containsKey(key)) {
                    continue
                }
                when (val value = sourceValues[key]) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            putBoolean(KEY_MIGRATED_FROM_DEFAULT_PREFS, true)
        }
    }

    private fun getLongPreference(context: Context, key: String, defaultValue: Long): Long {
        val prefs = getPrefs(context)
        return try {
            prefs.getLong(key, defaultValue)
        } catch (_: ClassCastException) {
            prefs.getString(key, null)?.toLongOrNull() ?: defaultValue
        }
    }
}
