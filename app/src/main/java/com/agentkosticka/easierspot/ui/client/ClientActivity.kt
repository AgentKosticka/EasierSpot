package com.agentkosticka.easierspot.ui.client

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.client.BleScanner
import com.agentkosticka.easierspot.ble.client.BleDiscoveryRegistrar
import com.agentkosticka.easierspot.ble.client.DiscoveredServer
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.service.BleClientService
import com.agentkosticka.easierspot.service.ClientConnectionState
import com.agentkosticka.easierspot.service.titleAndText
import com.agentkosticka.easierspot.ui.permissions.PermissionsActivity
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Pairing browser. The foreground service owns connection work and survives this activity. */
class ClientActivity : AppCompatActivity() {
    private lateinit var scanner: BleScanner
    private lateinit var scanButton: Button
    private lateinit var status: TextView
    private val rows = mutableListOf<Map<String, String>>()
    private data class RowTarget(
        val trusted: TrustedServerProfile?,
        val discovered: DiscoveredServer?
    )
    private val targets = mutableListOf<RowTarget>()
    private var latestServers: List<DiscoveredServer> = emptyList()
    private var adapter: SimpleAdapter? = null
    private var renderJob: Job? = null
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
                if (target.trusted != null) {
                    BleClientService.connectTrusted(applicationContext, target.trusted.discoveryToken)
                } else {
                    target.discovered?.let { BleClientService.pair(applicationContext, it) }
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                targets.getOrNull(position)?.trusted?.let(::showPairedDeviceMenu) != null
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
            scanner.scanError.collect { error -> error?.let { Toast.makeText(this@ClientActivity, it, Toast.LENGTH_LONG).show() } }
        }
        lifecycleScope.launch {
            BleClientService.connectionState.collect { state ->
                if (state != ClientConnectionState.Idle) status.text = state.titleAndText().second
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startScan()
    }

    private fun startScan() {
        if (!hasPermissions()) {
            startActivity(Intent(this, PermissionsActivity::class.java).putExtra(PermissionsActivity.EXTRA_VIEW_ONLY, true))
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
            val byToken = discovered.associateBy { it.deviceId }
            targets.clear()
            rows.clear()
            trusted.forEach { profile ->
                val nearby = byToken[profile.discoveryToken]
                targets += RowTarget(profile, nearby)
                rows += mapOf(
                    "name" to profile.label,
                    "detail" to if (nearby != null) getString(R.string.paired_tap_connect)
                    else getString(R.string.paired_not_nearby)
                )
            }
            discovered.filter { server -> trusted.none { it.discoveryToken == server.deviceId } }
                .forEach { server ->
                    targets += RowTarget(null, server)
                    rows += mapOf(
                        "name" to (server.deviceName ?: getString(R.string.nearby_easierspot_phone)),
                        "detail" to getString(R.string.new_phone_tap_pair)
                    )
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
