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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.client.BleScanner
import com.agentkosticka.easierspot.ble.client.GattClient
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ClientActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ClientActivity"
        private const val ADD_WIFI_RESULT_SUCCESS = 0
    }
    
    private lateinit var bleScanner: BleScanner
    private lateinit var gattClient: GattClient
    private var adapter: SimpleAdapter? = null
    private val deviceList = mutableListOf<Map<String, String>>()
    private lateinit var scanButton: Button
    private var connectionStatusDialog: AlertDialog? = null
    private var connectionStatusTextView: android.widget.TextView? = null
    private var hotspotNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var suggestionNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var suggestionFallbackJob: Job? = null
    private var addNetworkStabilityJob: Job? = null
    private var suggestionPostConnectReceiver: BroadcastReceiver? = null
    private var isConnecting = false
    private var pendingCredentials: HotspotCredentials? = null
    private var pendingSuggestionCredentials: HotspotCredentials? = null
    private var pendingAddNetworksCredentials: HotspotCredentials? = null
    private var awaitingAddNetworkConnectionCredentials: HotspotCredentials? = null
    private var addNetworkStabilityCredentials: HotspotCredentials? = null
    private var addNetworkFlowStartTime: Long = 0L
    private var addNetworkRetryCount = 0
    private val MAX_ADD_NETWORK_RETRIES = 1
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "Bluetooth enabled by user")
                Toast.makeText(this, "Bluetooth enabled! Tap scan again.", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "User declined to enable Bluetooth")
                Toast.makeText(this, "Bluetooth is required for scanning", Toast.LENGTH_SHORT).show()
            }
        }
    private val addWifiNetworksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleAddWifiNetworksResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)
        Log.d(TAG, "onCreate()")

        title = getString(R.string.client_screen_title)

        bleScanner = BleScanner(this)
        gattClient = GattClient(this)

        scanButton = findViewById(R.id.btn_scan)
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.btn_client_settings)
        val devicesListView = findViewById<ListView>(R.id.devices_list)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
                    scanButton.text = getString(R.string.client_scan_start)
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            gattClient.connectionState.collect { state ->
                Log.d(TAG, "Connection state: $state")
                when (state) {
                    GattClient.ConnectionState.CONNECTING -> {
                        showConnectionStatus("Connecting...")
                    }
                    GattClient.ConnectionState.CONNECTED -> {
                        showConnectionStatus("Connected, waiting for approval...")
                    }
                    GattClient.ConnectionState.DISCONNECTED -> {
                        if (isConnecting) {
                            showConnectionStatus("Disconnected")
                            isConnecting = false
                        } else {
                            dismissConnectionStatus()
                        }
                    }
                    GattClient.ConnectionState.ERROR -> {
                        showConnectionStatus("Connection error")
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
                        showConnectionStatus("Approved! Receiving credentials...")
                    }
                    GattClient.ApprovalStatus.DENIED -> {
                        showConnectionStatus("Connection denied by server")
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
                    showConnectionStatus("Got credentials! Preparing fast Wi-Fi handoff...")
                    isConnecting = false
                    Log.d(TAG, "Calling gattClient.disconnect() after receiving credentials for ${credentials.ssid}")
                    gattClient.disconnect()
                    delay(300)
                    connectToHotspot(credentials)
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
            val hasConnectPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasConnectPermission) {
                Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
                return
            }
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
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
            scanButton.text = getString(R.string.client_scan_stop)
            bleScanner.startScan()
        } else {
            Log.d(TAG, "Stopping scan...")
            scanButton.text = getString(R.string.client_scan_start)
            bleScanner.stopScan()
        }
    }

    private fun handleAddWifiNetworksResult(resultCode: Int, data: Intent?) {
        Log.d(TAG, "handleAddWifiNetworksResult: resultCode=$resultCode")
        val credentials = pendingAddNetworksCredentials
        pendingAddNetworksCredentials = null
        if (credentials == null) {
            Log.w(TAG, "handleAddWifiNetworksResult: No pending credentials")
            addNetworkFlowStartTime = 0L
            return
        }
        Log.d(TAG, "handleAddWifiNetworksResult: Processing for SSID=${credentials.ssid}")

        if (resultCode != RESULT_OK) {
            Log.w(TAG, "handleAddWifiNetworksResult: Add-network cancelled or failed for ${credentials.ssid}")
            addNetworkFlowStartTime = 0L
            showConnectionStatus("Wi-Fi add/connect cancelled. You can retry or use temporary mode.")
            showTemporaryConnectionFallbackDialog(credentials)
            return
        }

        @Suppress("DEPRECATION")
        val resultList = data?.getIntArrayExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
        val allSucceeded = resultList?.all { it == ADD_WIFI_RESULT_SUCCESS } ?: true
        Log.d(TAG, "handleAddWifiNetworksResult: allSucceeded=$allSucceeded, resultList=${resultList?.toList()}")
        if (!allSucceeded) {
            Log.w(TAG, "handleAddWifiNetworksResult: System could not add/connect ${credentials.ssid} reliably")
            addNetworkFlowStartTime = 0L
            showConnectionStatus("System could not add/connect this network reliably.")
            prepareFastWifiHandoff(credentials)
            val suggestionStarted = connectToHotspotViaSuggestion(credentials)
            if (!suggestionStarted) {
                showTemporaryConnectionFallbackDialog(credentials)
            }
            return
        }

        // Flags and monitoring already set in connectToHotspotViaAddNetworks before intent launch
        // Just verify and log status
        Log.d(TAG, "handleAddWifiNetworksResult: Add-network accepted for ${credentials.ssid}; monitoring already active")
        Log.d(TAG, "handleAddWifiNetworksResult: Verified addNetworkStabilityCredentials=${addNetworkStabilityCredentials?.ssid}")
        showConnectionStatus("System accepted ${credentials.ssid}. Waiting for connection...")
    }

    private fun connectToServer(server: com.agentkosticka.easierspot.ble.client.DiscoveredServer) {
        Log.d(TAG, "Connecting to ${server.deviceName} (${server.deviceId})")
        isConnecting = true
        showConnectionStatus("Connecting to ${server.deviceName}...")
        gattClient.connect(server.bluetoothDevice)
        bleScanner.stopScan()
        scanButton.text = getString(R.string.client_scan_start)
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permissions - Scan: $hasScan, Connect: $hasConnect, Location: $hasLocation")
        return hasScan && hasConnect && hasLocation
    }

    private fun connectToHotspot(credentials: HotspotCredentials) {
        Log.d(TAG, "connectToHotspot: Starting connection to SSID=${credentials.ssid}")
        
        // Reset retry counter when starting a new connection to a different SSID
        if (addNetworkStabilityCredentials?.ssid != credentials.ssid) {
            addNetworkRetryCount = 0
            Log.d(TAG, "connectToHotspot: Reset retry counter for new SSID ${credentials.ssid}")
        }
        
        if (!ensureWifiEnabled(credentials)) {
            Log.d(TAG, "connectToHotspot: WiFi not enabled, waiting")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "connectToHotspot: SDK < Q, using legacy manual connection path")
            prepareFastWifiHandoff(credentials)
            Toast.makeText(
                this,
                "Please connect manually in Wi-Fi settings: ${credentials.ssid}",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            return
        }

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "connectToHotspot: Missing required permissions")
            Toast.makeText(this, "Missing permissions to request Wi-Fi connection", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "connectToHotspot: SDK >= R, using add-network path for ${credentials.ssid}")
            prepareWifiHandoffForAddNetworks(credentials)
            val addNetworksStarted = connectToHotspotViaAddNetworks(credentials)
            if (addNetworksStarted) {
                return
            }

            prepareFastWifiHandoff(credentials)
            val suggestionStarted = connectToHotspotViaSuggestion(credentials)
            if (suggestionStarted) {
                showConnectionStatus("Add-network unavailable. Using suggestion for ${credentials.ssid}...")
                return
            }

            showTemporaryConnectionFallbackDialog(credentials)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "connectToHotspot: SDK >= Q but < R, using suggestion path for ${credentials.ssid}")
            prepareFastWifiHandoff(credentials)
            val started = connectToHotspotViaSuggestion(credentials)
            if (started) {
                return
            }
            Log.w(TAG, "connectToHotspot: Suggestion connection failed to start for ${credentials.ssid}")
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
            showConnectionStatus("System Wi-Fi suggestion failed (status=$finalStatus)")
            return false
        }

        pendingSuggestionCredentials = credentials
        showConnectionStatus("Waiting for Android to connect to ${credentials.ssid}...")

        monitorSuggestionConnection(credentials)

        suggestionFallbackJob?.cancel()
        suggestionFallbackJob = lifecycleScope.launch {
            delay(25000)
            if (pendingSuggestionCredentials == credentials) {
                Log.w(TAG, "Suggestion connection timed out; waiting for user fallback choice")
                showConnectionStatus("Still waiting for system connection to ${credentials.ssid}")
                runOnUiThread {
                    showTemporaryConnectionFallbackDialog(credentials)
                }
            }
        }

        return true
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
        awaitingAddNetworkConnectionCredentials = credentials
        addNetworkStabilityCredentials = credentials
        addNetworkFlowStartTime = System.currentTimeMillis()
        Log.d(TAG, "connectToHotspotViaAddNetworks: Starting add-network flow at $addNetworkFlowStartTime for ${credentials.ssid}")
        Log.d(TAG, "connectToHotspotViaAddNetworks: Set addNetworkStabilityCredentials=${credentials.ssid} BEFORE launching intent")
        
        // Register network monitoring BEFORE launching the intent
        // This ensures we catch the connection event when Android connects during the dialog
        Log.d(TAG, "connectToHotspotViaAddNetworks: Registering network monitoring for ${credentials.ssid}")
        monitorSuggestionConnection(credentials)
        
        showConnectionStatus("Confirm connection to ${credentials.ssid} in system dialog...")
        addWifiNetworksLauncher.launch(intent)
        return true
    }

    private fun prepareWifiHandoffForAddNetworks(credentials: HotspotCredentials) {
        Log.d(TAG, "prepareWifiHandoffForAddNetworks: Entry for SSID=${credentials.ssid}")
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val suggestion = buildNetworkSuggestion(credentials)
        runCatching {
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
            Log.d(TAG, "prepareWifiHandoffForAddNetworks: Removed previous suggestions for ${credentials.ssid}")
        }.onFailure {
            Log.d(TAG, "prepareWifiHandoffForAddNetworks: Could not clear previous suggestion for add-network ${credentials.ssid}: ${it.message}")
        }
        Log.d(TAG, "prepareWifiHandoffForAddNetworks: Prepared add-network handoff for ${credentials.ssid} without forced disconnect")
    }

    private fun prepareFastWifiHandoff(credentials: HotspotCredentials) {
        Log.d(TAG, "prepareFastWifiHandoff: Entry for SSID=${credentials.ssid}")
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val suggestion = buildNetworkSuggestion(credentials)

        runCatching {
            wifiManager.removeNetworkSuggestions(listOf(suggestion))
            Log.d(TAG, "prepareFastWifiHandoff: Called removeNetworkSuggestions() for ${credentials.ssid}")
        }.onFailure {
            Log.d(TAG, "prepareFastWifiHandoff: Could not clear previous suggestion for ${credentials.ssid}: ${it.message}")
        }

        @Suppress("DEPRECATION")
        val disconnected = runCatching { wifiManager.disconnect() }.getOrDefault(false)
        Log.d(TAG, "prepareFastWifiHandoff: Called wifiManager.disconnect() with result=$disconnected for ${credentials.ssid}")
        if (disconnected) {
            Log.d(TAG, "prepareFastWifiHandoff: Pre-join Wi-Fi disconnect requested for fast handoff to ${credentials.ssid}")
        } else {
            Log.w(TAG, "prepareFastWifiHandoff: Wi-Fi disconnect failed or returned false for ${credentials.ssid}")
        }
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
                Log.d(TAG, "monitorSuggestionConnection.onAvailable: Network available for ${credentials.ssid}")
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val currentSsid = normalizeSsid(wifiManager.connectionInfo?.ssid)
                Log.d(TAG, "monitorSuggestionConnection.onAvailable: currentSsid=$currentSsid, expectedSsid=${credentials.ssid}")
                if (currentSsid != credentials.ssid) {
                    Log.w(TAG, "monitorSuggestionConnection.onAvailable: SSID mismatch, early return (current=$currentSsid, expected=${credentials.ssid})")
                    return
                }

                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

                pendingSuggestionCredentials = null
                suggestionFallbackJob?.cancel()

                val requiresAddNetworkStabilityGate =
                    addNetworkStabilityCredentials?.ssid == credentials.ssid
                Log.d(TAG, "monitorSuggestionConnection.onAvailable: requiresAddNetworkStabilityGate=$requiresAddNetworkStabilityGate for ${credentials.ssid}")
                if (requiresAddNetworkStabilityGate) {
                    Log.d(TAG, "monitorSuggestionConnection.onAvailable: Network available for ${credentials.ssid}; running stability gate")
                    addNetworkStabilityJob?.cancel()
                    addNetworkStabilityJob = lifecycleScope.launch {
                        try {
                            withTimeout(10_000L) {
                                Log.d(TAG, "StabilityGate: Started for ${credentials.ssid}")
                                runOnUiThread {
                                    showConnectionStatus("Connected to ${credentials.ssid}. Verifying stability...")
                                }
                                delay(1500)
                                Log.d(TAG, "StabilityGate: Checking SSID after 1.5s delay for ${credentials.ssid}")
                                val refreshedWifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                                @Suppress("DEPRECATION")
                                val stableSsid = normalizeSsid(refreshedWifiManager.connectionInfo?.ssid)
                                Log.d(TAG, "StabilityGate: stableSsid=$stableSsid, expectedSsid=${credentials.ssid}")
                                if (stableSsid == credentials.ssid) {
                                    addNetworkStabilityCredentials = null
                                    awaitingAddNetworkConnectionCredentials = null
                                    addNetworkFlowStartTime = 0L
                                    addNetworkRetryCount = 0  // Reset retry counter on success
                                    Log.d(TAG, "StabilityGate: PASSED for ${credentials.ssid}, clearing stability credentials and flow timestamp")
                                    runOnUiThread {
                                        val stableMessage = "Connected to ${credentials.ssid}"
                                        showConnectionStatus(stableMessage)
                                        dismissConnectionStatus()
                                        Toast.makeText(this@ClientActivity, stableMessage, Toast.LENGTH_LONG).show()
                                    }
                                    Log.d(TAG, "StabilityGate: Stability gate passed for ${credentials.ssid}")
                                } else {
                                    Log.w(TAG, "StabilityGate: FAILED for ${credentials.ssid}; current SSID=$stableSsid")
                                    
                                    // Auto-retry on first-time disconnect (when SSID becomes unknown)
                                    if (stableSsid == "<unknown ssid>" && addNetworkRetryCount < MAX_ADD_NETWORK_RETRIES) {
                                        Log.w(TAG, "StabilityGate: First connection attempt failed with unknown SSID, retrying... (attempt ${addNetworkRetryCount + 1}/$MAX_ADD_NETWORK_RETRIES)")
                                        addNetworkRetryCount++
                                        
                                        // Clear state for retry
                                        addNetworkStabilityCredentials = null
                                        awaitingAddNetworkConnectionCredentials = null
                                        addNetworkFlowStartTime = 0L
                                        
                                        runOnUiThread {
                                            showConnectionStatus("First connection unstable, retrying ${credentials.ssid}...")
                                            // Re-launch add-network flow
                                            connectToHotspot(credentials)
                                        }
                                    } else {
                                        // Either not unknown SSID or retry exhausted
                                        if (addNetworkRetryCount >= MAX_ADD_NETWORK_RETRIES) {
                                            Log.w(TAG, "StabilityGate: Retry limit reached for ${credentials.ssid}")
                                        }
                                        runOnUiThread {
                                            showConnectionStatus("Connection to ${credentials.ssid} changed. Waiting...")
                                        }
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.w(TAG, "StabilityGate: TIMEOUT for ${credentials.ssid} - 10 second limit exceeded")
                            addNetworkStabilityCredentials = null
                            awaitingAddNetworkConnectionCredentials = null
                            addNetworkFlowStartTime = 0L
                            runOnUiThread {
                                showConnectionStatus("Connection to ${credentials.ssid} timed out")
                                dismissConnectionStatus()
                                
                                AlertDialog.Builder(this@ClientActivity)
                                    .setTitle("Connection Timeout")
                                    .setMessage("Failed to verify connection to ${credentials.ssid}. Would you like to retry?")
                                    .setPositiveButton("Retry") { _, _ ->
                                        Log.d(TAG, "StabilityGate: User requested retry for ${credentials.ssid}")
                                        prepareWifiHandoffForAddNetworks(credentials)
                                        connectToHotspotViaAddNetworks(credentials)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                    return
                }

                runOnUiThread {
                    val message = if (validated) {
                        "Connected to ${credentials.ssid} (internet validated)"
                    } else if (hasInternet) {
                        "Connected to ${credentials.ssid} (internet pending validation)"
                    } else {
                        "Connected to ${credentials.ssid}, but no upstream internet"
                    }
                    showConnectionStatus(message)
                    dismissConnectionStatus()
                    Toast.makeText(this@ClientActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onUnavailable() {
                // Get current SSID for verification
                val currentWifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val currentSsid = normalizeSsid(currentWifiManager.connectionInfo?.ssid)
                
                val timeSinceFlowStart = if (addNetworkFlowStartTime > 0) {
                    System.currentTimeMillis() - addNetworkFlowStartTime
                } else {
                    Long.MAX_VALUE
                }
                
                Log.w(TAG, "monitorSuggestionConnection.onUnavailable fired: " +
                    "currentSSID=$currentSsid, " +
                    "expectedSSID=${credentials.ssid}, " +
                    "inAddNetworkFlow=${pendingAddNetworksCredentials != null || awaitingAddNetworkConnectionCredentials != null}, " +
                    "stabilityGateRunning=${addNetworkStabilityCredentials != null}, " +
                    "timeSinceFlowStart=${timeSinceFlowStart}ms")
                
                runOnUiThread {
                    // Guard 1: Still in add-network flow (pending or awaiting connection)
                    if (pendingAddNetworksCredentials == credentials ||
                        awaitingAddNetworkConnectionCredentials?.ssid == credentials.ssid
                    ) {
                        Log.d(TAG, "monitorSuggestionConnection.onUnavailable: Still waiting for add-network flow for ${credentials.ssid}")
                        showConnectionStatus("Suggestion unavailable. Waiting for add-network flow...")
                        return@runOnUiThread
                    }
                    
                    // Guard 2: Stability gate is still running - don't interfere
                    if (addNetworkStabilityCredentials?.ssid == credentials.ssid) {
                        Log.d(TAG, "monitorSuggestionConnection.onUnavailable: Stability gate still running for ${credentials.ssid}, ignoring premature callback")
                        showConnectionStatus("Network initializing for ${credentials.ssid}...")
                        return@runOnUiThread
                    }
                    
                    // Guard 3: Too soon after add-network flow started (within 5 seconds)
                    if (timeSinceFlowStart < 5000) {
                        Log.d(TAG, "monitorSuggestionConnection.onUnavailable: Too soon after flow start (${timeSinceFlowStart}ms), ignoring premature callback")
                        showConnectionStatus("Establishing connection to ${credentials.ssid}...")
                        return@runOnUiThread
                    }
                    
                    // Guard 4: Verify we're not already connected to the target SSID
                    if (currentSsid == credentials.ssid) {
                        Log.w(TAG, "monitorSuggestionConnection.onUnavailable: Already connected to ${credentials.ssid}, ignoring stale callback")
                        showConnectionStatus("Already connected to ${credentials.ssid}")
                        return@runOnUiThread
                    }
                    
                    // All guards passed - show fallback dialog
                    Log.w(TAG, "monitorSuggestionConnection.onUnavailable: All guards passed, showing fallback dialog for ${credentials.ssid}")
                    showConnectionStatus("System connection unavailable for ${credentials.ssid}")
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
                Log.w(TAG, "connectToHotspotViaSpecifier.onUnavailable: Network unavailable for ${credentials.ssid}, calling bindProcessToNetwork(null)")
                connectivityManager.bindProcessToNetwork(null)
                Log.w(TAG, "connectToHotspotViaSpecifier.onUnavailable: Hotspot network unavailable: ${credentials.ssid}")
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
                showConnectionStatus("Trying temporary app connection...")
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
        showConnectionStatus("Please enable Wi-Fi to continue")

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
        Log.d(TAG, "onDestroy: Entry - isConnecting=$isConnecting, pendingCredentials=$pendingCredentials")
        Log.d(TAG, "onDestroy: pendingSuggestionCredentials=${pendingSuggestionCredentials?.ssid}, addNetworkStabilityCredentials=${addNetworkStabilityCredentials?.ssid}")
        Log.d(TAG, "onDestroy: pendingAddNetworksCredentials=${pendingAddNetworksCredentials?.ssid}, awaitingAddNetworkConnectionCredentials=${awaitingAddNetworkConnectionCredentials?.ssid}")
        dismissConnectionStatus()
        super.onDestroy()
        Log.d(TAG, "onDestroy: Cancelling jobs and cleaning up callbacks")
        suggestionFallbackJob?.cancel()
        addNetworkStabilityJob?.cancel()
        suggestionPostConnectReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        suggestionPostConnectReceiver = null
        
        // CRITICAL FIX: Check if add-network connection is pending before unbinding
        // Root cause of first-time disconnect: bindProcessToNetwork(null) was being called
        // while the system dialog was open, disconnecting the network we just requested.
        val isAddNetworkPending = pendingAddNetworksCredentials != null ||
                                   awaitingAddNetworkConnectionCredentials != null ||
                                   addNetworkStabilityCredentials != null
        
        hotspotNetworkCallback?.let {
            runCatching {
                if (!isAddNetworkPending) {
                    Log.d(TAG, "onDestroy: No pending add-network connection - safe to unbind")
                    getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
                } else {
                    Log.w(TAG, "onDestroy: *** SKIPPING bindProcessToNetwork(null) - add-network connection is pending! ***")
                    Log.w(TAG, "onDestroy: This preserves the network binding during system dialog and stability gate.")
                    // NOTE: The network binding will persist across activity recreation (e.g., rotation).
                    // This is intentional to keep the Wi-Fi connection alive. Consider adding
                    // android:configChanges="orientation|screenSize" to AndroidManifest.xml
                    // to avoid activity recreation entirely during configuration changes.
                }
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        suggestionNetworkCallback?.let {
            Log.d(TAG, "onDestroy: Unregistering suggestionNetworkCallback")
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            }
        }
        Log.d(TAG, "onDestroy: Stopping BLE scan and disconnecting GATT")
        bleScanner.stopScan()
        gattClient.disconnect()
        Log.d(TAG, "onDestroy: Complete")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: pendingCredentials=${pendingCredentials?.ssid}")
        val pending = pendingCredentials ?: return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            Log.d(TAG, "onResume: WiFi enabled, connecting to pending credentials: ${pending.ssid}")
            pendingCredentials = null
            connectToHotspot(pending)
        } else {
            Log.d(TAG, "onResume: WiFi still disabled, keeping pending credentials")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: isConnecting=$isConnecting, pendingCredentials=${pendingCredentials?.ssid}")
        Log.d(TAG, "onPause: addNetworkStabilityCredentials=${addNetworkStabilityCredentials?.ssid}, awaitingAddNetworkConnectionCredentials=${awaitingAddNetworkConnectionCredentials?.ssid}")
        Log.d(TAG, "onPause: pendingAddNetworksCredentials=${pendingAddNetworksCredentials?.ssid}")
        // NOTE: We don't tear down network state here. The connection process must survive
        // activity pause events (e.g., when system dialog appears or user switches apps).
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: isConnecting=$isConnecting, pendingCredentials=${pendingCredentials?.ssid}")
        Log.d(TAG, "onStop: addNetworkStabilityCredentials=${addNetworkStabilityCredentials?.ssid}, awaitingAddNetworkConnectionCredentials=${awaitingAddNetworkConnectionCredentials?.ssid}")
        Log.d(TAG, "onStop: pendingAddNetworksCredentials=${pendingAddNetworksCredentials?.ssid}")
        // NOTE: We don't tear down network state here. The connection process must survive
        // activity stop events (e.g., when system dialog appears or user switches to another app).
        Log.d(TAG, "onStop: addNetworkStabilityCredentials=${addNetworkStabilityCredentials?.ssid}, awaitingAddNetworkConnectionCredentials=${awaitingAddNetworkConnectionCredentials?.ssid}")
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

    private fun showConnectionStatus(message: String) {
        val dialog = connectionStatusDialog
        if (dialog == null || !dialog.isShowing) {
            val textView = android.widget.TextView(this).apply {
                text = message
                textSize = 16f
                setPadding(48, 32, 48, 16)
            }
            connectionStatusTextView = textView
            connectionStatusDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.connection_status_title))
                .setView(textView)
                .setNegativeButton(getString(R.string.connection_cancel)) { _, _ ->
                    gattClient.disconnect()
                    isConnecting = false
                }
                .setCancelable(true)
                .show()
        } else {
            connectionStatusTextView?.text = message
        }
    }

    private fun dismissConnectionStatus() {
        connectionStatusDialog?.dismiss()
        connectionStatusDialog = null
        connectionStatusTextView = null
    }
}
