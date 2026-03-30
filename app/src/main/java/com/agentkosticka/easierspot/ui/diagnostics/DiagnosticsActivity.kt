package com.agentkosticka.easierspot.ui.diagnostics

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.ui.dialogs.HotspotTestDialog
import com.agentkosticka.easierspot.ui.server.ShizukuHelper

class DiagnosticsActivity : AppCompatActivity() {
    private val hotspotManager by lazy { HotspotManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        title = getString(R.string.title_diagnostics)

        val runButton = findViewById<Button>(R.id.btn_run_hotspot_diagnostics)
        runButton.setOnClickListener {
            runHotspotTest()
        }
    }

    private fun runHotspotTest() {
        ShizukuHelper.requestShizukuPermission(
            this,
            onGranted = {
                val diagnostics = hotspotManager.getHotspotDiagnostics()
                HotspotTestDialog.newInstance(diagnostics)
                    .show(supportFragmentManager, "diagnostics_dialog")
            },
            onDenied = {
                Toast.makeText(this, "Shizuku permission required for diagnostics", Toast.LENGTH_LONG).show()
            }
        )
    }
}
