package com.agentkosticka.easierspot.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.agentkosticka.easierspot.hotspot.HotspotDiagnostics

/**
 * Dialog to display hotspot configuration test results.
 */
class HotspotTestDialog : DialogFragment() {

    companion object {
        private const val ARG_DIAGNOSTICS_RUNNING = "shizuku_running"
        private const val ARG_DIAGNOSTICS_PERMISSION = "shizuku_permission"
        private const val ARG_DIAGNOSTICS_BINDER = "wifi_binder"
        private const val ARG_DIAGNOSTICS_MANAGER = "wifi_manager"
        private const val ARG_DIAGNOSTICS_CONFIG = "softap_config"
        private const val ARG_DIAGNOSTICS_SSID = "ssid"
        private const val ARG_DIAGNOSTICS_PASSPHRASE = "passphrase"
        private const val ARG_DIAGNOSTICS_ERROR = "error"

        fun newInstance(diagnostics: HotspotDiagnostics): HotspotTestDialog {
            return HotspotTestDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_DIAGNOSTICS_RUNNING, diagnostics.shizukuRunning)
                    putBoolean(ARG_DIAGNOSTICS_PERMISSION, diagnostics.shizukuPermissionGranted)
                    putBoolean(ARG_DIAGNOSTICS_BINDER, diagnostics.wifiBinderObtained)
                    putBoolean(ARG_DIAGNOSTICS_MANAGER, diagnostics.wifiManagerObtained)
                    putBoolean(ARG_DIAGNOSTICS_CONFIG, diagnostics.softApConfigObtained)
                    putString(ARG_DIAGNOSTICS_SSID, diagnostics.ssidExtracted)
                    putBoolean(ARG_DIAGNOSTICS_PASSPHRASE, diagnostics.passphraseExtracted)
                    putString(ARG_DIAGNOSTICS_ERROR, diagnostics.errorMessage)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val shizukuRunning = args.getBoolean(ARG_DIAGNOSTICS_RUNNING)
        val shizukuPermission = args.getBoolean(ARG_DIAGNOSTICS_PERMISSION)
        val wifiBinder = args.getBoolean(ARG_DIAGNOSTICS_BINDER)
        val wifiManager = args.getBoolean(ARG_DIAGNOSTICS_MANAGER)
        val softApConfig = args.getBoolean(ARG_DIAGNOSTICS_CONFIG)
        val ssid = args.getString(ARG_DIAGNOSTICS_SSID)
        val passphrase = args.getBoolean(ARG_DIAGNOSTICS_PASSPHRASE)
        val error = args.getString(ARG_DIAGNOSTICS_ERROR)

        // Build diagnostic text
        val diagnosticText = buildString {
            appendLine("═══ Hotspot Config Test ═══")
            appendLine()
            appendLine("${statusIcon(shizukuRunning)} Shizuku Running")
            appendLine("${statusIcon(shizukuPermission)} Shizuku Permission")
            appendLine("${statusIcon(wifiBinder)} WiFi Binder Obtained")
            appendLine("${statusIcon(wifiManager)} IWifiManager Interface")
            appendLine("${statusIcon(softApConfig)} SoftApConfiguration")
            appendLine()
            appendLine("═══ Results ═══")
            appendLine()
            if (!ssid.isNullOrEmpty()) {
                appendLine("✅ SSID: $ssid")
            } else {
                appendLine("❌ SSID: (not retrieved)")
            }
            appendLine("${statusIcon(passphrase)} Passphrase: ${if (passphrase) "obtained" else "not obtained"}")
            
            if (!error.isNullOrEmpty()) {
                appendLine()
                appendLine("═══ Error ═══")
                appendLine()
                appendLine("⚠️ $error")
            }
            
            appendLine()
            appendLine("═══ Summary ═══")
            appendLine()
            val allPassed = shizukuRunning && shizukuPermission && wifiBinder && 
                           wifiManager && softApConfig && !ssid.isNullOrEmpty() && passphrase
            if (allPassed) {
                appendLine("✅ All checks passed! Hotspot sharing should work.")
            } else {
                appendLine("❌ Some checks failed. See above for details.")
            }
        }

        // Create scrollable text view
        val textView = TextView(requireContext()).apply {
            text = diagnosticText
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Hotspot Config Test")
            .setView(scrollView)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .create()
    }

    private fun statusIcon(success: Boolean): String = if (success) "✅" else "❌"
}
