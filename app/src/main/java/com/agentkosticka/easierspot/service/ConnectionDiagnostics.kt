package com.agentkosticka.easierspot.service

import android.content.Context
import androidx.core.content.edit
import java.text.DateFormat
import java.util.Date

/** Small, durable, credential-free history for the latest client connection attempt. */
class ConnectionDiagnostics(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun begin(target: String) {
        prefs.edit {
            putLong(KEY_STARTED, System.currentTimeMillis())
            putString(KEY_TARGET, "paired-phone-${target.hashCode().toUInt().toString(16).take(6)}")
            putString(KEY_EVENTS, "")
            remove(KEY_RESULT)
        }
        event("attempt_started")
    }

    @Synchronized
    fun event(message: String) {
        val existing = prefs.getString(KEY_EVENTS, "").orEmpty()
        val entry = "${System.currentTimeMillis()} ${sanitize(message)}"
        prefs.edit { putString(KEY_EVENTS, (existing + entry + "\n").takeLast(8_000)) }
    }

    fun finish(result: String) {
        event("result=${sanitize(result)}")
        prefs.edit { putString(KEY_RESULT, sanitize(result)) }
    }

    fun report(): String {
        val started = prefs.getLong(KEY_STARTED, 0L)
        if (started == 0L) return "No EasierSpot connection attempt has been recorded yet."
        return buildString {
            appendLine("EasierSpot connection report")
            appendLine("Started: ${DateFormat.getDateTimeInstance().format(Date(started))}")
            appendLine("Target: ${prefs.getString(KEY_TARGET, "unknown")}")
            appendLine("Result: ${prefs.getString(KEY_RESULT, "in progress")}")
            appendLine("Timeline (elapsed milliseconds):")
            prefs.getString(KEY_EVENTS, "").orEmpty().lineSequence().filter(String::isNotBlank)
                .forEach { raw ->
                    val split = raw.indexOf(' ')
                    val time = raw.substring(0, split).toLongOrNull() ?: started
                    appendLine("+${time - started} ${raw.substring(split + 1)}")
                }
        }.trim()
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(password|passphrase|psk)\\s*[=:]\\s*\\S+"), "$1=<redacted>")
        .replace('\n', ' ')
        .take(500)

    companion object {
        private const val PREFS = "connection_diagnostics_v1"
        private const val KEY_STARTED = "started"
        private const val KEY_TARGET = "target"
        private const val KEY_EVENTS = "events"
        private const val KEY_RESULT = "result"
    }
}
