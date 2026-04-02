package com.agentkosticka.easierspot.util

import android.content.Context
import android.util.Log
import com.agentkosticka.easierspot.ui.settings.AppPreferences

/**
 * Configurable logging utility to reduce log spam.
 * Set LOG_LEVEL to control verbosity across the app.
 */
object LogUtils {
    /**
     * Log levels in order of verbosity (most verbose first).
     */
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Current log level. Messages below this level are suppressed.
     * Change to DEBUG or INFO for production, VERBOSE for debugging.
     */
    var LOG_LEVEL: Level = Level.INFO

    /**
     * Whether to include detailed diagnostic logging (for hotspot testing).
     */
    var DIAGNOSTIC_MODE: Boolean = false

    /**
     * Application context for accessing preferences.
     * Should be set once at app startup.
     */
    var appContext: Context? = null

    @Suppress("unused")
    fun v(tag: String, message: String) {
        if (LOG_LEVEL <= Level.VERBOSE) {
            Log.v(tag, message)
        }
    }

    @Suppress("unused")
    fun d(tag: String, message: String) {
        val debugEnabled = appContext?.let { AppPreferences.isDebugLoggingEnabled(it) } ?: true
        if (debugEnabled && LOG_LEVEL <= Level.DEBUG) {
            Log.d(tag, message)
        }
    }

    @Suppress("unused")
    fun i(tag: String, message: String) {
        if (LOG_LEVEL <= Level.INFO) {
            Log.i(tag, message)
        }
    }

    @Suppress("unused")
    fun w(tag: String, message: String) {
        if (LOG_LEVEL <= Level.WARN) {
            Log.w(tag, message)
        }
    }

    @Suppress("unused")
    fun w(tag: String, message: String, throwable: Throwable) {
        if (LOG_LEVEL <= Level.WARN) {
            Log.w(tag, message, throwable)
        }
    }

    @Suppress("unused")
    fun e(tag: String, message: String) {
        if (LOG_LEVEL <= Level.ERROR) {
            Log.e(tag, message)
        }
    }

    @Suppress("unused")
    fun e(tag: String, message: String, throwable: Throwable) {
        if (LOG_LEVEL <= Level.ERROR) {
            Log.e(tag, message, throwable)
        }
    }

    /**
     * Diagnostic logging - only outputs when DIAGNOSTIC_MODE is enabled.
     * Used for detailed debugging during hotspot testing.
     */
    fun diag(tag: String, message: String) {
        if (DIAGNOSTIC_MODE) {
            Log.d(tag, "[DIAG] $message")
        }
    }
}
