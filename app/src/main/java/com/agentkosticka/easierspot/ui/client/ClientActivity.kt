package com.agentkosticka.easierspot.ui.client

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.DialogInterface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.client.BleScanner
import com.agentkosticka.easierspot.ble.client.GattClient
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClientActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ClientActivity"
        private const val REQUEST_ENABLE_BT = 1001
        private const val REQUEST_ADD_WIFI_NETWORKS = 1002
        private const val ADD_WIFI_RESULT_SUCCESS = 0
    }
    
    private lateinit var bleScanner: BleScanner
    private lateinit var gattClient: GattClient
    private var adapter: SimpleAdapter? = null
    private val deviceList = mutableListOf<Map<String, String>>()
    private lateinit var scanButton: Button
    private lateinit var statusText: android.widget.TextView
    private var hotspotNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var suggestionNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var suggestionFallbackJob: Job? = null
    private var addNetworksFallbackJob: Job? = null
    private var suggestionPostConnectReceiver: BroadcastReceiver? = null
    private var isConnecting = false
    private var pendingCredentials: HotspotCredentials? = null
    private var pendingSuggestionCredentials: HotspotCredentials? = null
    private var pendingAddNetworksCredentials: HotspotCredentials? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        Log.d(TAG, "onCreate()")

        title = "Connect to Hotspot"

        bleScanner = BleScanner(this)
        gattClient = GattClient(this)

        scanButton = findViewById(R.id.btn_scan)
        statusText = findViewById(R.id.status_text)
        val devicesListView = findViewById<ListView>(R.id.devices_list)

        adapter = SimpleAdapter(
            this,
            deviceList,
            android.R.layout.simple_list_item_2,
            arrayOf("name", "rssi"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        devicesListView.adapter = adapter

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            if (isConnecting) {
                Log.d(TAG, "Already connecting, ignoring tap")
                return@setOnItemClickListener
            }
            val device = bleScanner.discoveredServers.value[position]
            connectToServer(device)
        }

        scanButton.setOnClickListener {
            Log.d(TAG, "Scan button clicked")
            handleScanButtonClick()
        }

        // Observe discovered servers
        lifecycleScope.launch {
            bleScanner.discoveredServers.collect { servers ->
                Log.d(TAG, "Discovered servers updated: ${servers.size} devices")
                deviceList.clear()
                servers.forEach { server ->
                    deviceList.add(mapOf(
                        "name" to (server.deviceName ?: "Unknown"),
                        "rssi" to "RSSI: ${server.rssi} dBm"
                    ))
                }
                adapter?.notifyDataSetChanged()
            }
        }

        // Observe scan errors
        lifecycleScope.launch {
            bleScanner.scanError.collect { error ->
                if (error != null) {
                    Log.e(TAG, "Scan error: $error")
                    Toast.makeText(this@ClientActivity, error, Toast.LENGTH_LONG).show()
                    scanButton.text = "Start Scan"
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            gattClient.connectionState.collect { state ->
                Log.d(TAG, "Connection state: $state")
                when (state) {
                    GattClient.ConnectionState.CONNECTING -> {
                        statusText.text = "Connecting..."
                        statusText.visibility = android.view.View.VISIBLE
                    }
                    GattClient.ConnectionState.CONNECTED -> {
                        statusText.text = "Connected, waiting for approval..."
                        statusText.visibility = android.view.View.VISIBLE
                    }
                    GattClient.ConnectionState.DISCONNECTED -> {
                        if (isConnecting) {
                            statusText.text = "Disconnected"
                            isConnecting = false
                        } else {
                            statusText.visibility = android.view.View.GONE
                        }
                    }
                    GattClient.ConnectionState.ERROR -> {
                        statusText.text = "Connection error"
                        isConnecting = false
                    }
                }
            }
        }

        // Observe approval status
        lifecycleScope.launch {
            gattClient.approvalStatus.collect { status ->
                Log.d(TAG, "Approval status: $status")
                when (status) {
                    GattClient.ApprovalStatus.APPROVED -> {
                        statusText.text = "Approved! Receiving credentials..."
                    }
                    GattClient.ApprovalStatus.DENIED -> {
                        statusText.text = "Connection denied by server"
                        isConnecting = false
                    }
                    null -> { /* Pending or unknown */ }
                }
            }
        }

        // Observe received credentials
        lifecycleScope.launch {
            gattClient.receivedCredentials.collect { credentials ->
                if (credentials != null) {
                    Log.d(TAG, "Received credentials: ${credentials.ssid}")
                    statusText.text = "Got credentials! Connecting to ${credentials.ssid}..."
                    isConnecting = false
                    connectToHotspot(credentials)
                    gattClient.disconnect()
                }
            }
        }

        lifecycleScope.launch {
            gattClient.gattError.collect { error ->
                if (!error.isNullOrBlank()) {
                    Log.e(TAG, "GATT error: $error")
                    Toast.makeText(this@ClientActivity, error, Toast.LENGTH_LONG).show()
                    isConnecting = false
                }
            }
        }

        registerSuggestionPostConnectReceiver()
    }

    private fun handleScanButtonClick() {
        // Check Bluetooth availability
        if (!bleScanner.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        // Check if Bluetooth is enabled
        if (!bleScanner.isBluetoothEnabled()) {
            Log.d(TAG, "Bluetooth disabled, requesting enable")
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        // Check permissions
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
            return
        }

        // Toggle scanning
        if (!bleScanner.isScanning.value) {
            Log.d(TAG, "Starting scan...")
            scanButton.text = "Stop Scan"
            bleScanner.startScan()
        } else {
            Log.d(TAG, "Stopping scan...")
            scanButton.text = "Start Scan"
            bleScanner.stopScan()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Bluetooth enabled by user")
                Toast.makeText(this, "Bluetooth enabled! Tap scan again.", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "User declined to enable Bluetooth")
                Toast.makeText(this, "Bluetooth is required for scanning", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (requestCode == REQUEST_ADD_WIFI_NETWORKS) {
            val credentials = pendingAddNetworksCredentials
            pendingAddNetworksCredentials = null
            if (credentials == null) {
                return
            }

            if (resultCode != RESULT_OK) {
                if (pendingSuggestionCredentials == credentials) {
                    statusText.text = "Add-network cancelled. Continuing suggestion path..."
                    statusText.visibility = android.view.View.VISIBLE
                    return
                }
                statusText.text = "Wi-Fi add/connect cancelled. You can retry or use temporary mode."
                statusText.visibility = android.view.View.VISIBLE
                showTemporaryConnectionFallbackDialog(credentials)
                return
            }

            @Suppress("DEPRECATION")
            val resultList = data?.getIntArrayExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
            val allSucceeded = resultList?.all { it == ADD_WIFI_RESULT_SUCCESS } ?: true
            if (!allSucceeded) {
                if (pendingSuggestionCredentials == credentials) {
                    statusText.text = "Add-network failed. Waiting on suggestion path..."
                    statusText.visibility = android.view.View.VISIBLE
                    return
                }
                statusText.text = "System could not add/connect this network reliably."
                statusText.visibility = android.view.View.VISIBLE
                showTemporaryConnectionFallbackDialog(credentials)
                return
            }

            statusText.text = "System accepted ${credentials.ssid}. Waiting for connection..."
            statusText.visibility = android.view.View.VISIBLE
            monitorSuggestionConnection(credentials)
        }
    }

    private fun connectToServer(server: com.agentkosticka.easierspot.ble.client.DiscoveredServer) {
        Log.d(TAG, "Connecting to ${server.deviceName} (${server.deviceId})")
        isConnecting = true
        statusText.text = "Connecting to ${server.deviceName}..."
        statusText.visibility = android.view.View.VISIBLE
        gattClient.connect(server.bluetoothDevice)
        bleScanner.stopScan()
        scanButton.text = "Start Scan"
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permissions - Scan: $hasScan, Connect: $hasConnect, Location: $hasLocation")
        return hasScan && hasConnect && hasLocation
    }

    private fun connectToHotspot(credentials: HotspotCredentials) {
        if (!ensureWifiEnabled(credentials)) {
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(
                this,
                "Please connect manually in Wi-Fi settings: ${credentials.ssid}",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            return
        }

        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Missing permissions to request Wi-Fi connection", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val suggestionStarted = connectToHotspotViaSuggestion(credentials)
            if (suggestionStarted) {
                scheduleAddNetworksFallback(credentials)
                statusText.text = "Trying system suggestion first for ${credentials.ssid}..."
                statusText.visibility = android.view.View.VISIBLE
                return
            }

            val addNetworksStarted = connectToHotspotViaAddNetworks(credentials)
            if (addNetworksStarted) {
                statusText.text = "Suggestion failed, trying add-network path for ${credentials.ssid}..."
                statusText.visibility = android.view.View.VISIBLE
                return
            }
            showTemporaryConnectionFallbackDialog(credentials)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val started = connectToHotspotViaSuggestion(credentials)
            if (started) {
                return
            }
            showTemporaryConnectionFallbackDialog(credentials)
            return
        }

        connectToHotspotViaSpecifier(credentials)
    }

    private fun connectToHotspotViaSuggestion(credentials: HotspotCredentials): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val suggestion = buildNetworkSuggestion(credentials)

        val firstStatus = wifiManager.addNetworkSuggestions(listOf(suggestion))
        val finalStatus = if (firstStatus == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
            wifiManager.addNetworkSuggestions(listOf(suggestion))
        } else {
            firstStatus
        }

        if (finalStatus != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.w(TAG, "WifiNetworkSuggestion failed with status=$finalStatus")
            statusText.text = "System Wi-Fi suggestion failed (status=$finalStatus)"
            statusText.visibility = android.view.View.VISIBLE
            return false
        }

        pendingSuggestionCredentials = credentials
        statusText.text = "Waiting for Android to connect to ${credentials.ssid}..."
        statusText.visibility = android.view.View.VISIBLE

        monitorSuggestionConnection(credentials)

        suggestionFallbackJob?.cancel()
        suggestionFallbackJob = lifecycleScope.launch {
            delay(25000)
            if (pendingSuggestionCredentials == credentials) {
                Log.w(TAG, "Suggestion connection timed out; waiting for user fallback choice")
                statusText.text = "Still waiting for system connection to ${credentials.ssid}"
                runOnUiThread {
                    showTemporaryConnectionFallbackDialog(credentials)
                }
            }
        }

        return true
    }

    private fun scheduleAddNetworksFallback(credentials: HotspotCredentials) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        addNetworksFallbackJob?.cancel()
        addNetworksFallbackJob = lifecycleScope.launch {
            delay(6000)
            if (pendingSuggestionCredentials != credentials || pendingAddNetworksCredentials != null) {
                return@launch
            }
            runOnUiThread {
                val started = connectToHotspotViaAddNetworks(credentials)
                if (started) {
                    statusText.text = "Suggestion still pending. Trying add-network path..."
                    statusText.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun connectToHotspotViaAddNetworks(credentials: HotspotCredentials): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val suggestion = buildNetworkSuggestion(credentials)
        val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(suggestion)
            )
        }

        pendingAddNetworksCredentials = credentials
        statusText.text = "Confirm connection to ${credentials.ssid} in system dialog..."
        statusText.visibility = android.view.View.VISIBLE
        startActivityForResult(intent, REQUEST_ADD_WIFI_NETWORKS)
        return true
    }

    private fun buildNetworkSuggestion(credentials: HotspotCredentials): WifiNetworkSuggestion {
        return WifiNetworkSuggestion.Builder()
            .setSsid(credentials.ssid)
            .apply {
                if (credentials.password.isNotEmpty()) {
                    setWpa2Passphrase(credentials.password)
                }
            }
            .build()
    }

    private fun monitorSuggestionConnection(credentials: HotspotCredentials) {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        suggestionNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val currentSsid = normalizeSsid(wifiManager.connectionInfo?.ssid)
                if (currentSsid != credentials.ssid) {
                    return
                }

                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

                pendingSuggestionCredentials = null
                suggestionFallbackJob?.cancel()
                addNetworksFallbackJob?.cancel()

                runOnUiThread {
                    if (validated) {
                        statusText.text = "Connected to ${credentials.ssid} (internet validated)"
                    } else if (hasInternet) {
                        statusText.text = "Connected to ${credentials.ssid} (internet pending validation)"
                    } else {
                        statusText.text = "Connected to ${credentials.ssid}, but no upstream internet"
                    }
                    Toast.makeText(this@ClientActivity, statusText.text, Toast.LENGTH_LONG).show()
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    if (pendingAddNetworksCredentials == credentials) {
                        statusText.text = "Suggestion unavailable. Waiting for add-network flow..."
                        statusText.visibility = android.view.View.VISIBLE
                        return@runOnUiThread
                    }
                    statusText.text = "System connection unavailable for ${credentials.ssid}"
                    showTemporaryConnectionFallbackDialog(credentials)
                }
            }
        }
        suggestionNetworkCallback = callback
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(req, callback)
    }

    private fun connectToHotspotViaSpecifier(credentials: HotspotCredentials) {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(credentials.ssid)

        if (credentials.password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(credentials.password)
        }

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        hotspotNetworkCallback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                Log.d(TAG, "Connected to hotspot network: ${credentials.ssid}")
                runOnUiThread {
                    Toast.makeText(
                        this@ClientActivity,
                        "Connected via app to ${credentials.ssid}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                connectivityManager.bindProcessToNetwork(null)
                Log.w(TAG, "Hotspot network unavailable: ${credentials.ssid}")
                runOnUiThread {
                    Toast.makeText(
                        this@ClientActivity,
                        "Could not connect to ${credentials.ssid}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        hotspotNetworkCallback = callback
        connectivityManager.requestNetwork(networkRequest, callback)
    }

    private fun normalizeSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.removePrefix("\"").removeSuffix("\"")
    }

    private fun showTemporaryConnectionFallbackDialog(credentials: HotspotCredentials) {
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Use temporary app connection?")
            .setMessage(
                "System Wi-Fi connection did not complete yet. " +
                    "You can try a temporary app-scoped connection now (may not provide full-device internet)."
            )
            .setPositiveButton("Try Temporary") { _: DialogInterface, _: Int ->
                statusText.text = "Trying temporary app connection..."
                connectToHotspotViaSpecifier(credentials)
            }
            .setNegativeButton("Keep Waiting", null)
            .show()
    }

    private fun ensureWifiEnabled(credentials: HotspotCredentials): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            return true
        }

        pendingCredentials = credentials
        statusText.text = "Please enable Wi-Fi to continue"
        statusText.visibility = android.view.View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Wi-Fi is off. Please enable it and return.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } else {
            @Suppress("DEPRECATION")
            val toggled = runCatching {
                wifiManager.isWifiEnabled = true
                wifiManager.isWifiEnabled
            }.getOrDefault(false)
            if (!toggled) {
                Toast.makeText(this, "Could not enable Wi-Fi automatically. Please enable it.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        suggestionFallbackJob?.cancel()
        addNetworksFallbackJob?.cancel()
        suggestionPostConnectReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        suggestionPostConnectReceiver = null
        hotspotNetworkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        suggestionNetworkCallback?.let {
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        bleScanner.stopScan()
        gattClient.disconnect()
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingCredentials ?: return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            pendingCredentials = null
            connectToHotspot(pending)
        }
    }

    private fun registerSuggestionPostConnectReceiver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (suggestionPostConnectReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action == WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION) {
                    Log.d(TAG, "Received ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION")
                }
            }
        }
        suggestionPostConnectReceiver = receiver
        registerReceiver(receiver, IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION))
    }
}
