package com.agentkosticka.easierspot.ui.client

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSuggestion
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
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
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

class ClientActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ClientActivity"
        private const val ADD_WIFI_RESULT_SUCCESS = 0
        private const val ADD_WIFI_RESULT_ALREADY_EXISTS = 2
    }
    
    private lateinit var bleScanner: BleScanner
    private lateinit var gattClient: GattClient
    private var adapter: SimpleAdapter? = null
    private val deviceList = mutableListOf<Map<String, String>>()
    private lateinit var scanButton: Button
    private var connectionStatusDialog: AlertDialog? = null
    private var connectionStatusTextView: android.widget.TextView? = null
    private var suggestionNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var suggestionFallbackJob: Job? = null
    private var addNetworkStabilityJob: Job? = null
    private var suggestionPostConnectReceiver: BroadcastReceiver? = null
    private var suggestionApprovalListener: WifiManager.SuggestionUserApprovalStatusListener? = null
    private var suggestionConnectionListener: WifiManager.SuggestionConnectionStatusListener? = null
    private var isConnecting = false
    private var pendingCredentials: HotspotCredentials? = null
    private var pendingSuggestionCredentials: HotspotCredentials? = null
    private var pendingAddNetworksCredentials: HotspotCredentials? = null
    private var awaitingAddNetworkConnectionCredentials: HotspotCredentials? = null
    private var addNetworkStabilityCredentials: HotspotCredentials? = null
    private var addNetworkFlowStartTime: Long = 0L
    private var addNetworkRetryCount = 0
    private var observedWifiSsid: String = ""
    private var usingWifiSuggestion = false
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
        registerSuggestionStatusListeners()

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

        lifecycleScope.launch {
            bleScanner.isScanning.collect { scanning ->
                scanButton.text = if (scanning) {
                    getString(R.string.client_scan_stop)
                } else {
                    getString(R.string.client_scan_start)
                }
            }
        }

        // Observe scan errors
        lifecycleScope.launch {
            bleScanner.scanError.collect { error ->
                if (error != null) {
                    Log.e(TAG, "Scan error: $error")
                    Toast.makeText(this@ClientActivity, error, Toast.LENGTH_LONG).show()
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

        lifecycleScope.launch {
            gattClient.pairingCode.collect { code ->
                if (!code.isNullOrBlank() && isConnecting) {
                    showConnectionStatus("Compare pairing code on both devices: $code")
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

    private fun getMaxAddNetworkRetries(): Int {
        return if (AppPreferences.isAutoRetryEnabled(this)) 1 else 0
    }

    private fun applyKeepScreenOnPreference() {
        if (AppPreferences.isKeepScreenOnEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        val allSucceeded = resultList?.isNotEmpty() == true &&
            resultList.all {
                it == ADD_WIFI_RESULT_SUCCESS || it == ADD_WIFI_RESULT_ALREADY_EXISTS
            }
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
        if (isConnectedToSsid(credentials.ssid)) {
            showConnected(credentials, isTargetWifiValidated(credentials.ssid))
        } else {
            showConnectionStatus("System accepted ${credentials.ssid}. Waiting for device-wide connection...")
        }
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
        val hasWifi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Permissions - Scan: $hasScan, Connect: $hasConnect, Wi-Fi nearby: $hasWifi")
        return hasScan && hasConnect && hasWifi
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

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "connectToHotspot: Missing required permissions")
            Toast.makeText(this, "Missing permissions to request Wi-Fi connection", Toast.LENGTH_LONG).show()
            return
        }

        if (isConnectedToSsid(credentials.ssid)) {
            Log.d(TAG, "Already connected to ${credentials.ssid}")
            pendingSuggestionCredentials = null
            showConnected(credentials, internetValidated = isTargetWifiValidated(credentials.ssid))
            return
        }

        // Shizuku's shell UID can ask WifiService to add a saved network and switch the whole
        // device. This avoids WifiNetworkSpecifier's app-only routing and the suggestion API's
        // OEM-dependent delay before Android's network selector chooses the hotspot.
        usingWifiSuggestion = false
        pendingSuggestionCredentials = credentials
        showConnectionStatus("Switching this device to ${credentials.ssid}...")
        monitorSuggestionConnection(credentials)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                HotspotManager(applicationContext).connectDeviceToWifi(credentials)
            }
            if (pendingSuggestionCredentials != credentials || isConnectedToSsid(credentials.ssid)) {
                if (isConnectedToSsid(credentials.ssid)) {
                    showConnected(credentials, isTargetWifiValidated(credentials.ssid))
                }
                return@launch
            }
            if (result.accepted) {
                Log.d(TAG, "Privileged whole-device Wi-Fi switch accepted")
                showConnectionStatus("Android is switching to ${credentials.ssid}...")
                scheduleSavedNetworkFallback(credentials, 18_000L)
            } else {
                Log.w(TAG, "Privileged Wi-Fi switch unavailable: ${result.detail}")
                showConnectionStatus("Confirm the saved Wi-Fi connection in Android...")
                if (!connectToHotspotViaAddNetworks(credentials)) {
                    val suggestionStarted = connectToHotspotViaSuggestion(credentials)
                    if (!suggestionStarted) showSavedNetworkFallbackDialog(credentials)
                }
            }
        }
    }

    private fun connectToHotspotViaSuggestion(credentials: HotspotCredentials): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val suggestion = buildNetworkSuggestion(credentials)

        val firstStatus = wifiManager.addNetworkSuggestions(listOf(suggestion))
        val finalStatus = firstStatus

        if (finalStatus != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
            finalStatus != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        ) {
            Log.w(TAG, "WifiNetworkSuggestion failed with status=$finalStatus")
            showConnectionStatus("System Wi-Fi suggestion failed (status=$finalStatus)")
            return false
        }

        pendingSuggestionCredentials = credentials
        usingWifiSuggestion = true
        showConnectionStatus("Waiting for Android to connect to ${credentials.ssid}...")

        monitorSuggestionConnection(credentials)

        scheduleSavedNetworkFallback(credentials, 25_000L)

        return true
    }

    private fun scheduleSavedNetworkFallback(credentials: HotspotCredentials, timeoutMs: Long) {
        suggestionFallbackJob?.cancel()
        suggestionFallbackJob = lifecycleScope.launch {
            delay(timeoutMs)
            if (pendingSuggestionCredentials == credentials && !isConnectedToSsid(credentials.ssid)) {
                Log.w(TAG, "Device-wide connection timed out; offering saved-network fallback")
                showConnectionStatus("Android has not switched to ${credentials.ssid}")
                showSavedNetworkFallbackDialog(credentials)
            }
        }
    }

    private fun registerSuggestionStatusListeners() {
        val hasWifiPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasWifiPermission) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        suggestionApprovalListener = WifiManager.SuggestionUserApprovalStatusListener { status ->
            if (!usingWifiSuggestion) return@SuggestionUserApprovalStatusListener
            val credentials = pendingSuggestionCredentials ?: return@SuggestionUserApprovalStatusListener
            when (status) {
                WifiManager.STATUS_SUGGESTION_APPROVAL_PENDING ->
                    showConnectionStatus("Approve EasierSpot's Wi-Fi suggestion to join ${credentials.ssid}")
                WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER -> {
                    suggestionFallbackJob?.cancel()
                    showConnectionStatus("Automatic Wi-Fi joining is not allowed for EasierSpot")
                    showSavedNetworkFallbackDialog(credentials)
                }
                WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER,
                WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_CARRIER_PRIVILEGE ->
                    showConnectionStatus("Wi-Fi suggestion approved. Waiting for ${credentials.ssid}...")
            }
        }
        suggestionConnectionListener = WifiManager.SuggestionConnectionStatusListener { suggestion, status ->
            if (!usingWifiSuggestion) return@SuggestionConnectionStatusListener
            val credentials = pendingSuggestionCredentials ?: return@SuggestionConnectionStatusListener
            if (suggestion.ssid != credentials.ssid) return@SuggestionConnectionStatusListener
            suggestionFallbackJob?.cancel()
            val reason = when (status) {
                WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION -> "authentication failed"
                WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_ASSOCIATION -> "the device could not associate"
                WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_IP_PROVISIONING -> "IP address setup failed"
                else -> "Android could not connect"
            }
            showConnectionStatus("Wi-Fi connection failed: $reason")
            showSavedNetworkFallbackDialog(credentials)
        }
        try {
            suggestionApprovalListener?.let {
                wifiManager.addSuggestionUserApprovalStatusListener(mainExecutor, it)
            }
            suggestionConnectionListener?.let {
                wifiManager.addSuggestionConnectionStatusListener(mainExecutor, it)
            }
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Cannot register Wi-Fi suggestion status listeners", securityException)
        }
    }

    private fun connectToHotspotViaAddNetworks(credentials: HotspotCredentials): Boolean {
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
        return try {
            addWifiNetworksLauncher.launch(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Saved-network system flow is unavailable", error)
            pendingAddNetworksCredentials = null
            awaitingAddNetworkConnectionCredentials = null
            addNetworkStabilityCredentials = null
            addNetworkFlowStartTime = 0L
            false
        }
    }

    private fun prepareWifiHandoffForAddNetworks(credentials: HotspotCredentials) {
        // ACTION_WIFI_ADD_NETWORKS is independent from the suggestion API. Keep the suggestion in
        // place so Android can continue auto-join evaluation while the saved-network flow runs.
        Log.d(TAG, "Prepared saved-network fallback for ${credentials.ssid}")
    }

    private fun prepareFastWifiHandoff(credentials: HotspotCredentials) {
        // Deliberately do not disconnect the user's current network or remove a working
        // suggestion. Android's network selector performs the handoff when the new suggestion is
        // approved and suitable.
        Log.d(TAG, "Prepared non-disruptive Wi-Fi handoff for ${credentials.ssid}")
    }

    private fun buildNetworkSuggestion(credentials: HotspotCredentials): WifiNetworkSuggestion {
        return WifiNetworkSuggestion.Builder()
            .setSsid(credentials.ssid)
            .setIsHiddenSsid(credentials.isHidden)
            .setIsInitialAutojoinEnabled(true)
            .apply {
                when (credentials.securityType) {
                    HotspotCredentials.SecurityType.OPEN -> Unit
                    HotspotCredentials.SecurityType.WPA2_PSK,
                    HotspotCredentials.SecurityType.WPA3_TRANSITION ->
                        setWpa2Passphrase(credentials.password)
                    HotspotCredentials.SecurityType.WPA3_SAE ->
                        setWpa3Passphrase(credentials.password)
                }
            }
            .build()
    }

    private fun monitorSuggestionConnection(credentials: HotspotCredentials) {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        suggestionNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        observedWifiSsid = ""
        var handledTargetNetwork: Network? = null

        val callback = object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        ) {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "monitorSuggestionConnection.onAvailable: Network available for ${credentials.ssid}")
                val currentSsid = ssidForNetwork(network).ifBlank { observedWifiSsid }
                if (currentSsid.isNotBlank()) observedWifiSsid = currentSsid
                Log.d(TAG, "monitorSuggestionConnection.onAvailable: currentSsid=$currentSsid, expectedSsid=${credentials.ssid}")
                if (currentSsid != credentials.ssid) {
                    Log.w(TAG, "monitorSuggestionConnection.onAvailable: SSID mismatch, early return (current=$currentSsid, expected=${credentials.ssid})")
                    return
                }
                if (handledTargetNetwork == network) return
                handledTargetNetwork = network

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
                                val stableSsid = currentConnectedWifiSsid()
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
                                    val maxAddNetworkRetries = getMaxAddNetworkRetries()
                                    if (stableSsid == "<unknown ssid>" && addNetworkRetryCount < maxAddNetworkRetries) {
                                        Log.w(TAG, "StabilityGate: First connection attempt failed with unknown SSID, retrying... (attempt ${addNetworkRetryCount + 1}/$maxAddNetworkRetries)")
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
                                        val maxAddNetworkRetries = getMaxAddNetworkRetries()
                                        if (addNetworkRetryCount >= maxAddNetworkRetries) {
                                            Log.w(TAG, "StabilityGate: Retry limit reached for ${credentials.ssid}")
                                        }
                                        runOnUiThread {
                                            showConnectionStatus("Connection to ${credentials.ssid} changed. Waiting...")
                                        }
                                    }
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
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

                showConnected(credentials, validated, hasInternet)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return
                observedWifiSsid = normalizeSsid(wifiInfo.ssid)
                if (observedWifiSsid == credentials.ssid) {
                    onAvailable(network)
                }
            }

            override fun onLost(network: Network) {
                if (handledTargetNetwork == network) handledTargetNetwork = null
                if (pendingSuggestionCredentials?.ssid == credentials.ssid) {
                    observedWifiSsid = ""
                    runOnUiThread {
                        showConnectionStatus("Connection to ${credentials.ssid} was lost")
                    }
                }
            }

            override fun onUnavailable() {
                // Get current SSID for verification
                val currentSsid = currentConnectedWifiSsid().ifBlank { observedWifiSsid }
                
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
        findTargetWifiNetwork(credentials.ssid)?.let { (network, capabilities) ->
            callback.onCapabilitiesChanged(network, capabilities)
        }
    }

    private fun normalizeSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val normalized = raw.removePrefix("\"").removeSuffix("\"")
        return if (normalized == WifiManager.UNKNOWN_SSID) "" else normalized
    }

    private fun ssidForNetwork(network: Network): String {
        val capabilities = getSystemService(ConnectivityManager::class.java)
            .getNetworkCapabilities(network)
        return normalizeSsid((capabilities?.transportInfo as? WifiInfo)?.ssid)
    }

    @Suppress("DEPRECATION")
    private fun findTargetWifiNetwork(ssid: String): Pair<Network, NetworkCapabilities>? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        return connectivityManager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@firstNotNullOfOrNull null
            }
            val connectedSsid = normalizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid)
            if (connectedSsid == ssid) network to capabilities else null
        }
    }

    @Suppress("DEPRECATION")
    private fun currentConnectedWifiSsid(): String {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val activeSsid = ssidForNetwork(activeNetwork)
            if (activeSsid.isNotBlank()) return activeSsid
        }
        connectivityManager.allNetworks.forEach { network ->
            val ssid = ssidForNetwork(network)
            if (ssid.isNotBlank()) return ssid
        }
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return runCatching { normalizeSsid(wifiManager.connectionInfo?.ssid) }.getOrDefault("")
    }

    private fun isConnectedToSsid(ssid: String): Boolean =
        findTargetWifiNetwork(ssid) != null || currentConnectedWifiSsid() == ssid

    private fun isTargetWifiValidated(ssid: String): Boolean =
        findTargetWifiNetwork(ssid)?.second
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private fun showConnected(
        credentials: HotspotCredentials,
        internetValidated: Boolean,
        hasInternetCapability: Boolean = true
    ) {
        pendingSuggestionCredentials = null
        usingWifiSuggestion = false
        suggestionFallbackJob?.cancel()
        addNetworkStabilityCredentials = null
        awaitingAddNetworkConnectionCredentials = null
        addNetworkFlowStartTime = 0L
        val callback = suggestionNetworkCallback
        suggestionNetworkCallback = null
        if (callback != null) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            }
        }
        runOnUiThread {
            val message = when {
                internetValidated -> "Connected to ${credentials.ssid}"
                hasInternetCapability -> "Connected to ${credentials.ssid}; checking internet..."
                else -> "Connected to ${credentials.ssid}, but no upstream internet"
            }
            showConnectionStatus(message)
            dismissConnectionStatus()
            Toast.makeText(this@ClientActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showTemporaryConnectionFallbackDialog(credentials: HotspotCredentials) {
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Wi-Fi connection needs confirmation")
            .setMessage(
                "Android did not complete the device-wide switch. Try the saved-network prompt " +
                    "again, or select ${credentials.ssid} in Wi-Fi settings."
            )
            .setPositiveButton("Try again") { _: DialogInterface, _: Int ->
                connectToHotspotViaAddNetworks(credentials)
            }
            .setNeutralButton("Wi-Fi settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavedNetworkFallbackDialog(credentials: HotspotCredentials) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Automatic connection needs help")
            .setMessage(
                "Android has not joined ${credentials.ssid}. Save it as a normal Wi-Fi network, " +
                    "or select it in Wi-Fi settings."
            )
            .setPositiveButton("Save network") { _, _ ->
                prepareWifiHandoffForAddNetworks(credentials)
                connectToHotspotViaAddNetworks(credentials)
            }
            .setNeutralButton("Wi-Fi settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureWifiEnabled(credentials: HotspotCredentials): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            return true
        }

        pendingCredentials = credentials
        showConnectionStatus("Please enable Wi-Fi to continue")

        Toast.makeText(this, "Wi-Fi is off. Please enable it and return.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.Panel.ACTION_WIFI))
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
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        suggestionApprovalListener?.let {
            runCatching { wifiManager.removeSuggestionUserApprovalStatusListener(it) }
        }
        suggestionConnectionListener?.let {
            runCatching { wifiManager.removeSuggestionConnectionStatusListener(it) }
        }
        suggestionApprovalListener = null
        suggestionConnectionListener = null

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
        applyKeepScreenOnPreference()
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

    override fun onStart() {
        super.onStart()
        applyKeepScreenOnPreference()
    }

    private fun registerSuggestionPostConnectReceiver() {
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
