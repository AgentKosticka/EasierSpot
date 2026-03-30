package com.agentkosticka.easierspot.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object ThemePreferences {
    private const val PREFS_NAME = "ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    enum class ThemeMode(val value: String, val appCompatMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromValue(value: String?): ThemeMode {
                return entries.firstOrNull { it.value == value } ?: SYSTEM
            }
        }
    }

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromValue(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.value))
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_THEME_MODE, mode.value)
            }
    }

    fun applyThemeMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode(context).appCompatMode)
    }
}
