package com.agentkosticka.easierspot.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ui.diagnostics.DiagnosticsActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.title_settings)

        val modeSpinner = findViewById<Spinner>(R.id.spinner_theme_mode)
        val diagnosticsButton = findViewById<Button>(R.id.btn_open_diagnostics)

        val labels = listOf(
            getString(R.string.theme_mode_system),
            getString(R.string.theme_mode_light),
            getString(R.string.theme_mode_dark)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modeSpinner.adapter = adapter

        val currentMode = ThemePreferences.getThemeMode(this)
        modeSpinner.setSelection(
            when (currentMode) {
                ThemePreferences.ThemeMode.SYSTEM -> 0
                ThemePreferences.ThemeMode.LIGHT -> 1
                ThemePreferences.ThemeMode.DARK -> 2
            }
        )

        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedMode = when (position) {
                    1 -> ThemePreferences.ThemeMode.LIGHT
                    2 -> ThemePreferences.ThemeMode.DARK
                    else -> ThemePreferences.ThemeMode.SYSTEM
                }
                if (selectedMode != ThemePreferences.getThemeMode(this@SettingsActivity)) {
                    ThemePreferences.setThemeMode(this@SettingsActivity, selectedMode)
                    ThemePreferences.applyThemeMode(this@SettingsActivity)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }
}
