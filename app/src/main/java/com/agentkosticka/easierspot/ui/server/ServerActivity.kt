package com.agentkosticka.easierspot.ui.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.service.BleHotspotService
import com.agentkosticka.easierspot.ui.dialogs.ApprovalDialog
import com.agentkosticka.easierspot.ui.dialogs.RememberedDeviceDialog
import com.agentkosticka.easierspot.ui.settings.SettingsActivity
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.launch
import java.util.UUID

class ServerActivity : AppCompatActivity(), ApprovalDialog.ApprovalListener, RememberedDeviceDialog.RememberedDeviceListener {
    companion object {
        private const val TAG = "ServerActivity"
        private const val APPROVAL_DIALOG_TAG = "approval_dialog"
        private const val REMEMBERED_DIALOG_TAG = "remembered_dialog"
    }
    
    private val deviceId = UUID.randomUUID().toString().take(4)
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val rememberedDeviceRows = mutableListOf<Map<String, String>>()
    private var rememberedAdapter: SimpleAdapter? = null
    private var approvalReceiverRegistered = false
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBound = (service as? BleHotspotService.LocalBinder)?.getService() != null
            applyServerUiState(BleHotspotService.isServerRunning)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            applyServerUiState(false)
        }
    }
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled. Tap Start Sharing again.", Toast.LENGTH_SHORT).show()
            }
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSharing()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.server_notifications_required_message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    private val approvalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleHotspotService.ACTION_SHOW_APPROVAL) {
                showPendingApprovalIfPresent(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_server)
            title = "Share Hotspot"

            val startButton = findViewById<Button>(R.id.btn_start_sharing)
            val stopButton = findViewById<Button>(R.id.btn_stop_sharing)
            val settingsButton = findViewById<ImageButton>(R.id.btn_server_settings)
            val rememberedList = findViewById<ListView>(R.id.list_remembered_devices)

            rememberedAdapter = SimpleAdapter(
                this,
                rememberedDeviceRows,
                android.R.layout.simple_list_item_2,
                arrayOf("name", "meta"),
                intArrayOf(android.R.id.text1, android.R.id.text2)
            )
            rememberedList.adapter = rememberedAdapter

            rememberedList.setOnItemClickListener { _, _, position, _ ->
                val selected = rememberedDeviceRows.getOrNull(position) ?: return@setOnItemClickListener
                val stableId = selected["stableId"] ?: return@setOnItemClickListener
                val deviceName = selected["deviceName"] ?: stableId
                val nickname = selected["nickname"]
                val approvalPolicy = selected["approvalPolicy"] ?: RememberedServer.APPROVAL_POLICY_ASK

                if (supportFragmentManager.findFragmentByTag(REMEMBERED_DIALOG_TAG) == null) {
                    RememberedDeviceDialog.newInstance(
                        deviceId = stableId,
                        deviceName = deviceName,
                        currentNickname = nickname,
                        approvalPolicy = approvalPolicy
                    ).show(supportFragmentManager, REMEMBERED_DIALOG_TAG)
                }
            }

            startButton.setOnClickListener {
                try {
                    startSharing()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            stopButton.setOnClickListener {
                try {
                    stopSharing()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            settingsButton.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            stopButton.isEnabled = false
            showPendingApprovalIfPresent(intent)
            observeRememberedDevices()
            probeServiceLiveness()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun observeRememberedDevices() {
        lifecycleScope.launch {
            database.rememberedServerDao().getAllServers().collect { servers ->
                rememberedDeviceRows.clear()
                val now = System.currentTimeMillis()
                servers.forEach { server ->
                    val canonicalId = server.deviceId
                        .trim()
                        .replace(Regex("^(?i)(client-)+"), "")
                        .ifBlank { server.deviceId }
                    val nickname = server.nickname?.trim().orEmpty()
                    val title = nickname.ifEmpty { canonicalId }
                    val lastApprovedText = if (server.lastApprovedAt > 0L) {
                        val secondsAgo = ((now - server.lastApprovedAt).coerceAtLeast(0L)) / 1000
                        getString(R.string.remembered_last_approved_seconds_ago, secondsAgo)
                    } else {
                        getString(R.string.remembered_last_approved_never)
                    }
                    val meta = if (nickname.isNotEmpty()) {
                        getString(
                            R.string.remembered_meta_with_id_policy,
                            canonicalId,
                            policyLabel(server.approvalPolicy),
                            lastApprovedText
                        )
                    } else {
                        getString(
                            R.string.remembered_meta_with_policy,
                            policyLabel(server.approvalPolicy),
                            lastApprovedText
                        )
                    }

                    rememberedDeviceRows.add(
                        mapOf(
                            "name" to title,
                            "meta" to meta,
                            "stableId" to server.deviceId,
                            "deviceName" to server.deviceName,
                            "nickname" to nickname,
                            "approvalPolicy" to server.approvalPolicy
                        )
                    )
                }
                rememberedAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun policyLabel(policy: String): String {
        return when (policy) {
            RememberedServer.APPROVAL_POLICY_APPROVED -> getString(R.string.approval_policy_approved)
            RememberedServer.APPROVAL_POLICY_DENIED -> getString(R.string.approval_policy_denied)
            else -> getString(R.string.approval_policy_ask)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPendingApprovalIfPresent(intent)
    }

    private fun showPendingApprovalIfPresent(sourceIntent: Intent?) {
        val clientAddress = sourceIntent?.getStringExtra(BleHotspotService.EXTRA_CLIENT_ADDRESS) ?: return
        val rawClientDeviceId = sourceIntent.getStringExtra(BleHotspotService.EXTRA_CLIENT_DEVICE_ID)
        val rawClientName = sourceIntent.getStringExtra(BleHotspotService.EXTRA_CLIENT_NAME)
        val nickname = sourceIntent.getStringExtra(BleHotspotService.EXTRA_APPROVAL_NICKNAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val displayId = sourceIntent.getStringExtra(BleHotspotService.EXTRA_APPROVAL_DISPLAY_ID)
            ?: BleHotspotService.normalizeIdentityForDisplay(rawClientDeviceId)
        val displayName = sourceIntent.getStringExtra(BleHotspotService.EXTRA_APPROVAL_DISPLAY_NAME)
            ?: BleHotspotService.normalizeIdentityForDisplay(rawClientName)
                .takeUnless { it == "Unknown" }
            ?: displayId
        val isRememberedClient = sourceIntent.getBooleanExtra(
            BleHotspotService.EXTRA_APPROVAL_IS_REMEMBERED,
            false
        )

        if (supportFragmentManager.findFragmentByTag(APPROVAL_DIALOG_TAG) != null) {
            return
        }

        ApprovalDialog.newInstance(
            deviceId = rawClientDeviceId ?: displayId,
            deviceName = rawClientName ?: displayName,
            deviceAddress = clientAddress,
            isNewDevice = !isRememberedClient,
            nickname = nickname
        )
            .show(supportFragmentManager, APPROVAL_DIALOG_TAG)
    }

    private fun startSharing() {
        if (!ensureNotificationPermissionForServer()) {
            return
        }

        // Check if Bluetooth is enabled
        try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            
            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
                return
            }
            
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_LONG).show()
                val hasConnectPermission = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasConnectPermission) {
                    Toast.makeText(this, "Missing Bluetooth permission", Toast.LENGTH_LONG).show()
                    return
                }
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
                return
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error checking Bluetooth status", e)
            Toast.makeText(this, "Error checking Bluetooth: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        
        // Check Shizuku before starting
        try {
            ShizukuHelper.requestShizukuPermission(
                this,
                onGranted = {
                    try {
                        Toast.makeText(this, "Starting hotspot sharing... Device ID: $deviceId", Toast.LENGTH_SHORT).show()

                        // Start foreground service with server mode
                        val serviceIntent = Intent(this, BleHotspotService::class.java).apply {
                            action = BleHotspotService.ACTION_START_SERVER
                            putExtra(BleHotspotService.EXTRA_DEVICE_ID, deviceId)
                        }

                        startForegroundService(serviceIntent)
                        probeServiceLiveness()
                        window.decorView.postDelayed({ probeServiceLiveness() }, 350L)
                    } catch (e: Exception) {
                        LogUtils.e(TAG, "Error starting service", e)
                        Toast.makeText(this, "Service start failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                onDenied = {
                    Toast.makeText(this, "Shizuku permission required for server mode", Toast.LENGTH_LONG).show()
                }
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in startSharing", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureNotificationPermissionForServer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            return true
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }

    private fun stopSharing() {
        Toast.makeText(this, "Stopping hotspot sharing", Toast.LENGTH_SHORT).show()

        val serviceIntent = Intent(this, BleHotspotService::class.java).apply {
            action = BleHotspotService.ACTION_STOP_SERVER
        }
        startService(serviceIntent)
        applyServerUiState(false)
        window.decorView.postDelayed({ probeServiceLiveness() }, 200L)
    }

    private fun applyServerUiState(isRunning: Boolean) {
        findViewById<Button>(R.id.btn_start_sharing).isEnabled = !isRunning
        findViewById<Button>(R.id.btn_stop_sharing).isEnabled = isRunning
    }

    private fun probeServiceLiveness() {
        if (serviceBound) {
            applyServerUiState(BleHotspotService.isServerRunning)
            return
        }
        val didBind = bindService(Intent(this, BleHotspotService::class.java), serviceConnection, 0)
        if (!didBind) {
            applyServerUiState(false)
        }
    }

    override fun onApprove(deviceId: String, deviceName: String?, deviceAddress: String) {
        Toast.makeText(this, "Approved device: $deviceId", Toast.LENGTH_SHORT).show()

        // Notify service to send credentials
        val approveIntent = Intent(this, BleHotspotService::class.java).apply {
            action = BleHotspotService.ACTION_APPROVE_CLIENT
            putExtra(BleHotspotService.EXTRA_CLIENT_ADDRESS, deviceAddress)
            putExtra(BleHotspotService.EXTRA_CLIENT_DEVICE_ID, deviceId)
            putExtra(
                BleHotspotService.EXTRA_CLIENT_NAME,
                deviceName?.takeIf { it.isNotBlank() } ?: "Unknown Device"
            )
        }
        startService(approveIntent)
    }

    override fun onDeny(deviceAddress: String) {
        Toast.makeText(this, "Denied connection", Toast.LENGTH_SHORT).show()
        
        // Notify service to deny client
        val denyIntent = Intent(this, BleHotspotService::class.java).apply {
            action = BleHotspotService.ACTION_DENY_CLIENT
            putExtra(BleHotspotService.EXTRA_CLIENT_ADDRESS, deviceAddress)
        }
        startService(denyIntent)
    }

    override fun onRememberedDeviceSave(deviceId: String, nickname: String?, approvalPolicy: String) {
        lifecycleScope.launch {
            val normalizedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }
            val dao = database.rememberedServerDao()
            val current = dao.getServerById(deviceId)
            if (current != null) {
                val updatedApprovedState = when (approvalPolicy) {
                    RememberedServer.APPROVAL_POLICY_APPROVED -> true
                    RememberedServer.APPROVAL_POLICY_DENIED -> false
                    else -> current.isApproved
                }
                dao.insertServer(
                    current.copy(
                        nickname = normalizedNickname,
                        approvalPolicy = approvalPolicy,
                        isApproved = updatedApprovedState
                    )
                )
            } else {
                dao.updateNickname(deviceId, normalizedNickname)
                dao.updateApprovalPolicy(deviceId, approvalPolicy)
            }
            Toast.makeText(
                this@ServerActivity,
                getString(R.string.remembered_device_updated),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRememberedDeviceForget(deviceId: String) {
        lifecycleScope.launch {
            database.rememberedServerDao().deleteServer(deviceId)
            Toast.makeText(
                this@ServerActivity,
                getString(R.string.remembered_device_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop service on activity destroy - it should run in background
    }

    override fun onStart() {
        super.onStart()
        probeServiceLiveness()
        if (!approvalReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                approvalReceiver,
                IntentFilter(BleHotspotService.ACTION_SHOW_APPROVAL),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            approvalReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        if (approvalReceiverRegistered) {
            unregisterReceiver(approvalReceiver)
            approvalReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        probeServiceLiveness()
    }
}
