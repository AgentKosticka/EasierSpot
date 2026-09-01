package com.agentkosticka.easierspot.ui.client

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.client.BleDiscoveryRegistrar
import com.agentkosticka.easierspot.ble.client.BleScanner
import com.agentkosticka.easierspot.ble.client.DiscoveredServer
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.ble.client.isRecentlyPresent
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.service.BleClientService
import com.agentkosticka.easierspot.service.ClientConnectionState
import com.agentkosticka.easierspot.service.ClientRecoveryAction
import com.agentkosticka.easierspot.service.ConnectTrigger
import com.agentkosticka.easierspot.service.TrustedConnectLauncher
import com.agentkosticka.easierspot.service.titleAndText
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pairing browser. The foreground service owns connection work and survives this activity. */
class ClientActivity : AppCompatActivity() {
    private lateinit var scanner: BleScanner
    private lateinit var scanButton: Button
    private lateinit var status: TextView
    private val rows = mutableListOf<Map<String, String>>()

    private data class RowTarget(
        val trusted: TrustedServerProfile?,
        val discovered: DiscoveredServer?,
        val header: Boolean = false
    )

    private val targets = mutableListOf<RowTarget>()
    private var latestServers: List<DiscoveredServer> = emptyList()
    private var adapter: SimpleAdapter? = null
    private var renderJob: Job? = null
    private var currentConnectionState: ClientConnectionState = ClientConnectionState.Idle
    private var wifiPromptShown = false
    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (scanner.isBluetoothEnabled()) startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        title = getString(R.string.client_screen_title)
        scanner = BleScanner(this)
        scanButton = findViewById(R.id.btn_scan)
        status = findViewById(R.id.tv_client_subheading)
        findViewById<android.widget.ImageButton>(R.id.btn_client_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        adapter = SimpleAdapter(
            this,
            rows,
            android.R.layout.simple_list_item_2,
            arrayOf("name", "detail"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        findViewById<ListView>(R.id.devices_list).apply {
            adapter = this@ClientActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val target = targets.getOrNull(position) ?: return@setOnItemClickListener
                if (target.header) return@setOnItemClickListener
                if (target.trusted != null) {
                    val connected = currentConnectionState as? ClientConnectionState.Connected
                    if (connected?.serverToken == target.trusted.discoveryToken) {
                        BleClientService.disconnect(applicationContext)
                    } else {
                        TrustedConnectLauncher.connect(
                            applicationContext,
                            target.trusted.discoveryToken,
                            ConnectTrigger.CLIENT_ACTIVITY
                        )
                    }
                } else {
                    target.discovered?.let { BleClientService.pair(applicationContext, it) }
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val target = targets.getOrNull(position) ?: return@setOnItemLongClickListener false
                if (target.header) return@setOnItemLongClickListener false
                target.trusted?.let(::showPairedDeviceMenu) != null
            }
        }
        scanButton.setOnClickListener {
            if (scanner.isScanning.value) scanner.stopScan() else startScan()
        }
        lifecycleScope.launch {
            scanner.discoveredServers.collect { servers ->
                latestServers = servers
                renderRows()
            }
        }
        lifecycleScope.launch {
            scanner.isScanning.collect { scanning ->
                scanButton.text = getString(if (scanning) R.string.client_scan_stop else R.string.client_scan_start)
            }
        }
        lifecycleScope.launch {
            scanner.scanError.collect { error ->
                error?.let { Toast.makeText(this@ClientActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
        lifecycleScope.launch {
            BleClientService.connectionState.collect { state ->
                currentConnectionState = state
                if (state != ClientConnectionState.Idle) status.text = state.titleAndText().second
                renderRows()
                val wifiRecovery = state as? ClientConnectionState.Failed
                if (wifiRecovery?.recovery == ClientRecoveryAction.WIFI_SETTINGS && !wifiPromptShown) {
                    wifiPromptShown = true
                    AlertDialog.Builder(this@ClientActivity)
                        .setTitle(wifiRecovery.title)
                        .setMessage(wifiRecovery.detail)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.open_wifi_controls) { _, _ ->
                            startActivity(Intent(Settings.Panel.ACTION_WIFI))
                        }
                        .show()
                } else if (wifiRecovery?.recovery != ClientRecoveryAction.WIFI_SETTINGS) {
                    wifiPromptShown = false
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startScan()
    }

    private fun startScan() {
        if (!hasPermissions()) {
            startActivity(
                Intent(this, PermissionsActivity::class.java)
                    .putExtra(PermissionsActivity.EXTRA_VIEW_ONLY, true)
            )
            return
        }
        if (!scanner.isBluetoothEnabled()) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        scanner.startScan()
    }

    private fun hasPermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun renderRows() {
        renderJob?.cancel()
        val discovered = latestServers
        renderJob = lifecycleScope.launch {
            val trusted = withContext(Dispatchers.IO) {
                TrustedServerStore(applicationContext).all()
            }
            val now = System.currentTimeMillis()
            val byToken = discovered.associateBy { it.deviceId }
            val connectedState = currentConnectionState as? ClientConnectionState.Connected
            val available = trusted.filter { profile ->
                byToken.containsKey(profile.discoveryToken) ||
                    profile.isRecentlyPresent(now) ||
                    connectedState?.serverToken == profile.discoveryToken
            }
            val absent = trusted.filterNot(available::contains)
            val fresh = discovered.filter { server ->
                trusted.none { it.discoveryToken == server.deviceId }
            }

            targets.clear()
            rows.clear()

            fun header(label: String) {
                targets += RowTarget(null, null, header = true)
                rows += mapOf("name" to label, "detail" to "")
            }

            if (available.isNotEmpty()) {
                header("Available via EasierSpot")
                available.forEach { profile ->
                    val connected = connectedState?.takeIf {
                        it.serverToken == profile.discoveryToken
                    }
                    targets += RowTarget(profile, byToken[profile.discoveryToken])
                    rows += mapOf(
                        "name" to profile.label,
                        "detail" to if (connected != null) {
                            getString(
                                R.string.paired_connected_tap_disconnect,
                                connected.activeClientCount
                            )
                        } else {
                            "Available via EasierSpot"
                        }
                    )
                }
            }
            if (absent.isNotEmpty()) {
                header("Paired devices")
                absent.forEach { profile ->
                    targets += RowTarget(profile, null)
                    rows += mapOf("name" to profile.label, "detail" to "Not nearby")
                }
            }
            if (fresh.isNotEmpty()) {
                header("New EasierSpot devices")
                fresh.forEach { server ->
                    targets += RowTarget(null, server)
                    rows += mapOf(
                        "name" to (server.deviceName ?: getString(R.string.nearby_easierspot_phone)),
                        "detail" to getString(R.string.new_phone_tap_pair)
                    )
                }
            }
            adapter?.notifyDataSetChanged()
        }
    }

    private fun showPairedDeviceMenu(profile: TrustedServerProfile) {
        val alertLabel = if (profile.alertsEnabled) getString(R.string.disable_nearby_alerts)
        else getString(R.string.enable_nearby_alerts)
        AlertDialog.Builder(this)
            .setTitle(profile.label)
            .setItems(arrayOf(getString(R.string.rename_phone), alertLabel, getString(R.string.forget_phone))) { _, which ->
                when (which) {
                    0 -> showRenameDialog(profile)
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            TrustedServerStore(applicationContext).remember(
                                profile.copy(alertsEnabled = !profile.alertsEnabled)
                            )
                            BleDiscoveryRegistrar.reconcile(applicationContext)
                            withContext(Dispatchers.Main) { renderRows() }
                        }
                    }
                    2 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            WifiSuggestionInstaller.removeForSsid(applicationContext, profile.ssid)
                            TrustedServerStore(applicationContext).forget(profile.fingerprint)
                            NotificationManagerCompat.from(applicationContext)
                                .cancel(profile.fingerprint.hashCode())
                            BleDiscoveryRegistrar.reconcile(applicationContext)
                            withContext(Dispatchers.Main) { renderRows() }
                        }
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(profile: TrustedServerProfile) {
        val input = EditText(this).apply {
            setText(profile.nickname ?: profile.label)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_phone)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val nickname = input.text.toString().trim().ifBlank { null }
                lifecycleScope.launch(Dispatchers.IO) {
                    TrustedServerStore(applicationContext).remember(profile.copy(nickname = nickname))
                    withContext(Dispatchers.Main) { renderRows() }
                }
            }
            .show()
    }

    override fun onDestroy() {
        scanner.stopScan()
        super.onDestroy()
    }
}
