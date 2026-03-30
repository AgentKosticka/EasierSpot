package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import androidx.core.content.edit

@SuppressLint("MissingPermission")
class GattClient(private val context: Context) {
    companion object {
        private const val TAG = "GattClient"
        private const val TARGET_MTU = 517
        private const val APPROVAL_POLL_INTERVAL_MS = 2000L
        private const val APPROVAL_POLL_MAX_ATTEMPTS = 30 // 60 seconds max wait
    }
    private var gatt: BluetoothGatt? = null
    private var pendingDeviceIdRead = false
    private var pendingClientIdWrite = false

    // CCCD Cccd
    private var pendingHotspotCccdWrite = false
    private var pendingHotspotRead = false
    private var approvalPollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedCredentials = MutableStateFlow<HotspotCredentials?>(null)
    val receivedCredentials: StateFlow<HotspotCredentials?> = _receivedCredentials.asStateFlow()

    private val _gattError = MutableStateFlow<String?>(null)
    val gattError: StateFlow<String?> = _gattError.asStateFlow()

    private val _approvalStatus = MutableStateFlow<ApprovalStatus?>(null)
    val approvalStatus: StateFlow<ApprovalStatus?> = _approvalStatus.asStateFlow()

    private val _serverDeviceId = MutableStateFlow<String?>(null)

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    enum class ApprovalStatus {
        APPROVED,
        DENIED
    }

    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            _gattError.value = "Missing Bluetooth permissions"
            _connectionState.value = ConnectionState.ERROR
            return
        }

        stopApprovalPolling()
        gatt?.close()
        gatt = null

        _connectionState.value = ConnectionState.CONNECTING
        _gattError.value = null
        _receivedCredentials.value = null
        _approvalStatus.value = null
        pendingDeviceIdRead = false
        pendingClientIdWrite = false
        pendingHotspotCccdWrite = false
        pendingHotspotRead = false
        _serverDeviceId.value = null

        gatt = device.connectGatt(context, false, GattCallbackImpl())
    }

    fun disconnect() {
        stopApprovalPolling()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _receivedCredentials.value = null
        pendingDeviceIdRead = false
        pendingClientIdWrite = false
        pendingHotspotCccdWrite = false
        pendingHotspotRead = false
    }

    private fun getOrCreateStableClientId(): String {
        val prefs = context.getSharedPreferences("easierspot_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("stable_client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "client-" + UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit { putString("stable_client_id", generated) }
        return generated
    }
    
    private fun startApprovalPolling() {
        stopApprovalPolling()
        Log.d(TAG, "Starting approval polling (fallback for missed notifications)")
        approvalPollJob = scope.launch {
            var attempts = 0
            while (attempts < APPROVAL_POLL_MAX_ATTEMPTS && 
                   _approvalStatus.value != ApprovalStatus.APPROVED &&
                   _approvalStatus.value != ApprovalStatus.DENIED &&
                   _receivedCredentials.value == null &&
                   _connectionState.value == ConnectionState.CONNECTED) {
                delay(APPROVAL_POLL_INTERVAL_MS)
                attempts++
                Log.d(TAG, "Polling approval status (attempt $attempts)")
                readApprovalCharacteristic()
            }
            if (attempts >= APPROVAL_POLL_MAX_ATTEMPTS) {
                Log.w(TAG, "Approval polling timed out after ${APPROVAL_POLL_MAX_ATTEMPTS * APPROVAL_POLL_INTERVAL_MS / 1000} seconds")
            }
        }
    }
    
    private fun stopApprovalPolling() {
        approvalPollJob?.cancel()
        approvalPollJob = null
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun readDeviceIdCharacteristic() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_DEVICE_ID)
        if (characteristic != null) {
            val started = gatt?.readCharacteristic(characteristic) == true
            if (!started) {
                _gattError.value = "Failed to start device ID read"
            }
        } else {
            _gattError.value = "Device ID characteristic missing"
        }
    }

    private fun writeClientIdCharacteristic() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_CLIENT_ID)
        if (characteristic == null) {
            _gattError.value = "Client ID characteristic missing on server"
            return
        }
        val clientId = getOrCreateStableClientId()
        characteristic.value = clientId.toByteArray(StandardCharsets.UTF_8)
        val started = gatt?.writeCharacteristic(characteristic) == true
        if (!started) {
            _gattError.value = "Failed to write client ID characteristic"
        } else {
            pendingClientIdWrite = true
            Log.d(TAG, "Writing client stable ID: $clientId")
        }
    }

    private fun readApprovalCharacteristic() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_APPROVAL_STATUS) ?: return
        val started = gatt?.readCharacteristic(characteristic) == true
        if (!started) {
            _gattError.value = "Failed to read approval status"
        } else {
            Log.d(TAG, "Reading approval status characteristic")
        }
    }

    private fun readHotspotDataCharacteristic() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_HOTSPOT_DATA) ?: return
        val started = gatt?.readCharacteristic(characteristic) == true
        if (!started) {
            _gattError.value = "Failed to read hotspot data characteristic"
        } else {
            Log.d(TAG, "Reading hotspot data characteristic")
        }
    }

    private fun enableServerNotifications() {
        val service = gatt?.getService(BleConstants.SERVICE_UUID) ?: run {
            _gattError.value = "BLE service not found"
            return
        }

        val approvalChar = service.getCharacteristic(BleConstants.CHAR_APPROVAL_STATUS)
        val hotspotChar = service.getCharacteristic(BleConstants.CHAR_HOTSPOT_DATA)
        if (approvalChar == null || hotspotChar == null) {
            _gattError.value = "Required BLE characteristics missing"
            return
        }

        if (!gatt!!.setCharacteristicNotification(approvalChar, true)) {
            _gattError.value = "Failed to enable approval notifications"
            return
        }
        if (!gatt!!.setCharacteristicNotification(hotspotChar, true)) {
            _gattError.value = "Failed to enable hotspot notifications"
            return
        }

        val approvalCccd = approvalChar.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
        val hotspotCccd = hotspotChar.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (approvalCccd == null || hotspotCccd == null) {
            _gattError.value = "CCCD descriptor missing on server"
            return
        }

        approvalCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt!!.writeDescriptor(approvalCccd)) {
            _gattError.value = "Failed to write approval CCCD"
        } else {
            Log.d(TAG, "Writing approval CCCD")
        }
    }

    private fun decodeHotspotData(data: ByteArray): HotspotCredentials? {
        if (data.size < 2) {
            _gattError.value = "Hotspot payload too short: ${data.size} bytes"
            return null
        }

        val ssidLength = data[0].toInt() and 0xFF
        val ssidStart = 1
        val ssidEnd = ssidStart + ssidLength
        if (data.size < ssidEnd + 1) {
            _gattError.value =
                "Hotspot payload truncated before password length (expected >= ${ssidEnd + 1}, got ${data.size})"
            return null
        }

        val passwordLength = data[ssidEnd].toInt() and 0xFF
        val passwordStart = ssidEnd + 1
        val passwordEnd = passwordStart + passwordLength
        if (data.size < passwordEnd) {
            _gattError.value =
                "Hotspot payload truncated before password bytes (expected >= $passwordEnd, got ${data.size})"
            return null
        }

        val ssid = String(data.copyOfRange(ssidStart, ssidEnd), StandardCharsets.UTF_8)
        val password = String(data.copyOfRange(passwordStart, passwordEnd), StandardCharsets.UTF_8)

        if (ssid.isEmpty()) {
            _gattError.value = "Hotspot payload decoded with empty SSID"
            return null
        }

        return HotspotCredentials(ssid, password)
    }

    private inner class GattCallbackImpl : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    val mtuRequested = gatt.requestMtu(TARGET_MTU)
                    if (!mtuRequested) {
                        Log.w(TAG, "Failed to request MTU $TARGET_MTU, continuing with default MTU")
                        gatt.discoverServices()
                    } else {
                        Log.d(TAG, "Requested MTU $TARGET_MTU")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed successfully to $mtu")
            } else {
                Log.w(TAG, "MTU change failed with status=$status, using current MTU")
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableServerNotifications()
                pendingDeviceIdRead = true
            } else {
                _gattError.value = "Service discovery failed"
                _connectionState.value = ConnectionState.ERROR
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                _gattError.value = "Characteristic read failed"
                return
            }

            when (characteristic.uuid) {
                BleConstants.CHAR_DEVICE_ID -> {
                    Log.d(TAG, "Device ID characteristic read")
                    val raw = characteristic.value
                    val serverId = String(raw, StandardCharsets.UTF_8).trim()
                    _serverDeviceId.value = serverId
                    // Successfully read device ID, connection is established
                    // Now we wait for hotspot data notification
                    writeClientIdCharacteristic()
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val statusValue = characteristic.value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Approval status read: 0x${String.format("%02X", statusValue)}")
                    when (statusValue) {
                        0x01 -> {
                            Log.d(TAG, "Already approved, reading hotspot data")
                            stopApprovalPolling()
                            _approvalStatus.value = ApprovalStatus.APPROVED
                            pendingHotspotRead = true
                            readHotspotDataCharacteristic()
                        }
                        0x00 -> {
                            // Not yet approved - start polling as fallback for missed notifications
                            Log.d(TAG, "Pending approval, starting poll loop...")
                            if (approvalPollJob == null || approvalPollJob?.isActive != true) {
                                startApprovalPolling()
                            }
                        }
                        else -> {
                            Log.w(TAG, "Unknown approval status: $statusValue")
                        }
                    }
                }
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    if (characteristic.value.isNotEmpty()) {
                        val credentials = decodeHotspotData(characteristic.value)
                        if (credentials != null) {
                            Log.d(TAG, "Received hotspot payload via read for SSID=${credentials.ssid}")
                            stopApprovalPolling()
                            _receivedCredentials.value = credentials
                            pendingHotspotRead = false
                        }
                    } else {
                        Log.w(TAG, "Hotspot data read returned empty - credentials not ready yet")
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic.uuid}, value size=${characteristic.value?.size ?: 0}")

            when (characteristic.uuid) {
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    Log.d(TAG, "Received hotspot notification, data size=${characteristic.value?.size ?: 0}")
                    val credentials = decodeHotspotData(characteristic.value)
                    if (credentials != null) {
                        Log.d(TAG, "Decoded hotspot credentials: SSID=${credentials.ssid}")
                        stopApprovalPolling()
                        _receivedCredentials.value = credentials
                    } else {
                        Log.w(TAG, "Failed to decode hotspot data")
                    }
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val status = characteristic.value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Received approval notification: 0x${String.format("%02X", status)}")
                    when (status) {
                        0x01 -> {
                            Log.d(TAG, "Approval granted by server")
                            stopApprovalPolling()
                            _approvalStatus.value = ApprovalStatus.APPROVED
                            if (_receivedCredentials.value == null && !pendingHotspotRead) {
                                pendingHotspotRead = true
                                readHotspotDataCharacteristic()
                            }
                        }
                        0x00 -> {
                            Log.d(TAG, "Connection denied by server")
                            stopApprovalPolling()
                            pendingHotspotRead = false
                            _approvalStatus.value = ApprovalStatus.DENIED
                        }
                        else -> {
                            Log.w(TAG, "Unknown approval notification value: $status")
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _gattError.value = "Descriptor write failed"
                return
            }

            if (descriptor.characteristic.uuid == BleConstants.CHAR_APPROVAL_STATUS) {
                val hotspotChar = gatt.getService(BleConstants.SERVICE_UUID)
                    ?.getCharacteristic(BleConstants.CHAR_HOTSPOT_DATA)
                val hotspotCccd = hotspotChar?.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
                if (hotspotCccd != null) {
                    hotspotCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!gatt.writeDescriptor(hotspotCccd)) {
                        _gattError.value = "Failed to write hotspot CCCD"
                    } else {
                        pendingHotspotCccdWrite = true
                        Log.d(TAG, "Writing hotspot CCCD")
                    }
                } else {
                    _gattError.value = "Hotspot CCCD missing"
                }
                return
            }

            if (descriptor.characteristic.uuid == BleConstants.CHAR_HOTSPOT_DATA && pendingHotspotCccdWrite) {
                pendingHotspotCccdWrite = false
                if (pendingDeviceIdRead) {
                    pendingDeviceIdRead = false
                    Log.d(TAG, "CCCD setup complete, reading device ID")
                    readDeviceIdCharacteristic()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (characteristic.uuid != BleConstants.CHAR_CLIENT_ID || !pendingClientIdWrite) {
                return
            }
            pendingClientIdWrite = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Client stable ID write succeeded, reading approval status")
                readApprovalCharacteristic()
            } else {
                _gattError.value = "Client ID write failed (status=$status)"
            }
        }
    }
}
