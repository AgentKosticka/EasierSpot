package com.agentkosticka.easierspot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.update.UpdateCheckCoordinator
import com.agentkosticka.easierspot.update.UpdateChecker
import com.agentkosticka.easierspot.ui.client.ClientActivity
import com.agentkosticka.easierspot.ui.permissions.AppPermissions
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import com.agentkosticka.easierspot.ui.server.ShizukuHelper
import com.agentkosticka.easierspot.ui.server.ServerActivity
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.hotspot.HotspotClientRegistry
import com.agentkosticka.easierspot.service.BleClientService
import com.agentkosticka.easierspot.service.BleHotspotService
import com.agentkosticka.easierspot.service.ClientConnectionState
import com.agentkosticka.easierspot.service.ConnectionDiagnostics
import com.agentkosticka.easierspot.service.titleAndText
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
    private lateinit var serverStatus: TextView
    private lateinit var clientStatus: TextView
    private lateinit var setupHealth: TextView
    private lateinit var serverButton: android.widget.Button
    private var connectedClients: List<BleHotspotService.ConnectedClientSummary> = emptyList()
    private var externalClients: List<HotspotClientRegistry.ExternalClientSummary> = emptyList()
    private val updateStateListener: (UpdateChecker.State) -> Unit = { state ->
        runOnUiThread { renderUpdateBanner(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateBanner = findViewById(R.id.update_warning_banner)
        updateText = findViewById(R.id.tv_update_warning_text)
        serverStatus = findViewById(R.id.tv_server_status)
        clientStatus = findViewById(R.id.tv_client_status)
        setupHealth = findViewById(R.id.tv_setup_health)

        val clientModeButton = findViewById<android.widget.Button>(R.id.btn_client_mode)
        serverButton = findViewById(R.id.btn_server_mode)
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.btn_settings)

        clientModeButton.setOnClickListener {
            if (AppPermissions.hasRequiredRuntimePermissions(this, AppPermissions.Role.CLIENT)) {
                startActivity(Intent(this, ClientActivity::class.java))
            } else {
                startActivity(Intent(this, PermissionsActivity::class.java).apply {
                    putExtra(PermissionsActivity.EXTRA_VIEW_ONLY, true)
                    putExtra(PermissionsActivity.EXTRA_ROLE, AppPermissions.Role.CLIENT.name)
                })
            }
        }

        serverButton.setOnClickListener {
            if (!AppPermissions.hasRequiredRuntimePermissions(this, AppPermissions.Role.SERVER)) {
                startActivity(Intent(this, PermissionsActivity::class.java).apply {
                    putExtra(PermissionsActivity.EXTRA_VIEW_ONLY, true)
                    putExtra(PermissionsActivity.EXTRA_ROLE, AppPermissions.Role.SERVER.name)
                })
            } else if (BleHotspotService.isServerRunning) stopSharing() else requestAndStartSharing()
        }
        findViewById<View>(R.id.btn_manage_sharing).setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_copy_report).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("EasierSpot connection report", ConnectionDiagnostics(this).report())
            )
            Toast.makeText(this, R.string.dashboard_report_copied, Toast.LENGTH_SHORT).show()
        }

        updateBanner.setOnClickListener { openLatestReleasePage() }
        renderUpdateBanner(UpdateChecker.getState(this))
        UpdateCheckCoordinator.addListener(updateStateListener)
        BleHotspotService.restoreIfEnabled(this)
        mainScope.launch {
            BleHotspotService.serverState.collect { renderServerState(it) }
        }
        mainScope.launch {
            BleHotspotService.connectedClients.collect { clients ->
                connectedClients = clients.filterNot { client ->
                    HotspotClientRegistry.isExternalLifecycleLease(client.stableId)
                }
                if (connectedClients.isNotEmpty()) {
                    serverStatus.text = resources.getQuantityString(
                        R.plurals.dashboard_server_connected_clients,
                        connectedClients.size,
                        connectedClients.size
                    )
                }
            }
        }
        mainScope.launch {
            HotspotClientRegistry.externalClients.collect { clients ->
                externalClients = clients
            }
        }
        mainScope.launch {
            BleClientService.connectionState.collect { state ->
                val (title, detail) = state.titleAndText()
                clientStatus.text = getString(R.string.dashboard_connection_status, title, detail)
                refreshSetupHealth()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
        refreshSetupHealth()
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

    private fun requestAndStartSharing() {
        ShizukuHelper.requestShizukuPermission(
            this,
            onGranted = { startSharing() },
            onDenied = {
                renderServerState(BleHotspotService.ServerState.NEEDS_SHIZUKU)
                refreshSetupHealth()
            }
        )
    }

    private fun startSharing() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BleHotspotService::class.java).apply {
                action = BleHotspotService.ACTION_START_SERVER
            }
        )
    }

    private fun stopSharing() {
        val affected = buildList {
            if (connectedClients.isNotEmpty()) {
                add("EasierSpot\n" + connectedClients.joinToString("\n") { "• ${it.label}" })
            }
            if (externalClients.isNotEmpty()) {
                add("External\n" + externalClients.joinToString("\n") { "• ${it.label}" })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.stop_sharing_confirm_title)
            .setMessage(
                if (affected.isEmpty()) getString(R.string.stop_sharing_no_clients)
                else getString(R.string.stop_sharing_with_clients, affected.joinToString("\n\n"))
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.stop_sharing) { _, _ ->
                startService(Intent(this, BleHotspotService::class.java).apply {
                    action = BleHotspotService.ACTION_STOP_SERVER
                })
            }
            .show()
    }

    private fun renderServerState(state: BleHotspotService.ServerState) {
        serverStatus.setText(
            when (state) {
                BleHotspotService.ServerState.STOPPED -> R.string.dashboard_share_off
                BleHotspotService.ServerState.STARTING -> R.string.dashboard_server_starting
                BleHotspotService.ServerState.NEEDS_SHIZUKU -> R.string.dashboard_server_waiting_shizuku
                BleHotspotService.ServerState.ADVERTISING -> R.string.dashboard_server_advertising
                BleHotspotService.ServerState.CLIENT_PENDING -> R.string.dashboard_server_pending
                BleHotspotService.ServerState.HOTSPOT_STARTING -> R.string.dashboard_server_hotspot
                BleHotspotService.ServerState.SHARING -> R.string.dashboard_server_sharing
                BleHotspotService.ServerState.PAUSED_FOR_AUDIO -> R.string.dashboard_server_audio
                BleHotspotService.ServerState.DEGRADED -> R.string.dashboard_server_attention
            }
        )
        serverButton.setText(
            if (state == BleHotspotService.ServerState.STOPPED) {
                R.string.dashboard_enable_sharing
            } else {
                R.string.dashboard_disable_sharing
            }
        )
        refreshSetupHealth()
    }

    private fun refreshSetupHealth() {
        val bluetoothReady = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val notificationsReady = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val shizukuReady = ShizukuHelper.hasShizukuPermission()
        val alertsEnabled = AppPreferences.isBackgroundDiscoveryEnabled(this)
        mainScope.launch {
            val pairedCount = withContext(Dispatchers.IO) {
                runCatching { TrustedServerStore(applicationContext).all().size }.getOrDefault(0)
            }
            setupHealth.text = buildString {
                append(if (bluetoothReady) "✓" else "!").append(" Bluetooth\n")
                append(if (notificationsReady) "✓" else "!").append(" Nearby notifications\n")
                append(if (shizukuReady) "✓" else "!").append(" Shizuku for sharing\n")
                append(if (alertsEnabled) "✓" else "–").append(" Background alerts\n")
                append(pairedCount).append(if (pairedCount == 1) " paired phone" else " paired phones")
            }
        }
    }

}
