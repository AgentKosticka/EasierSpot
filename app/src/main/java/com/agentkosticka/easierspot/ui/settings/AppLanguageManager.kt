package com.agentkosticka.easierspot.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.agentkosticka.easierspot.util.LogUtils

object AppLanguageManager {
    private const val TAG = "AppLanguageManager"
    private const val LANGUAGE_SYSTEM = "system"

    fun applySavedLanguage(context: Context) {
        applyLanguageTag(AppPreferences.getAppLanguage(context))
    }

    fun persistAndApplyLanguage(context: Context, languageTag: String) {
        AppPreferences.setAppLanguage(context, languageTag)
        applyLanguageTag(languageTag)
    }

    private fun applyLanguageTag(languageTag: String) {
        val localeList = if (languageTag == LANGUAGE_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        try {
            AppCompatDelegate.setApplicationLocales(localeList)
        } catch (throwable: Throwable) {
            LogUtils.w(TAG, "Failed to apply app language '$languageTag'", throwable)
        }
    }
}
