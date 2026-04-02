package com.agentkosticka.easierspot.update

import android.content.Context
import com.agentkosticka.easierspot.util.LogUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

object UpdateCheckPreferences {
    private const val TAG = "UpdateCheckPrefs"
    private const val CACHE_FILE_NAME = "update_check_cache.properties"
    private const val KEY_LAST_CHECK_EPOCH_MS = "last_check_epoch_ms"
    private const val KEY_LATEST_VERSION_NAME = "latest_version_name"
    private const val KEY_UPDATE_AVAILABLE = "update_available"

    @Synchronized
    fun getLastCheckEpochMs(context: Context): Long {
        return load(context).getProperty(KEY_LAST_CHECK_EPOCH_MS)?.toLongOrNull() ?: 0L
    }

    @Synchronized
    fun setLastCheckEpochMs(context: Context, value: Long) {
        val props = load(context)
        props.setProperty(KEY_LAST_CHECK_EPOCH_MS, value.toString())
        save(context, props)
    }

    @Synchronized
    fun getLatestVersionName(context: Context): String? {
        return load(context).getProperty(KEY_LATEST_VERSION_NAME)
    }

    @Synchronized
    fun isUpdateAvailable(context: Context): Boolean {
        return load(context).getProperty(KEY_UPDATE_AVAILABLE)?.toBooleanStrictOrNull() ?: false
    }

    @Synchronized
    fun setLatestVersionState(context: Context, latestVersionName: String, updateAvailable: Boolean) {
        val props = load(context)
        props.setProperty(KEY_LATEST_VERSION_NAME, latestVersionName)
        props.setProperty(KEY_UPDATE_AVAILABLE, updateAvailable.toString())
        save(context, props)
    }

    @Synchronized
    fun setState(context: Context, lastCheckEpochMs: Long, latestVersionName: String, updateAvailable: Boolean) {
        val props = load(context)
        props.setProperty(KEY_LAST_CHECK_EPOCH_MS, lastCheckEpochMs.toString())
        props.setProperty(KEY_LATEST_VERSION_NAME, latestVersionName)
        props.setProperty(KEY_UPDATE_AVAILABLE, updateAvailable.toString())
        save(context, props)
    }

    private fun cacheFile(context: Context) = java.io.File(context.cacheDir, CACHE_FILE_NAME)

    private fun load(context: Context): Properties {
        val props = Properties()
        val file = cacheFile(context)
        if (!file.exists()) return props
        try {
            FileInputStream(file).use { props.load(it) }
        } catch (e: Exception) {
            LogUtils.w(TAG, "Failed to read update cache file", e)
        }
        return props
    }

    private fun save(context: Context, properties: Properties) {
        val file = cacheFile(context)
        try {
            FileOutputStream(file).use {
                properties.store(it, "EasierSpot update cache")
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to write update cache file", e)
        }
    }
}
