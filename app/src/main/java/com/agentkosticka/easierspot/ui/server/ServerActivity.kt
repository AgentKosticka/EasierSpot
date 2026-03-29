package com.agentkosticka.easierspot.ui.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.service.BleHotspotService
import com.agentkosticka.easierspot.ui.dialogs.ApprovalDialog
import com.agentkosticka.easierspot.ui.dialogs.HotspotTestDialog
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.launch
import java.util.UUID

class ServerActivity : AppCompatActivity(), ApprovalDialog.ApprovalListener {
    companion object {
        private const val TAG = "ServerActivity"
        private const val REQUEST_ENABLE_BT = 1001
        private const val APPROVAL_DIALOG_TAG = "approval_dialog"
        private const val TEST_DIALOG_TAG = "test_dialog"
    }
    
    private val deviceId = UUID.randomUUID().toString().take(4)
    private val hotspotManager by lazy { HotspotManager(this) }
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val rememberedDeviceRows = mutableListOf<Map<String, String>>()
    private var rememberedAdapter: SimpleAdapter? = null
    private var approvalReceiverRegistered = false
    private val approvalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
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
            val testButton = findViewById<Button>(R.id.btn_test_hotspot)
            val rememberedList = findViewById<ListView>(R.id.list_remembered_devices)

            rememberedAdapter = SimpleAdapter(
                this,
                rememberedDeviceRows,
                android.R.layout.simple_list_item_2,
                arrayOf("name", "meta"),
                intArrayOf(android.R.id.text1, android.R.id.text2)
            )
            rememberedList.adapter = rememberedAdapter

            rememberedList.setOnItemLongClickListener { _, _, position, _ ->
                val stableId = rememberedDeviceRows.getOrNull(position)?.get("stableId")
                if (!stableId.isNullOrBlank()) {
                    lifecycleScope.launch {
                        database.rememberedServerDao().deleteServer(stableId)
                        Toast.makeText(this@ServerActivity, "Removed remembered device", Toast.LENGTH_SHORT).show()
                    }
                }
                true
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
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                } catch (e: Exception) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            testButton.setOnClickListener {
                runHotspotTest()
            }

            stopButton.isEnabled = false
            showPendingApprovalIfPresent(intent)
            observeRememberedDevices()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun observeRememberedDevices() {
        lifecycleScope.launch {
            database.rememberedServerDao().getAllServers().collect { servers ->
                rememberedDeviceRows.clear()
                servers.forEach { server ->
                    rememberedDeviceRows.add(
                        mapOf(
                            "name" to "${server.deviceName} (${server.deviceId})",
                            "meta" to "Last address: ${server.deviceAddress.ifBlank { "unknown" }} | Approved: ${server.isApproved}"
                        )
                    )
                }
                rememberedAdapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPendingApprovalIfPresent(intent)
    }

    private fun showPendingApprovalIfPresent(sourceIntent: Intent?) {
        val clientAddress = sourceIntent?.getStringExtra(BleHotspotService.EXTRA_CLIENT_ADDRESS) ?: return
        val clientDeviceId = sourceIntent.getStringExtra(BleHotspotService.EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
        val clientName = sourceIntent.getStringExtra(BleHotspotService.EXTRA_CLIENT_NAME) ?: "Unknown Device"

        if (supportFragmentManager.findFragmentByTag(APPROVAL_DIALOG_TAG) != null) {
            return
        }

        ApprovalDialog.newInstance(clientDeviceId, clientName, clientAddress)
            .show(supportFragmentManager, APPROVAL_DIALOG_TAG)
    }

    private fun startSharing() {
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
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
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
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }

                        findViewById<Button>(R.id.btn_start_sharing).isEnabled = false
                        findViewById<Button>(R.id.btn_stop_sharing).isEnabled = true
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

    private fun runHotspotTest() {
        ShizukuHelper.requestShizukuPermission(
            this,
            onGranted = {
                val diagnostics = hotspotManager.getHotspotDiagnostics()
                HotspotTestDialog.newInstance(diagnostics)
                    .show(supportFragmentManager, TEST_DIALOG_TAG)
            },
            onDenied = {
                Toast.makeText(this, "Shizuku permission required for testing", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun stopSharing() {
        Toast.makeText(this, "Stopping hotspot sharing", Toast.LENGTH_SHORT).show()

        val serviceIntent = Intent(this, BleHotspotService::class.java).apply {
            action = BleHotspotService.ACTION_STOP_SERVER
        }
        startService(serviceIntent)
    }

    override fun onApprove(deviceId: String, deviceAddress: String) {
        Toast.makeText(this, "Approved device: $deviceId", Toast.LENGTH_SHORT).show()

        // Notify service to send credentials
        val approveIntent = Intent(this, BleHotspotService::class.java).apply {
            action = BleHotspotService.ACTION_APPROVE_CLIENT
            putExtra(BleHotspotService.EXTRA_CLIENT_ADDRESS, deviceAddress)
            putExtra(BleHotspotService.EXTRA_CLIENT_DEVICE_ID, deviceId)
            putExtra(BleHotspotService.EXTRA_CLIENT_NAME, "Client-$deviceId")
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

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop service on activity destroy - it should run in background
    }

    override fun onStart() {
        super.onStart()
        if (!approvalReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    approvalReceiver,
                    IntentFilter(BleHotspotService.ACTION_SHOW_APPROVAL),
                    RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(approvalReceiver, IntentFilter(BleHotspotService.ACTION_SHOW_APPROVAL))
            }
            approvalReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (approvalReceiverRegistered) {
            unregisterReceiver(approvalReceiver)
            approvalReceiverRegistered = false
        }
    }
}
