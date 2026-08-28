package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleSessionCrypto
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
import javax.crypto.spec.SecretKeySpec

@SuppressLint("MissingPermission")
class GattClient(private val context: Context) {
    companion object {
        private const val TAG = "GattClient"
        private const val TARGET_MTU = 185
        private const val CONNECTION_TIMEOUT_MS = 10000L
    }
    private var gatt: BluetoothGatt? = null
    private var pendingDeviceIdRead = false
    private var pendingClientIdWrite = false

    // CCCD Cccd
    private var pendingHotspotCccdWrite = false
    private var pendingHotspotRead = false
    private var approvalPollJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var serviceDiscoveryTimeoutJob: Job? = null
    private var mtuFallbackJob: Job? = null
    private var serviceDiscoveryStarted = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clientKeyPair by lazy { BleSessionCrypto.clientKeyPair(context) }
    private var serverHello: BleSessionCrypto.ServerHello? = null
    private var sessionKey: SecretKeySpec? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedCredentials = MutableStateFlow<HotspotCredentials?>(null)
    val receivedCredentials: StateFlow<HotspotCredentials?> = _receivedCredentials.asStateFlow()

    private val _gattError = MutableStateFlow<String?>(null)
    val gattError: StateFlow<String?> = _gattError.asStateFlow()

    private val _approvalStatus = MutableStateFlow<ApprovalStatus?>(null)
    val approvalStatus: StateFlow<ApprovalStatus?> = _approvalStatus.asStateFlow()

    private val _serverDeviceId = MutableStateFlow<String?>(null)
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

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
        serviceDiscoveryStarted = false
        _serverDeviceId.value = null
        _pairingCode.value = null
        serverHello = null
        sessionKey = null

        gatt = device.connectGatt(context, false, GattCallbackImpl(), BluetoothDevice.TRANSPORT_LE)
        startConnectionTimeout()
    }

    fun disconnect() {
        stopApprovalPolling()
        stopConnectionTimeouts()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _receivedCredentials.value = null
        pendingDeviceIdRead = false
        pendingClientIdWrite = false
        pendingHotspotCccdWrite = false
        pendingHotspotRead = false
        serviceDiscoveryStarted = false
        serverHello = null
        sessionKey = null
        _pairingCode.value = null
    }

    private fun getConnectionTimeoutMs(): Long {
        return CONNECTION_TIMEOUT_MS
    }

    private fun startConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        val timeoutMs = getConnectionTimeoutMs()
        connectionTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                _gattError.value = "BLE connection timed out"
                _connectionState.value = ConnectionState.ERROR
                gatt?.close()
                gatt = null
            }
        }
    }

    private fun startServiceDiscoveryTimeout() {
        serviceDiscoveryTimeoutJob?.cancel()
        val timeoutMs = getConnectionTimeoutMs()
        serviceDiscoveryTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (_connectionState.value == ConnectionState.CONNECTED) {
                _gattError.value = "Service discovery timed out"
                _connectionState.value = ConnectionState.ERROR
                gatt?.close()
                gatt = null
            }
        }
    }

    private fun stopConnectionTimeouts() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        mtuFallbackJob?.cancel()
        mtuFallbackJob = null
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        if (!gatt.discoverServices()) {
            _gattError.value = "Could not start BLE service discovery"
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun getOrCreateStableClientId(): String {
        val prefs = context.getSharedPreferences("easierspot_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("stable_client_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "client-" + UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit { putString("stable_client_id", generated) }
        return generated
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

        val hello = serverHello ?: run {
            _gattError.value = "Secure server handshake is missing"
            return
        }
        val clientIdBytes = runCatching {
            BleSessionCrypto.createClientAuth(clientKeyPair, hello)
        }.getOrElse {
            _gattError.value = "Could not authenticate client: ${it.message}"
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, clientIdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = clientIdBytes
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic) == true
        }

        if (!started) {
            _gattError.value = "Failed to write client ID characteristic"
        } else {
            pendingClientIdWrite = true
            Log.d(TAG, "Writing authenticated v2 client identity")
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

        val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt!!.writeDescriptor(approvalCccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            approvalCccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION")
            gatt!!.writeDescriptor(approvalCccd)
        }

        if (!writeResult) {
            _gattError.value = "Failed to write approval CCCD"
        } else {
            Log.d(TAG, "Writing approval CCCD")
        }
    }

    private fun decodeHotspotData(data: ByteArray): HotspotCredentials? {
        if (data.size < 5) {
            _gattError.value = "Hotspot payload too short: ${data.size} bytes"
            return null
        }

        if (data[0] != BleConstants.PROTOCOL_VERSION) {
            _gattError.value = "Unsupported hotspot protocol version"
            return null
        }
        val securityType = HotspotCredentials.SecurityType.entries
            .getOrNull(data[1].toInt() and 0xFF)
            ?: run {
                _gattError.value = "Unsupported hotspot security type"
                return null
            }
        val isHidden = data[2].toInt() and 0x01 != 0
        val ssidLength = data[3].toInt() and 0xFF
        val ssidStart = 4
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

        return HotspotCredentials(ssid, password, securityType, isHidden)
    }

    private fun decryptHotspotData(envelope: ByteArray): HotspotCredentials? {
        val key = sessionKey
        val hello = serverHello
        if (key == null || hello == null) {
            _gattError.value = "Secure BLE session is unavailable"
            return null
        }
        val plaintext = runCatching {
            BleSessionCrypto.decrypt(key, envelope, hello.nonce)
        }.getOrElse {
            _gattError.value = "Credential authentication failed"
            return null
        }
        return try {
            decodeHotspotData(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private inner class GattCallbackImpl : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopConnectionTimeouts()
                _gattError.value = "GATT connection failed (status=$status)"
                _connectionState.value = ConnectionState.ERROR
                gatt.close()
                if (this@GattClient.gatt === gatt) this@GattClient.gatt = null
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    _connectionState.value = ConnectionState.CONNECTED
                    startServiceDiscoveryTimeout()
                    val mtuRequested = gatt.requestMtu(TARGET_MTU)
                    if (!mtuRequested) {
                        Log.w(TAG, "Failed to request MTU $TARGET_MTU, continuing with default MTU")
                        beginServiceDiscovery(gatt)
                    } else {
                        Log.d(TAG, "Requested MTU $TARGET_MTU")
                        mtuFallbackJob?.cancel()
                        mtuFallbackJob = scope.launch {
                            delay(2_000L)
                            Log.w(TAG, "MTU callback timed out; continuing service discovery")
                            beginServiceDiscovery(gatt)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopConnectionTimeouts()
                    if (_connectionState.value != ConnectionState.ERROR) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed successfully to $mtu")
            } else {
                Log.w(TAG, "MTU change failed with status=$status, using current MTU")
            }
            beginServiceDiscovery(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableServerNotifications()
                pendingDeviceIdRead = true
            } else {
                _gattError.value = "Service discovery failed"
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)

            if (status != BluetoothGatt.GATT_SUCCESS) {
                _gattError.value = "Characteristic read failed"
                return
            }

            when (characteristic.uuid) {
                BleConstants.CHAR_DEVICE_ID -> {
                    val hello = runCatching { BleSessionCrypto.parseServerHello(value) }
                        .getOrElse {
                            _gattError.value = "Invalid server handshake: ${it.message}"
                            _connectionState.value = ConnectionState.ERROR
                            return
                        }
                    serverHello = hello
                    _serverDeviceId.value = BleSessionCrypto.fingerprint(hello.publicKey)
                    sessionKey = BleSessionCrypto.sessionKey(
                        clientKeyPair.private,
                        hello.publicKey,
                        hello.nonce
                    )
                    _pairingCode.value = BleSessionCrypto.pairingCode(sessionKey!!, hello.nonce)
                    writeClientIdCharacteristic()
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val statusValue = value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Approval status read: 0x${String.format("%02X", statusValue)}")
                    when (statusValue) {
                        BleConstants.APPROVAL_GRANTED.toInt() -> {
                            Log.d(TAG, "Already approved, reading hotspot data")
                            stopApprovalPolling()
                            _approvalStatus.value = ApprovalStatus.APPROVED
                            pendingHotspotRead = true
                            readHotspotDataCharacteristic()
                        }
                        BleConstants.APPROVAL_PENDING.toInt() ->
                            Log.d(TAG, "Pending approval; waiting for server indication")
                        BleConstants.APPROVAL_DENIED.toInt() -> {
                            _approvalStatus.value = ApprovalStatus.DENIED
                            pendingHotspotRead = false
                        }
                        else -> {
                            Log.w(TAG, "Unknown approval status: $statusValue")
                        }
                    }
                }
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    if (value.isNotEmpty()) {
                        val credentials = decryptHotspotData(value)
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic.uuid}, value size=${value.size}")

            when (characteristic.uuid) {
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    Log.d(TAG, "Received hotspot notification, data size=${value.size}")
                    val credentials = decryptHotspotData(value)
                    if (credentials != null) {
                        Log.d(TAG, "Decoded hotspot credentials: SSID=${credentials.ssid}")
                        stopApprovalPolling()
                        _receivedCredentials.value = credentials
                    } else {
                        Log.w(TAG, "Failed to decode hotspot data")
                    }
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val status = value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Received approval notification: 0x${String.format("%02X", status)}")
                    when (status) {
                        BleConstants.APPROVAL_GRANTED.toInt() -> {
                            Log.d(TAG, "Approval granted by server")
                            stopApprovalPolling()
                            _approvalStatus.value = ApprovalStatus.APPROVED
                            if (_receivedCredentials.value == null && !pendingHotspotRead) {
                                pendingHotspotRead = true
                                readHotspotDataCharacteristic()
                            }
                        }
                        BleConstants.APPROVAL_DENIED.toInt() -> {
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
                    val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(hotspotCccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        hotspotCccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(hotspotCccd)
                    }
                    if (!writeResult) {
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
