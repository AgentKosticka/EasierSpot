package com.agentkosticka.easierspot.ui.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.ui.dialogs.HotspotTestDialog
import com.agentkosticka.easierspot.ui.server.ShizukuHelper
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.update.UpdateCheckPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {
    private val hotspotManager by lazy { HotspotManager(this) }
    private val database by lazy { AppDatabase.getDatabase(this) }
    private lateinit var cardsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        title = getString(R.string.title_diagnostics)

        cardsContainer = findViewById(R.id.container_diagnostic_cards)

        findViewById<Button>(R.id.btn_run_hotspot_diagnostics).setOnClickListener {
            runHotspotTest()
        }

        findViewById<Button>(R.id.btn_run_ble_test).setOnClickListener {
            runBleTest()
        }

        findViewById<Button>(R.id.btn_test_connectivity).setOnClickListener {
            testConnectivity()
        }

        findViewById<Button>(R.id.btn_view_database).setOnClickListener {
            viewDatabase()
        }

        findViewById<Button>(R.id.btn_clear_cache).setOnClickListener {
            showClearCacheConfirmation()
        }

        findViewById<Button>(R.id.btn_reset_settings).setOnClickListener {
            showResetSettingsConfirmation()
        }

        findViewById<Button>(R.id.btn_export_logs).setOnClickListener {
            exportLogs()
        }

        // Load diagnostic information
        loadDiagnosticInfo()
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

    private fun runBleTest() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        val results = buildString {
            appendLine("═══ BLE Capability Test ═══")
            appendLine()

            val bleSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            appendLine("${statusIcon(bleSupported)} BLE Hardware Support")

            val bluetoothAvailable = bluetoothAdapter != null
            appendLine("${statusIcon(bluetoothAvailable)} Bluetooth Adapter Available")

            val bluetoothEnabled = bluetoothAdapter?.isEnabled == true
            appendLine("${statusIcon(bluetoothEnabled)} Bluetooth Enabled")

            val scanPermission = ActivityCompat.checkSelfPermission(
                this@DiagnosticsActivity,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            appendLine("${statusIcon(scanPermission)} BLUETOOTH_SCAN Permission")

            val connectPermission = ActivityCompat.checkSelfPermission(
                this@DiagnosticsActivity,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            appendLine("${statusIcon(connectPermission)} BLUETOOTH_CONNECT Permission")

            val advertisePermission = ActivityCompat.checkSelfPermission(
                this@DiagnosticsActivity,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            appendLine("${statusIcon(advertisePermission)} BLUETOOTH_ADVERTISE Permission")

            val locationPermission = ActivityCompat.checkSelfPermission(
                this@DiagnosticsActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            appendLine("${statusIcon(locationPermission)} FINE_LOCATION Permission")

            appendLine()
            appendLine("═══ Advertising Support ═══")
            appendLine()

            val multiAdvertiseSupported = bluetoothAdapter?.isMultipleAdvertisementSupported == true
            appendLine("${statusIcon(multiAdvertiseSupported)} Multiple Advertisement Support")

            val offloadedFilteringSupported = bluetoothAdapter?.isOffloadedFilteringSupported == true
            appendLine("${statusIcon(offloadedFilteringSupported)} Offloaded Filtering")

            val offloadedScanBatchingSupported = bluetoothAdapter?.isOffloadedScanBatchingSupported == true
            appendLine("${statusIcon(offloadedScanBatchingSupported)} Offloaded Scan Batching")

            appendLine()
            appendLine("═══ Summary ═══")
            appendLine()

            val allCriticalPassed = bleSupported && bluetoothAvailable && bluetoothEnabled && locationPermission
            if (allCriticalPassed) {
                appendLine("✅ BLE is ready for use")
            } else {
                appendLine("❌ Some critical checks failed")
                if (!bluetoothEnabled) {
                    appendLine("   → Enable Bluetooth to continue")
                }
                if (!locationPermission) {
                    appendLine("   → Location permission required for BLE scanning")
                }
            }
        }

        showResultDialog(getString(R.string.ble_test_title), results)
    }

    private fun testConnectivity() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.connectivity_test_title))
            .setMessage(getString(R.string.connectivity_test_running))
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                buildString {
                    appendLine("═══ Network Connectivity Test ═══")
                    appendLine()

                    val githubUrl = "https://api.github.com/repos/AgentKosticka/EasierSpot/releases/latest"
                    
                    try {
                        val connection = URL(githubUrl).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.setRequestProperty("User-Agent", "EasierSpot")

                        val responseCode = connection.responseCode
                        val responseMessage = connection.responseMessage

                        val success = responseCode == 200
                        appendLine("${statusIcon(success)} GitHub API Connection")
                        appendLine("   URL: $githubUrl")
                        appendLine("   Response: $responseCode $responseMessage")

                        if (success) {
                            appendLine()
                            appendLine("✅ Network connectivity is working")
                            appendLine("✅ Update checks should function properly")
                        } else {
                            appendLine()
                            appendLine("⚠️ Received non-200 response")
                            appendLine("   Update checks may not work properly")
                        }

                        connection.disconnect()
                    } catch (e: Exception) {
                        appendLine("❌ GitHub API Connection")
                        appendLine("   Error: ${e.message}")
                        appendLine()
                        appendLine("⚠️ Connectivity test failed")
                        appendLine("   Check your internet connection")
                    }
                }
            }

            progressDialog.dismiss()
            showResultDialog(getString(R.string.connectivity_test_title), results)
        }
    }

    private fun viewDatabase() {
        lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) {
                val db: SupportSQLiteDatabase = database.openHelper.readableDatabase
                val cursor: Cursor = db.query(
                    "SELECT * FROM remembered_servers ORDER BY lastSeen DESC"
                )
                cursor.use {
                    val servers = mutableListOf<RememberedServer>()
                    while (cursor.moveToNext()) {
                        val deviceId = cursor.getString(cursor.getColumnIndexOrThrow("deviceId"))
                        val deviceName = cursor.getString(cursor.getColumnIndexOrThrow("deviceName"))
                        val deviceAddress = cursor.getString(cursor.getColumnIndexOrThrow("deviceAddress"))
                        val lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("lastSeen"))
                        val isApproved = cursor.getInt(cursor.getColumnIndexOrThrow("isApproved")) == 1
                        val nickname = cursor.getString(cursor.getColumnIndexOrThrow("nickname"))
                        val approvalPolicy = cursor.getString(cursor.getColumnIndexOrThrow("approvalPolicy"))
                        val lastApprovedAt = cursor.getLong(cursor.getColumnIndexOrThrow("lastApprovedAt"))

                        servers.add(
                            RememberedServer(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                deviceAddress = deviceAddress,
                                lastSeen = lastSeen,
                                isApproved = isApproved,
                                nickname = nickname,
                                approvalPolicy = approvalPolicy,
                                lastApprovedAt = lastApprovedAt
                            )
                        )
                    }
                    servers
                }
            }

            if (servers.isEmpty()) {
                AlertDialog.Builder(this@DiagnosticsActivity)
                    .setTitle(getString(R.string.database_viewer_title))
                    .setMessage(getString(R.string.database_viewer_empty))
                    .setPositiveButton(getString(R.string.action_close)) { dialog, _ -> dialog.dismiss() }
                    .show()
            } else {
                showDatabaseViewer(servers)
            }
        }
    }

    private fun showDatabaseViewer(servers: List<RememberedServer>) {
        val content = buildString {
            appendLine("${servers.size} device(s) remembered")
            appendLine()
            servers.forEachIndexed { index, server ->
                appendLine("═══ Device ${index + 1} ═══")
                appendLine("Name: ${server.deviceName}")
                if (!server.nickname.isNullOrEmpty()) {
                    appendLine("Nickname: ${server.nickname}")
                }
                appendLine("ID: ${server.deviceId}")
                appendLine("Address: ${server.deviceAddress}")
                appendLine("Policy: ${server.approvalPolicy}")
                appendLine("Last Seen: ${formatTimestamp(server.lastSeen)}")
                if (server.lastApprovedAt > 0) {
                    appendLine("Last Approved: ${formatTimestamp(server.lastApprovedAt)}")
                }
                appendLine()
            }
        }

        val textView = TextView(this).apply {
            text = content
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scrollView = ScrollView(this).apply {
            addView(
                textView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.database_viewer_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.action_close)) { dialog, _ -> dialog.dismiss() }
            .setNegativeButton(getString(R.string.database_viewer_clear_all)) { _, _ ->
                showClearDatabaseConfirmation()
            }
            .show()
    }

    private fun showClearDatabaseConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.database_viewer_clear_confirm_title))
            .setMessage(getString(R.string.database_viewer_clear_confirm_message))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                clearDatabase()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun clearDatabase() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM remembered_servers")
            }
            Toast.makeText(
                this@DiagnosticsActivity,
                getString(R.string.database_viewer_cleared),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showClearCacheConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cache_clear_confirm_title))
            .setMessage(getString(R.string.cache_clear_confirm_message))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                clearCache()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun clearCache() {
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val results = mutableListOf<String>()

                try {
                    val cacheDir = cacheDir
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.deleteRecursively()) {
                            results.add("✅ ${file.name}")
                        } else {
                            results.add("❌ ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    results.add("❌ Error: ${e.message}")
                }

                results
            }

            if (results.isEmpty()) {
                Toast.makeText(
                    this@DiagnosticsActivity,
                    getString(R.string.cache_cleared_success),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@DiagnosticsActivity,
                    getString(R.string.cache_cleared_partial, results.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showResetSettingsConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_reset_confirm_title))
            .setMessage(getString(R.string.settings_reset_confirm_message))
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                resetSettings()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun resetSettings() {
        AppPreferences.resetToDefaults(this)
        Toast.makeText(
            this,
            getString(R.string.settings_reset_success),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exportLogs() {
        lifecycleScope.launch {
            val logContent = withContext(Dispatchers.IO) {
                buildDiagnosticReport()
            }

            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_logs_title))
                    putExtra(Intent.EXTRA_TEXT, logContent)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.export_logs_share_title)))
            } catch (_: Exception) {
                Toast.makeText(
                    this@DiagnosticsActivity,
                    getString(R.string.export_logs_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildDiagnosticReport(): String = buildString {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        appendLine("═══════════════════════════════════════")
        appendLine("    EasierSpot Diagnostic Report")
        appendLine("═══════════════════════════════════════")
        appendLine()
        appendLine("Generated: ${dateFormat.format(Date())}")
        appendLine()

        appendLine("═══ Device Information ═══")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build ID: ${Build.ID}")
        appendLine()

        appendLine("═══ App Information ═══")
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            appendLine("Package: $packageName")
            appendLine("Version: ${packageInfo.versionName}")
            appendLine("Version Code: ${packageInfo.longVersionCode}")
        } catch (e: Exception) {
            appendLine("Error retrieving app info: ${e.message}")
        }
        appendLine()

        appendLine("═══ BLE Status ═══")
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        appendLine("BLE Supported: ${packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        appendLine("Bluetooth Available: ${bluetoothAdapter != null}")
        appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
        appendLine("Multi-Advertise: ${bluetoothAdapter?.isMultipleAdvertisementSupported}")
        appendLine()

        appendLine("═══ Permissions ═══")
        appendLine("BLUETOOTH_SCAN: ${checkPermission(Manifest.permission.BLUETOOTH_SCAN)}")
        appendLine("BLUETOOTH_CONNECT: ${checkPermission(Manifest.permission.BLUETOOTH_CONNECT)}")
        appendLine("BLUETOOTH_ADVERTISE: ${checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE)}")
        appendLine("FINE_LOCATION: ${checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appendLine("POST_NOTIFICATIONS: ${checkPermission(Manifest.permission.POST_NOTIFICATIONS)}")
        }
        appendLine()

        appendLine("═══ App Preferences ═══")
        appendLine("Update Check Enabled: ${AppPreferences.isUpdateCheckEnabled(this@DiagnosticsActivity)}")
        appendLine("Debug Logging: ${AppPreferences.isDebugLoggingEnabled(this@DiagnosticsActivity)}")
        appendLine("Default Approval Policy: ${AppPreferences.getDefaultApprovalPolicy(this@DiagnosticsActivity).value}")
        appendLine("BLE Advertising Interval: ${AppPreferences.getBleAdvertisingInterval(this@DiagnosticsActivity).value}")
        appendLine("Scan Timeout: ${AppPreferences.getScanTimeoutMs(this@DiagnosticsActivity)}ms")
        appendLine()

        appendLine("═══ Update Check Cache ═══")
        appendLine("Last Check: ${formatTimestamp(UpdateCheckPreferences.getLastCheckEpochMs(this@DiagnosticsActivity))}")
        appendLine("Latest Version: ${UpdateCheckPreferences.getLatestVersionName(this@DiagnosticsActivity) ?: "N/A"}")
        appendLine("Update Available: ${UpdateCheckPreferences.isUpdateAvailable(this@DiagnosticsActivity)}")
        appendLine()

        appendLine("═══ Database Info ═══")
        try {
            val db: SupportSQLiteDatabase = database.openHelper.readableDatabase
            val cursor: Cursor = db.query("SELECT COUNT(*) FROM remembered_servers")
            cursor.use {
                if (it.moveToFirst()) {
                    appendLine("Remembered Devices: ${it.getInt(0)}")
                }
            }
        } catch (e: Exception) {
            appendLine("Error querying database: ${e.message}")
        }
        appendLine()

        appendLine("═══ Cache Info ═══")
        val cacheSize = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        appendLine("Cache Directory: ${cacheDir.absolutePath}")
        appendLine("Cache Size: ${cacheSize / 1024} KB")
        appendLine("Cache Files: ${cacheDir.listFiles()?.size ?: 0}")
        appendLine()

        appendLine("═══════════════════════════════════════")
        appendLine("           End of Report")
        appendLine("═══════════════════════════════════════")
    }

    private fun checkPermission(permission: String): String {
        return if (ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            "✅ Granted"
        } else {
            "❌ Not Granted"
        }
    }

    private fun loadDiagnosticInfo() {
        lifecycleScope.launch {
            showLoadingCard()

            val bleStatus = withContext(Dispatchers.IO) {
                DiagnosticsInfoCollector.collectBleStatus(this@DiagnosticsActivity)
            }

            val shizukuStatus = withContext(Dispatchers.IO) {
                DiagnosticsInfoCollector.collectShizukuStatus(this@DiagnosticsActivity)
            }

            val hotspotStatus = DiagnosticsInfoCollector.collectHotspotStatus(this@DiagnosticsActivity)

            val databaseInfo = DiagnosticsInfoCollector.collectDatabaseInfo(this@DiagnosticsActivity)

            val appInfo = withContext(Dispatchers.IO) {
                DiagnosticsInfoCollector.collectAppInfo(this@DiagnosticsActivity)
            }

            val systemInfo = withContext(Dispatchers.IO) {
                DiagnosticsInfoCollector.collectSystemInfo(this@DiagnosticsActivity)
            }

            cardsContainer.removeAllViews()
            addBleStatusCard(bleStatus)
            addShizukuStatusCard(shizukuStatus)
            addHotspotStatusCard(hotspotStatus)
            addDatabaseInfoCard(databaseInfo)
            addAppInfoCard(appInfo)
            addSystemInfoCard(systemInfo)
        }
    }

    private fun showLoadingCard() {
        cardsContainer.removeAllViews()
        val loadingView = createCardView(
            getString(R.string.diagnostics_status_loading),
            "Please wait..."
        )
        cardsContainer.addView(loadingView)
    }

    private fun addBleStatusCard(info: DiagnosticsInfoCollector.BleStatusInfo) {
        val content = buildString {
            appendLine("Adapter: ${if (info.adapterEnabled) "Enabled" else "Disabled"} (${info.adapterState})")
            appendLine("BLUETOOTH_SCAN: ${if (info.scanPermissionGranted) "✓ Granted" else "✗ Denied"}")
            appendLine("BLUETOOTH_CONNECT: ${if (info.connectPermissionGranted) "✓ Granted" else "✗ Denied"}")
            appendLine("BLUETOOTH_ADVERTISE: ${if (info.advertisePermissionGranted) "✓ Granted" else "✗ Denied"}")
            appendLine("Advertising supported: ${if (info.advertisingSupported) "Yes" else "No"}")
            if (info.error != null) {
                appendLine("Error: ${info.error}")
            }
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_ble_status), content)
        cardsContainer.addView(cardView)
    }

    private fun addShizukuStatusCard(info: DiagnosticsInfoCollector.ShizukuStatusInfo) {
        val content = buildString {
            appendLine("Running: ${if (info.isRunning) "✓ Yes" else "✗ No"}")
            appendLine("Permission: ${if (info.permissionGranted) "✓ Granted" else "✗ Denied"}")
            if (info.versionInfo != null) {
                appendLine("Version: ${info.versionInfo}")
            }
            appendLine()
            appendLine("Tip: ${info.troubleshootingTip}")
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_shizuku_status), content)
        cardsContainer.addView(cardView)
    }

    private fun addHotspotStatusCard(info: DiagnosticsInfoCollector.HotspotStatusInfo) {
        val content = buildString {
            appendLine("SSID: ${info.ssid ?: "N/A"}")
            appendLine("Status: ${if (info.isEnabled) "Enabled" else "Disabled"}")
            appendLine("Config source: ${info.configSource}")
            appendLine("Credentials available: ${if (info.credentialsAvailable) "Yes" else "No"}")
            if (info.error != null) {
                appendLine("Error: ${info.error}")
            }
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_hotspot_status), content)
        cardsContainer.addView(cardView)
    }

    private fun addDatabaseInfoCard(info: DiagnosticsInfoCollector.DatabaseInfo) {
        val content = buildString {
            appendLine("Total devices: ${info.totalDevices}")
            appendLine("Approved: ${info.approvedCount}")
            appendLine("Ask each time: ${info.askCount}")
            appendLine("Denied: ${info.deniedCount}")
            appendLine("Database version: ${info.databaseVersion}")
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_database_info), content)
        cardsContainer.addView(cardView)
    }

    private fun addAppInfoCard(info: DiagnosticsInfoCollector.AppInfo) {
        val content = buildString {
            appendLine("Version: ${info.versionName} (${info.versionCode})")
            if (info.latestVersion != null) {
                appendLine("Latest version: ${info.latestVersion}")
            }
            appendLine("Update available: ${if (info.updateAvailable) "Yes" else "No"}")
            appendLine("Last update check: ${info.lastUpdateCheck}")
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_app_info), content)
        cardsContainer.addView(cardView)
    }

    private fun addSystemInfoCard(info: DiagnosticsInfoCollector.SystemInfo) {
        val content = buildString {
            appendLine("Android: ${info.androidVersion} (API ${info.sdkInt})")
            appendLine("Device: ${info.manufacturer} ${info.model}")
            appendLine("Available memory: ${info.availableMemoryMb} MB")
            appendLine("Available storage: ${info.availableStorageGb} GB")
        }
        val cardView = createCardView(getString(R.string.diagnostics_card_system_info), content)
        cardsContainer.addView(cardView)
    }

    private fun createCardView(title: String, content: String): LinearLayout {
        val inflater = LayoutInflater.from(this)
        val cardView = inflater.inflate(R.layout.item_diagnostic_card, cardsContainer, false) as LinearLayout

        val titleView = cardView.findViewById<TextView>(R.id.tv_card_title)
        val contentView = cardView.findViewById<TextView>(R.id.tv_card_content)

        titleView.text = title
        contentView.text = content

        return cardView
    }

    private fun showResultDialog(title: String, content: String) {
        val textView = TextView(this).apply {
            text = content
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scrollView = ScrollView(this).apply {
            addView(
                textView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.action_close)) { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun statusIcon(success: Boolean): String = if (success) "✅" else "❌"

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }
}
