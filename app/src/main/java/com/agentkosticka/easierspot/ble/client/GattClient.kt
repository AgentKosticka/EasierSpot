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
import com.agentkosticka.easierspot.ble.BleAuthFraming
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import com.agentkosticka.easierspot.ble.ServerStatusMessage
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
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@SuppressLint("MissingPermission")
class GattClient(private val context: Context) {
    companion object {
        private const val TAG = "GattClient"
        // Android and the peer negotiate the mutually supported value. The callback is the
        // authority; the request itself is never treated as proof that a larger MTU is usable.
        private const val TARGET_MTU = 517
        private const val CONNECTION_TIMEOUT_MS = 10000L
    }
    private var gatt: BluetoothGatt? = null
    private var pendingDeviceIdRead = false
    private var pendingClientIdWrite = false

    // CCCD Cccd
    private var pendingHotspotCccdWrite = false
    private var pendingServerStatusCccdWrite = false
    private var pendingHotspotRead = false
    private var pendingApprovalRead = false
    private var approvalPollJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var serviceDiscoveryTimeoutJob: Job? = null
    private var mtuFallbackJob: Job? = null
    private var clientAuthFallbackJob: Job? = null
    private var serviceDiscoveryStarted = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clientKeyPair by lazy { BleSessionCrypto.clientKeyPair(context) }
    private var serverHello: BleSessionCrypto.ServerHello? = null
    private var sessionKey: SecretKeySpec? = null
    private var expectedServerFingerprint: String? = null
    private var controlCounter = 0L
    private var negotiatedMtu = 23
    private var authFrames: List<ByteArray> = emptyList()
    private var authFrameIndex = 0
    private var pendingAuthBytes: ByteArray? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _phase = MutableStateFlow<GattPhase>(GattPhase.Idle)
    val phase: StateFlow<GattPhase> = _phase.asStateFlow()

    private val _receivedCredentials = MutableStateFlow<HotspotCredentials?>(null)
    val receivedCredentials: StateFlow<HotspotCredentials?> = _receivedCredentials.asStateFlow()

    private val _gattError = MutableStateFlow<String?>(null)
    val gattError: StateFlow<String?> = _gattError.asStateFlow()

    private val _approvalStatus = MutableStateFlow<ApprovalStatus?>(null)
    val approvalStatus: StateFlow<ApprovalStatus?> = _approvalStatus.asStateFlow()

    private val _serverDeviceId = MutableStateFlow<String?>(null)
    val serverDeviceId: StateFlow<String?> = _serverDeviceId.asStateFlow()
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()
    private val _serverStatus = MutableStateFlow<ServerStatusMessage?>(null)
    val serverStatus: StateFlow<ServerStatusMessage?> = _serverStatus.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    enum class ApprovalStatus {
        APPROVED,
        DENIED,
        HOTSPOT_STARTING,
        ACTIVATION_FAILED
    }

    sealed interface GattPhase {
        data object Idle : GattPhase
        data object Connecting : GattPhase
        data object DiscoveringServices : GattPhase
        data object ReadingServerIdentity : GattPhase
        data object Authenticating : GattPhase
        data object WaitingForServer : GattPhase
        data object ReceivingCredentials : GattPhase
        data object Ready : GattPhase
        data class Failed(val message: String) : GattPhase
    }

    fun connect(device: BluetoothDevice, expectedServerFingerprint: String? = null) {
        if (!hasBluetoothPermissions()) {
            _gattError.value = "Missing Bluetooth permissions"
            _phase.value = GattPhase.Failed("Missing Bluetooth permissions")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        stopApprovalPolling()
        gatt?.close()
        gatt = null

        _connectionState.value = ConnectionState.CONNECTING
        _phase.value = GattPhase.Connecting
        _gattError.value = null
        _receivedCredentials.value = null
        _approvalStatus.value = null
        pendingDeviceIdRead = false
        pendingClientIdWrite = false
        pendingHotspotCccdWrite = false
        pendingServerStatusCccdWrite = false
        pendingHotspotRead = false
        pendingApprovalRead = false
        serviceDiscoveryStarted = false
        _serverDeviceId.value = null
        _pairingCode.value = null
        _serverStatus.value = null
        serverHello = null
        sessionKey = null
        this.expectedServerFingerprint = expectedServerFingerprint
        controlCounter = 0L
        negotiatedMtu = 23
        authFrames = emptyList()
        authFrameIndex = 0
        pendingAuthBytes?.fill(0)
        pendingAuthBytes = null

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
        _phase.value = GattPhase.Idle
        _receivedCredentials.value = null
        pendingDeviceIdRead = false
        pendingClientIdWrite = false
        pendingHotspotCccdWrite = false
        pendingServerStatusCccdWrite = false
        pendingHotspotRead = false
        pendingApprovalRead = false
        serviceDiscoveryStarted = false
        serverHello = null
        sessionKey = null
        expectedServerFingerprint = null
        controlCounter = 0L
        negotiatedMtu = 23
        authFrames = emptyList()
        authFrameIndex = 0
        pendingAuthBytes?.fill(0)
        pendingAuthBytes = null
        _pairingCode.value = null
        _serverStatus.value = null
    }

    fun requestLowPowerConnection(): Boolean =
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) == true

    fun sendHeartbeat(): Boolean = sendSessionControl(BleConstants.CONTROL_HEARTBEAT)

    fun sendGoodbye(): Boolean = sendSessionControl(BleConstants.CONTROL_GOODBYE)

    @Synchronized
    fun distressPayload(): ByteArray? = sessionKey?.let {
        controlCounter++
        BleDiscoveryProtocol.encodeDistress(it, controlCounter.toInt())
    }

    fun serverPublicKeyEncoded(): ByteArray? = serverHello?.publicKey?.encoded?.copyOf()

    @Synchronized
    private fun sendSessionControl(type: Byte): Boolean {
        val currentGatt = gatt ?: return false
        val key = sessionKey ?: return false
        val hello = serverHello ?: return false
        val characteristic = currentGatt.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_SESSION_CONTROL) ?: return false
        controlCounter++
        val plaintext = ByteBuffer.allocate(9)
            .put(type)
            .putLong(controlCounter)
            .array()
        val envelope = BleSessionCrypto.encrypt(key, plaintext, hello.nonce)
        plaintext.fill(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                characteristic,
                envelope,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = envelope
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
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
                _phase.value = GattPhase.Failed("BLE connection timed out")
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
                _phase.value = GattPhase.Failed("Service discovery timed out")
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
        clientAuthFallbackJob?.cancel()
        clientAuthFallbackJob = null
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt) {
        if (serviceDiscoveryStarted) return
        serviceDiscoveryStarted = true
        if (!gatt.discoverServices()) {
            _gattError.value = "Could not start BLE service discovery"
            _phase.value = GattPhase.Failed("Could not start BLE service discovery")
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

    private fun startApprovalPolling() {
        if (approvalPollJob?.isActive == true) return
        approvalPollJob = scope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED &&
                _receivedCredentials.value == null
            ) {
                delay(500L)
                when (_approvalStatus.value) {
                    ApprovalStatus.APPROVED -> if (!pendingHotspotRead) {
                        pendingHotspotRead = true
                        readHotspotDataCharacteristic()
                    }
                    ApprovalStatus.DENIED -> return@launch
                    ApprovalStatus.ACTIVATION_FAILED -> return@launch
                    ApprovalStatus.HOTSPOT_STARTING -> if (!pendingApprovalRead) {
                        readApprovalCharacteristic()
                    }
                    null -> if (!pendingApprovalRead) readApprovalCharacteristic()
                }
            }
        }
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
        _phase.value = GattPhase.ReadingServerIdentity
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
        _phase.value = GattPhase.Authenticating
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

        pendingAuthBytes = clientIdBytes

        val currentGatt = gatt ?: return
        if (currentGatt.requestMtu(TARGET_MTU)) {
            mtuFallbackJob?.cancel()
            mtuFallbackJob = scope.launch {
                delay(700L)
                Log.w(TAG, "MTU negotiation callback delayed; using safe MTU $negotiatedMtu")
                beginFramedAuthWrite()
            }
        } else {
            beginFramedAuthWrite()
        }
    }

    private fun beginFramedAuthWrite() {
        if (authFrames.isNotEmpty()) return
        val auth = pendingAuthBytes ?: return
        authFrames = BleAuthFraming.encode(auth, negotiatedMtu)
        auth.fill(0)
        pendingAuthBytes = null
        authFrameIndex = 0
        sendCurrentAuthFrame()
    }

    private fun sendCurrentAuthFrame() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_CLIENT_ID)
        if (characteristic == null) {
            failAuthentication("Client ID characteristic missing on server")
            return
        }
        val frame = authFrames.getOrNull(authFrameIndex) ?: run {
            failAuthentication("Authentication frame sequence is unavailable")
            return
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic) == true
        }

        if (!started) {
            failAuthentication("Failed to write authentication frame ${authFrameIndex + 1}/${authFrames.size}")
        } else {
            pendingClientIdWrite = true
            Log.d(TAG, "Writing auth frame ${authFrameIndex + 1}/${authFrames.size} at MTU $negotiatedMtu")
            clientAuthFallbackJob?.cancel()
            clientAuthFallbackJob = scope.launch {
                delay(2_000L)
                if (pendingClientIdWrite && _connectionState.value == ConnectionState.CONNECTED) {
                    pendingClientIdWrite = false
                    failAuthentication("Authentication write callback timed out")
                }
            }
        }
    }

    private fun failAuthentication(message: String) {
        _gattError.value = message
        _phase.value = GattPhase.Failed(message)
        _connectionState.value = ConnectionState.ERROR
    }

    private fun readApprovalCharacteristic() {
        _phase.value = GattPhase.WaitingForServer
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_APPROVAL_STATUS) ?: return
        val started = gatt?.readCharacteristic(characteristic) == true
        if (!started) {
            _gattError.value = "Failed to read approval status"
        } else {
            pendingApprovalRead = true
            Log.d(TAG, "Reading approval status characteristic")
        }
    }

    private fun readHotspotDataCharacteristic() {
        _phase.value = GattPhase.ReceivingCredentials
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

        val approvalNotifications = gatt!!.setCharacteristicNotification(approvalChar, true)
        val hotspotNotifications = gatt!!.setCharacteristicNotification(hotspotChar, true)
        if (!approvalNotifications || !hotspotNotifications) {
            Log.w(TAG, "Indications are unavailable; using characteristic-read polling")
        }

        val approvalCccd = approvalChar.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
        val hotspotCccd = hotspotChar.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (approvalCccd == null || hotspotCccd == null) {
            Log.w(TAG, "CCCD missing; continuing with characteristic-read polling")
            pendingDeviceIdRead = false
            readDeviceIdCharacteristic()
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
            Log.w(TAG, "Approval CCCD write was not accepted; continuing with read polling")
            pendingDeviceIdRead = false
            readDeviceIdCharacteristic()
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

    private fun enableServerStatusChannel() {
        if (pendingServerStatusCccdWrite) return
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_SERVER_STATUS) ?: run {
            _gattError.value = "Server status characteristic missing"
            return
        }
        if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
            readServerStatusCharacteristic()
            return
        }
        val descriptor = characteristic.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
            ?: run {
                readServerStatusCharacteristic()
                return
            }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION")
            currentGatt.writeDescriptor(descriptor)
        }
        if (accepted) pendingServerStatusCccdWrite = true else readServerStatusCharacteristic()
    }

    private fun readServerStatusCharacteristic() {
        val characteristic = gatt?.getService(BleConstants.SERVICE_UUID)
            ?.getCharacteristic(BleConstants.CHAR_SERVER_STATUS) ?: return
        gatt?.readCharacteristic(characteristic)
    }

    private fun decodeServerStatus(envelope: ByteArray): ServerStatusMessage? {
        val key = sessionKey ?: return null
        val hello = serverHello ?: return null
        val plaintext = runCatching {
            BleSessionCrypto.decrypt(key, envelope, hello.nonce)
        }.getOrElse {
            _gattError.value = "Server status authentication failed"
            return null
        }
        return try {
            ServerStatusMessage.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private inner class GattCallbackImpl : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (this@GattClient.gatt !== gatt) {
                gatt.close()
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopConnectionTimeouts()
                _gattError.value = "GATT connection failed (status=$status)"
                _phase.value = GattPhase.Failed("GATT connection failed (status=$status)")
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
                    _phase.value = GattPhase.DiscoveringServices
                    startServiceDiscoveryTimeout()
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Android performs long characteristic reads transparently. Discovering now
                    // removes the former two-second MTU gate from pairing and credential refresh.
                    beginServiceDiscovery(gatt)
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
            if (this@GattClient.gatt !== gatt) return
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu.coerceAtLeast(23)
                Log.d(TAG, "Negotiated ATT MTU $negotiatedMtu (requested $TARGET_MTU)")
            } else {
                Log.w(TAG, "MTU negotiation failed with status=$status; using safe MTU $negotiatedMtu")
            }
            beginFramedAuthWrite()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (this@GattClient.gatt !== gatt) return
            mtuFallbackJob?.cancel()
            mtuFallbackJob = null
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Authentication must never depend on CCCD writes. Several OEM stacks accept a
                // descriptor write and never deliver its callback, which previously prevented the
                // client identity from reaching the server at all. Approval and credential
                // characteristics are readable, so polling is the portable primary path.
                pendingDeviceIdRead = false
                readDeviceIdCharacteristic()
            } else {
                _gattError.value = "Service discovery failed"
                _phase.value = GattPhase.Failed("Service discovery failed")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        @Deprecated("Android 12 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (this@GattClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.uuid == BleConstants.CHAR_APPROVAL_STATUS) {
                    pendingApprovalRead = false
                }
                if (characteristic.uuid == BleConstants.CHAR_HOTSPOT_DATA) {
                    pendingHotspotRead = false
                }
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
                    val actualFingerprint = BleSessionCrypto.fingerprint(hello.publicKey)
                    val expectedFingerprint = expectedServerFingerprint
                    if (expectedFingerprint != null &&
                        !actualFingerprint.equals(expectedFingerprint, ignoreCase = true)
                    ) {
                        _gattError.value = "Nearby server identity did not match the paired device"
                        _connectionState.value = ConnectionState.ERROR
                        gatt.disconnect()
                        return
                    }
                    _serverDeviceId.value = actualFingerprint
                    sessionKey = BleSessionCrypto.sessionKey(
                        clientKeyPair.private,
                        hello.publicKey,
                        hello.nonce
                    )
                    _pairingCode.value = BleSessionCrypto.pairingCode(sessionKey!!, hello.nonce)
                    writeClientIdCharacteristic()
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    pendingApprovalRead = false
                    val statusValue = value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Approval status read: 0x${String.format("%02X", statusValue)}")
                    when (statusValue) {
                        BleConstants.APPROVAL_GRANTED.toInt() -> {
                            Log.d(TAG, "Already approved, reading hotspot data")
                            _approvalStatus.value = ApprovalStatus.APPROVED
                            pendingHotspotRead = true
                            readHotspotDataCharacteristic()
                        }
                        BleConstants.APPROVAL_PENDING.toInt() -> {
                            Log.d(TAG, "Pending approval; indication plus read polling active")
                            _phase.value = GattPhase.WaitingForServer
                            startApprovalPolling()
                        }
                        BleConstants.APPROVAL_DENIED.toInt() -> {
                            _approvalStatus.value = ApprovalStatus.DENIED
                            pendingHotspotRead = false
                        }
                        BleConstants.HOTSPOT_STARTING.toInt() -> {
                            _approvalStatus.value = ApprovalStatus.HOTSPOT_STARTING
                            _phase.value = GattPhase.WaitingForServer
                            startApprovalPolling()
                        }
                        BleConstants.ACTIVATION_FAILED.toInt() -> {
                            _approvalStatus.value = ApprovalStatus.ACTIVATION_FAILED
                            stopApprovalPolling()
                        }
                        else -> {
                            Log.w(TAG, "Unknown approval status: $statusValue")
                        }
                    }
                }
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    pendingHotspotRead = false
                    if (value.isNotEmpty()) {
                        val credentials = decryptHotspotData(value)
                        if (credentials != null) {
                            Log.d(TAG, "Received hotspot payload via read for SSID=${credentials.ssid}")
                            stopApprovalPolling()
                            _phase.value = GattPhase.Ready
                            _receivedCredentials.value = credentials
                            pendingHotspotRead = false
                            enableServerStatusChannel()
                        }
                    } else {
                        Log.w(TAG, "Hotspot data read returned empty - credentials not ready yet")
                    }
                }
                BleConstants.CHAR_SERVER_STATUS -> {
                    decodeServerStatus(value)?.let { _serverStatus.value = it }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (this@GattClient.gatt !== gatt) return
            handleCharacteristicChanged(characteristic, value)
        }

        @Deprecated("Android 12 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (this@GattClient.gatt !== gatt) return
            handleCharacteristicChanged(characteristic, characteristic.value ?: byteArrayOf())
        }

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged: uuid=${characteristic.uuid}, value size=${value.size}")

            when (characteristic.uuid) {
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    Log.d(TAG, "Received hotspot notification, data size=${value.size}")
                    pendingHotspotRead = false
                    val credentials = decryptHotspotData(value)
                    if (credentials != null) {
                        Log.d(TAG, "Decoded hotspot credentials: SSID=${credentials.ssid}")
                        stopApprovalPolling()
                        _phase.value = GattPhase.Ready
                        _receivedCredentials.value = credentials
                        enableServerStatusChannel()
                    } else {
                        Log.w(TAG, "Failed to decode hotspot data")
                    }
                }
                BleConstants.CHAR_SERVER_STATUS -> {
                    decodeServerStatus(value)?.let { _serverStatus.value = it }
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val status = value.firstOrNull()?.toInt() ?: -1
                    Log.d(TAG, "Received approval notification: 0x${String.format("%02X", status)}")
                    when (status) {
                        BleConstants.APPROVAL_GRANTED.toInt() -> {
                            Log.d(TAG, "Approval granted by server")
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
                        BleConstants.HOTSPOT_STARTING.toInt() -> {
                            _approvalStatus.value = ApprovalStatus.HOTSPOT_STARTING
                            _phase.value = GattPhase.WaitingForServer
                        }
                        BleConstants.ACTIVATION_FAILED.toInt() -> {
                            stopApprovalPolling()
                            _approvalStatus.value = ApprovalStatus.ACTIVATION_FAILED
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
            if (this@GattClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed for ${descriptor.characteristic.uuid}; using read polling")
                pendingHotspotCccdWrite = false
                if (pendingDeviceIdRead) {
                    pendingDeviceIdRead = false
                    readDeviceIdCharacteristic()
                }
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
            if (descriptor.characteristic.uuid == BleConstants.CHAR_SERVER_STATUS) {
                pendingServerStatusCccdWrite = false
                readServerStatusCharacteristic()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (this@GattClient.gatt !== gatt) return
            if (characteristic.uuid != BleConstants.CHAR_CLIENT_ID || !pendingClientIdWrite) {
                return
            }
            pendingClientIdWrite = false
            clientAuthFallbackJob?.cancel()
            clientAuthFallbackJob = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (authFrameIndex + 1 < authFrames.size) {
                    authFrameIndex++
                    sendCurrentAuthFrame()
                } else {
                    Log.d(TAG, "Authenticated client identity transfer complete")
                    authFrames = emptyList()
                    authFrameIndex = 0
                    readApprovalCharacteristic()
                    startApprovalPolling()
                }
            } else {
                failAuthentication(
                    "Authentication frame ${authFrameIndex + 1}/${authFrames.size} failed (status=$status)"
                )
            }
        }
    }
}
