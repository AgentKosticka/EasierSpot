package com.agentkosticka.easierspot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.update.UpdateCheckCoordinator
import com.agentkosticka.easierspot.update.UpdateChecker
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.ui.permissions.AppPermissions
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import com.agentkosticka.easierspot.ui.server.ServerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateText: TextView
    private val updateStateListener: (UpdateChecker.State) -> Unit = { state ->
        runOnUiThread { renderUpdateBanner(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppPermissions.hasRequiredRuntimePermissions(this)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        updateBanner = findViewById(R.id.update_warning_banner)
        updateText = findViewById(R.id.tv_update_warning_text)

        val clientModeButton = findViewById<android.widget.Button>(R.id.btn_client_mode)
        val serverModeButton = findViewById<android.widget.Button>(R.id.btn_server_mode)
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.btn_settings)

        clientModeButton.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        serverModeButton.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateBanner.setOnClickListener { openLatestReleasePage() }
        renderUpdateBanner(UpdateChecker.getState(this))
        UpdateCheckCoordinator.addListener(updateStateListener)
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateCheckCoordinator.removeListener(updateStateListener)
        mainScope.cancel()
    }

    private fun refreshUpdateState() {
        mainScope.launch {
            val state = withContext(Dispatchers.IO) {
                UpdateChecker.refreshIfStale(applicationContext)
            }
            renderUpdateBanner(state)
        }
    }

    private fun renderUpdateBanner(state: UpdateChecker.State) {
        if (state.updateAvailable) {
            val latest = state.latestVersionName
            updateText.text = if (!latest.isNullOrBlank()) {
                getString(R.string.update_available_with_version, latest)
            } else {
                getString(R.string.update_available_default)
            }
            updateBanner.visibility = View.VISIBLE
        } else {
            updateBanner.visibility = View.GONE
        }
    }

    private fun openLatestReleasePage() {
        val intent = Intent(Intent.ACTION_VIEW, UpdateChecker.LATEST_RELEASE_URL.toUri())
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.update_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

}
