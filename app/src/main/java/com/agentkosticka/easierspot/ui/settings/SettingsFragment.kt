package com.agentkosticka.easierspot.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.BuildConfig
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.shared.SystemWifiPickerIntegration
import com.agentkosticka.easierspot.shared.SystemWifiPickerState
import com.agentkosticka.easierspot.ui.diagnostics.DiagnosticsActivity
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.service.BleClientService
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        setupBackgroundDiscoveryPreference()
        setupSystemWifiPickerStatus()
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

    private fun setupSystemWifiPickerStatus() {
        val pref = findPreference<Preference>("system_wifi_picker_status") ?: return
        pref.summary = getString(R.string.pref_system_wifi_picker_checking)
        lifecycleScope.launch(Dispatchers.IO) {
            val diagnostics = SystemWifiPickerIntegration.reconcile(
                requireContext().applicationContext
            )
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                pref.summary = when (diagnostics.state) {
                    SystemWifiPickerState.NATIVE_REMOTE_ENTRIES ->
                        getString(R.string.pref_system_wifi_picker_native_active)
                    SystemWifiPickerState.SUGGESTION_ACTIVE ->
                        getString(
                            R.string.pref_system_wifi_picker_fallback_active,
                            diagnostics.pickerSelectableSuggestionCount
                        )
                    SystemWifiPickerState.SUGGESTION_READY ->
                        getString(R.string.pref_system_wifi_picker_fallback_ready)
                    SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH ->
                        getString(R.string.pref_system_wifi_picker_fallback_refresh)
                    SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING ->
                        getString(R.string.pref_system_wifi_picker_fallback_pending)
                    SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED ->
                        getString(R.string.pref_system_wifi_picker_fallback_rejected)
                }
                pref.setOnPreferenceClickListener {
                    val dialog = AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_system_wifi_picker_title)
                        .setMessage(diagnostics.report())
                        .setPositiveButton(android.R.string.ok, null)
                    if (diagnostics.state == SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED) {
                        dialog.setNeutralButton(R.string.pref_system_wifi_picker_open_wifi_settings) { _, _ ->
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                    }
                    dialog.show()
                    true
                }
            }
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

        findPreference<ListPreference>("wifi_connection_mode")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as String
                val mode = AppPreferences.WifiConnectionMode.fromValue(value)
                AppPreferences.setWifiConnectionMode(requireContext(), mode)
                if (mode == AppPreferences.WifiConnectionMode.SHIZUKU_FORCE) {
                    val app = requireContext().applicationContext
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        TrustedServerStore(app).all()
                            .distinctBy { it.ssid }
                            .forEach { profile ->
                                val securityType = runCatching {
                                    HotspotCredentials.SecurityType.valueOf(profile.securityType)
                                }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
                                WifiSuggestionInstaller.setAutojoinForOwnedSuggestion(
                                    app,
                                    profile.ssid,
                                    securityType,
                                    profile.isHidden,
                                    enabled = false
                                )
                            }
                    }
                }
                updateWifiConnectionModeSummary(pref, mode)
                Toast.makeText(
                    requireContext(),
                    R.string.wifi_mode_switch_hint,
                    Toast.LENGTH_LONG
                ).show()
                true
            }
            val mode = AppPreferences.getWifiConnectionMode(requireContext())
            pref.value = mode.value
            updateWifiConnectionModeSummary(pref, mode)
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

    private fun setupBackgroundDiscoveryPreference() {
        findPreference<SwitchPreferenceCompat>("background_discovery_enabled")?.let { pref ->
            pref.isChecked = AppPreferences.isBackgroundDiscoveryEnabled(requireContext())
            pref.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                AppPreferences.setBackgroundDiscoveryEnabled(requireContext(), enabled)
                if (enabled) {
                    runCatching { BleClientService.start(requireContext()) }
                        .onFailure { LogUtils.w(TAG, "Could not start background discovery", it) }
                } else {
                    BleClientService.stop(requireContext())
                }
                true
            }
        }
    }

    private fun updateWifiConnectionModeSummary(
        pref: Preference,
        mode: AppPreferences.WifiConnectionMode
    ) {
        pref.summary = when (mode) {
            AppPreferences.WifiConnectionMode.AUTO -> getString(R.string.pref_wifi_mode_auto)
            AppPreferences.WifiConnectionMode.SUGGESTION -> getString(R.string.pref_wifi_mode_suggestion)
            AppPreferences.WifiConnectionMode.SHIZUKU_FORCE -> getString(R.string.pref_wifi_mode_shizuku_force)
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
