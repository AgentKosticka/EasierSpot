package com.agentkosticka.easierspot.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.agentkosticka.easierspot.BuildConfig
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ui.diagnostics.DiagnosticsActivity
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.util.LogUtils

class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private const val TAG = "SettingsFragment"
    }


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppPreferences.PREFS_NAME
        AppPreferences.migrateLegacyDefaultPreferences(requireContext())
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Setup preference listeners for immediate changes
        setupThemeListener()
        setupListPreferenceSummaries()
        setupEditTextPreferenceSummaries()
        setupClickablePreferences()
    }

    private fun setupThemeListener() {
        findPreference<ListPreference>("theme_mode")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val selectedMode = when (newValue as String) {
                    "light" -> ThemePreferences.ThemeMode.LIGHT
                    "dark" -> ThemePreferences.ThemeMode.DARK
                    else -> ThemePreferences.ThemeMode.SYSTEM
                }
                ThemePreferences.setThemeMode(requireContext(), selectedMode)
                ThemePreferences.applyThemeMode(requireContext())
                updateThemeSummary(pref, newValue)
                true
            }
            
            // Initialize theme preference with current value
            val currentMode = ThemePreferences.getThemeMode(requireContext())
            val currentValue = when (currentMode) {
                ThemePreferences.ThemeMode.LIGHT -> "light"
                ThemePreferences.ThemeMode.DARK -> "dark"
                ThemePreferences.ThemeMode.SYSTEM -> "system"
            }
            pref.value = currentValue
            updateThemeSummary(pref, currentValue)
        }
    }

    private fun setupClickablePreferences() {
        // Diagnostics preference
        findPreference<Preference>("diagnostics")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DiagnosticsActivity::class.java))
            true
        }

        // View permissions preference
        findPreference<Preference>("view_permissions")?.setOnPreferenceClickListener {
            startActivity(
                Intent(requireContext(), PermissionsActivity::class.java).putExtra(
                    PermissionsActivity.EXTRA_VIEW_ONLY,
                    true
                )
            )
            true
        }

        // Help & About preference
        findPreference<Preference>("help_about")?.setOnPreferenceClickListener {
            showHelpAboutDialog()
            true
        }
    }

    private fun updateThemeSummary(pref: Preference, value: String) {
        val summary = when (value) {
            "light" -> getString(R.string.theme_mode_light)
            "dark" -> getString(R.string.theme_mode_dark)
            else -> getString(R.string.theme_mode_system)
        }
        pref.summary = summary
    }

    private fun setupListPreferenceSummaries() {
        // BLE Advertising Interval
        findPreference<ListPreference>("ble_advertising_interval")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                updateListPreferenceSummary(pref, newValue as String)
                true
            }
            val currentValue = AppPreferences.getBleAdvertisingInterval(requireContext()).value
            pref.value = currentValue
            updateListPreferenceSummary(pref, currentValue)
        }

        // BLE Broadcast Strength
        findPreference<ListPreference>("broadcast_strength")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                updateListPreferenceSummary(pref, newValue as String)
                true
            }
            val currentValue = AppPreferences.getBroadcastStrength(requireContext()).value
            pref.value = currentValue
            updateListPreferenceSummary(pref, currentValue)
        }

        // Default Approval Policy
        findPreference<ListPreference>("default_approval_policy")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                updateListPreferenceSummary(pref, newValue as String)
                true
            }
            val currentValue = AppPreferences.getDefaultApprovalPolicy(requireContext()).value
            pref.value = currentValue
            updateListPreferenceSummary(pref, currentValue)
        }

        // App language
        findPreference<ListPreference>("app_language")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val languageTag = newValue as String
                updateAppLanguageSummary(pref, languageTag)
                AppLanguageManager.persistAndApplyLanguage(requireContext(), languageTag)
                true
            }
            val currentValue = AppPreferences.getAppLanguage(requireContext())
            pref.value = currentValue
            updateAppLanguageSummary(pref, currentValue)
        }
    }

    private fun setupEditTextPreferenceSummaries() {
        // Scan Timeout
        findPreference<EditTextPreference>("scan_timeout_ms")?.let { pref ->
            pref.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            pref.setOnPreferenceChangeListener { _, newValue ->
                when (val timeout = (newValue as String).toLongOrNull()) {
                    null -> {
                        showToast(R.string.validation_error_invalid_number)
                        false
                    }
                    !in 5000..300000 -> {
                        showToast(R.string.validation_error_scan_timeout)
                        false
                    }
                    else -> {
                        pref.summary = getString(R.string.pref_scan_timeout_summary_format, timeout / 1000)
                        true
                    }
                }
            }
            val currentValue = AppPreferences.getScanTimeoutMs(requireContext())
            pref.text = currentValue.toString()
            pref.summary = getString(R.string.pref_scan_timeout_summary_format, currentValue / 1000)
        }

    }

    private fun updateListPreferenceSummary(pref: Preference, value: String) {
        when (pref.key) {
            "ble_advertising_interval" -> {
                val summary = when (value) {
                    "slow" -> getString(R.string.pref_ble_advertising_interval_slow)
                    "balanced" -> getString(R.string.pref_ble_advertising_interval_balanced)
                    "frequent" -> getString(R.string.pref_ble_advertising_interval_frequent)
                    else -> value
                }
                pref.summary = summary
                AppPreferences.setBleAdvertisingInterval(
                    requireContext(),
                    AppPreferences.AdvertisingInterval.fromValue(value)
                )
            }
            "broadcast_strength" -> {
                val summary = when (value) {
                    "low" -> getString(R.string.pref_broadcast_strength_low)
                    "medium" -> getString(R.string.pref_broadcast_strength_medium)
                    "high" -> getString(R.string.pref_broadcast_strength_high)
                    else -> value
                }
                pref.summary = summary
                AppPreferences.setBroadcastStrength(
                    requireContext(),
                    AppPreferences.BroadcastStrength.fromValue(value)
                )
            }
            "default_approval_policy" -> {
                val summary = when (value) {
                    "ask" -> getString(R.string.pref_default_approval_policy_ask)
                    "approve" -> getString(R.string.pref_default_approval_policy_approve)
                    "deny" -> getString(R.string.pref_default_approval_policy_deny)
                    else -> value
                }
                pref.summary = summary
                AppPreferences.setDefaultApprovalPolicy(
                    requireContext(),
                    AppPreferences.ApprovalPolicy.fromValue(value)
                )
            }
        }
    }

    private fun updateAppLanguageSummary(pref: Preference, value: String) {
        pref.summary = when (value) {
            "en" -> getString(R.string.pref_app_language_english)
            else -> getString(R.string.pref_app_language_system)
        }
    }

    private fun showHelpAboutDialog() {
        val message = getString(R.string.help_about_dialog_message, BuildConfig.VERSION_NAME)
        val spannedMessage = Html.fromHtml(message, Html.FROM_HTML_MODE_LEGACY)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.help_about_dialog_title)
            .setMessage(spannedMessage)
            .setPositiveButton(R.string.help_about_dialog_ok, null)
            .show()
    }

    private fun showToast(@StringRes messageResId: Int) {
        if (messageResId == 0) {
            LogUtils.w(TAG, "Blocked invalid toast resource ID 0x0")
            Toast.makeText(requireContext(), getString(R.string.validation_error_generic), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
    }
}
