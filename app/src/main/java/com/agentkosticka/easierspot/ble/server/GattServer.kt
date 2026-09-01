package com.agentkosticka.easierspot.ble.server

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleAuthFraming
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import com.agentkosticka.easierspot.ble.ServerStatusMessage
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class ClientConnection(
    val address: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isApproved: Boolean = false
)

@SuppressLint("MissingPermission")
class GattServer(private val context: Context, private val deviceId: String) {
    companion object {
        private const val TAG = "GattServer"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null

    private val _connectedClients = MutableStateFlow<List<ClientConnection>>(emptyList())

    private val _pendingApproval = MutableStateFlow<ClientConnection?>(null)

    private val _gattServerError = MutableStateFlow<String?>(null)

    private val _isRunning = MutableStateFlow(false)

    private val approvedClients = mutableSetOf<String>()
    private val clientStatus = ConcurrentHashMap<String, Byte>()
    private val clientStableIds = ConcurrentHashMap<String, String>()
    private val approvalNotificationEnabledClients = mutableSetOf<String>()
    private val hotspotNotificationEnabledClients = mutableSetOf<String>()
    private val serverStatusNotificationEnabledClients = mutableSetOf<String>()
    private var hotspotCredentials: HotspotCredentials? = null
    private var serverStatus = ServerStatusMessage(ServerStatusMessage.Type.AVAILABLE, 0)
    private var newClientCallback: ((String, String?) -> Unit)? = null
    private var clientConnectionStateCallback: ((String, Boolean, String?) -> Unit)? = null
    private var sessionControlCallback: ((String, Byte) -> Unit)? = null
    private var readyCallback: ((Result<Unit>) -> Unit)? = null
    private val serverKeyPair by lazy { BleSessionCrypto.serverKeyPair(context) }
    private var serverHello: BleSessionCrypto.ServerHello? = null
    private val sessionKeys = ConcurrentHashMap<String, SecretKeySpec>()
    private val pairingCodes = mutableMapOf<String, String>()
    private val lastControlCounters = ConcurrentHashMap<String, Long>()
    private val clientPublicKeys = ConcurrentHashMap<String, ByteArray>()
    private val authAssemblies = ConcurrentHashMap<String, BleAuthFraming.Assembler>()
    private data class PendingIndication(
        val address: String,
        val characteristicUuid: java.util.UUID,
        val payload: ByteArray
    )
    private val indicationQueue = ArrayDeque<PendingIndication>()
    private var indicationInFlight = false
    private var activeIndication: PendingIndication? = null

    fun setNewClientCallback(callback: (clientAddress: String, clientStableId: String?) -> Unit) {
        newClientCallback = callback
    }

    fun setClientConnectionStateCallback(callback: (clientAddress: String, connected: Boolean, clientStableId: String?) -> Unit) {
        clientConnectionStateCallback = callback
    }

    fun setSessionControlCallback(callback: (clientStableId: String, signal: Byte) -> Unit) {
        sessionControlCallback = callback
    }

    fun stableIdForAddress(clientAddress: String): String? = clientStableIds[clientAddress]
    fun stableAddress(stableId: String): String? =
        clientStableIds.entries.firstOrNull { it.value == stableId }?.key
    fun clientPublicKeyForAddress(clientAddress: String): ByteArray? =
        clientPublicKeys[clientAddress]?.copyOf()

    /** Returns the authenticated client fingerprint for a fresh distress advertisement. */
    @Synchronized
    fun verifyDistress(payload: ByteArray): String? {
        val counter = runCatching { BleDiscoveryProtocol.distressCounter(payload) }.getOrNull() ?: return null
        return sessionKeys.entries.firstNotNullOfOrNull { (address, key) ->
            val previous = lastControlCounters[address] ?: 0L
            clientStableIds[address]?.takeIf {
                counter > previous && BleDiscoveryProtocol.verifyDistress(payload, key)
            }?.also { lastControlCounters[address] = counter }
        }
    }

    fun getPairingCode(clientAddress: String): String? = pairingCodes[clientAddress]

    @Synchronized
    private fun consumeAuthFrame(address: String, value: ByteArray): ByteArray? {
        val frame = BleAuthFraming.parse(value)
        val assembler = if (frame.index == 0) {
            BleAuthFraming.Assembler(frame).also { authAssemblies[address] = it }
        } else {
            authAssemblies[address] ?: error("Authentication continuation has no first frame")
        }
        val complete = if (frame.index == 0) assembler.resultIfComplete() else assembler.accept(frame)
        if (complete != null) authAssemblies.remove(address)
        return complete
    }

    private fun authenticateClient(address: String, value: ByteArray) {
        val hello = serverHello ?: error("Server handshake is unavailable")
        val auth = BleSessionCrypto.parseAndVerifyClientAuth(value, hello)
        val stableId = BleSessionCrypto.fingerprint(auth.publicKey)
        val key = BleSessionCrypto.sessionKey(serverKeyPair.private, auth.publicKey, hello.nonce)
        clientStableIds[address] = stableId
        sessionKeys[address] = key
        clientPublicKeys[address] = auth.publicKey.encoded.copyOf()
        lastControlCounters[address] = 0L
        pairingCodes[address] = BleSessionCrypto.pairingCode(key, hello.nonce)
        LogUtils.i(TAG, "Authenticated v3 client fingerprint=$stableId")
        newClientCallback?.invoke(address, stableId)
    }

    fun startServer(onReady: (Result<Unit>) -> Unit = {}) {
        if (!hasBluetoothPermissions()) {
            _gattServerError.value = "Missing Bluetooth permissions"
            onReady(Result.failure(SecurityException("Missing Bluetooth permissions")))
            return
        }

        if (_isRunning.value) {
            onReady(Result.success(Unit))
            return
        }

        serverHello = BleSessionCrypto.createServerHello(serverKeyPair)
        val gattService = createGattService()
        readyCallback = onReady
        gattServer = bluetoothManager.openGattServer(context, GattServerCallbackImpl())
        if (gattServer == null) {
            val error = IllegalStateException("Unable to open GATT server")
            _gattServerError.value = error.message
            readyCallback?.invoke(Result.failure(error))
            readyCallback = null
            return
        }
        if (gattServer?.addService(gattService) != true) {
            val error = IllegalStateException("Unable to register GATT service")
            _gattServerError.value = error.message
            gattServer?.close()
            gattServer = null
            readyCallback?.invoke(Result.failure(error))
            readyCallback = null
            return
        }
        _gattServerError.value = null
    }

    fun stopServer() {
        gattServer?.close()
        gattServer = null
        _isRunning.value = false
        _connectedClients.value = emptyList()
        clientStableIds.clear()
        approvedClients.clear()
        clientStatus.clear()
        approvalNotificationEnabledClients.clear()
        hotspotNotificationEnabledClients.clear()
        serverStatusNotificationEnabledClients.clear()
        hotspotCredentials = null
        serverStatus = ServerStatusMessage(ServerStatusMessage.Type.AVAILABLE, 0)
        sessionKeys.clear()
        pairingCodes.clear()
        lastControlCounters.clear()
        clientPublicKeys.clear()
        authAssemblies.clear()
        serverHello = null
        indicationQueue.clear()
        indicationInFlight = false
        activeIndication = null
        readyCallback = null
    }

    fun approveClient(clientAddress: String) {
        approvedClients.add(clientAddress)
        clientStatus[clientAddress] = BleConstants.APPROVAL_GRANTED
        _pendingApproval.value = null
        LogUtils.i(TAG, "Client approved: $clientAddress")

        // Notify client of approval via CHAR_APPROVAL_STATUS
        notifyClient(clientAddress, BleConstants.CHAR_APPROVAL_STATUS, byteArrayOf(BleConstants.APPROVAL_GRANTED))
    }

    fun denyClient(clientAddress: String) {
        clientStatus[clientAddress] = BleConstants.APPROVAL_DENIED
        LogUtils.i(TAG, "Client denied: $clientAddress")
        // Notify client of denial
        notifyClient(clientAddress, BleConstants.CHAR_APPROVAL_STATUS, byteArrayOf(BleConstants.APPROVAL_DENIED))

        // Remove from pending
        _pendingApproval.value = null
    }

    fun sendHotspotCredentials(clientAddress: String, credentials: HotspotCredentials) {
        this.hotspotCredentials = credentials
        val key = sessionKeys[clientAddress]
        val hello = serverHello
        if (key == null || hello == null) {
            LogUtils.e(TAG, "Refusing credential transfer without an authenticated session")
            denyClient(clientAddress)
            return
        }
        val plaintext = encodeHotspotData(credentials)
        val payload = BleSessionCrypto.encrypt(key, plaintext, hello.nonce)
        plaintext.fill(0)
        LogUtils.i(TAG, "Sending encrypted hotspot credentials to authenticated client $clientAddress")
        notifyClient(clientAddress, BleConstants.CHAR_HOTSPOT_DATA, payload)
    }

    fun disconnectClient(clientAddress: String) {
        val updatedList = _connectedClients.value.filter { it.address != clientAddress }
        _connectedClients.value = updatedList
    }

    fun updateServerStatus(status: ServerStatusMessage) {
        serverStatus = status
        clientStableIds.keys.forEach { address -> sendServerStatus(address, status) }
    }

    fun sendServerStatus(clientAddress: String, status: ServerStatusMessage) {
        val payload = encryptedServerStatus(clientAddress, status) ?: return
        notifyClient(clientAddress, BleConstants.CHAR_SERVER_STATUS, payload)
    }

    fun disconnectAuthenticatedClient(stableId: String) {
        val address = clientStableIds.entries.firstOrNull { it.value == stableId }?.key ?: return
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return
        runCatching { gattServer?.cancelConnection(device) }
            .onFailure { LogUtils.w(TAG, "Could not disconnect authenticated client", it) }
    }

    private fun encryptedServerStatus(
        clientAddress: String,
        status: ServerStatusMessage
    ): ByteArray? {
        val key = sessionKeys[clientAddress] ?: return null
        val hello = serverHello ?: return null
        val plaintext = status.encode()
        return try {
            BleSessionCrypto.encrypt(key, plaintext, hello.nonce)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun notifyClient(clientAddress: String, characteristicUuid: java.util.UUID, payload: ByteArray) {
        if (characteristicUuid == BleConstants.CHAR_APPROVAL_STATUS && clientAddress !in approvalNotificationEnabledClients) {
            LogUtils.d(TAG, "Skipping approval notification for $clientAddress until CCCD is enabled")
            return
        }
        if (characteristicUuid == BleConstants.CHAR_HOTSPOT_DATA && clientAddress !in hotspotNotificationEnabledClients) {
            LogUtils.d(TAG, "Skipping hotspot notification for $clientAddress until CCCD is enabled")
            return
        }
        if (characteristicUuid == BleConstants.CHAR_SERVER_STATUS &&
            clientAddress !in serverStatusNotificationEnabledClients
        ) {
            LogUtils.d(TAG, "Skipping server status indication until CCCD is enabled")
            return
        }
        indicationQueue.addLast(PendingIndication(clientAddress, characteristicUuid, payload.copyOf()))
        drainIndicationQueue()
    }

    fun markHotspotStarting(clientAddress: String) {
        clientStatus[clientAddress] = BleConstants.HOTSPOT_STARTING
        notifyClient(
            clientAddress,
            BleConstants.CHAR_APPROVAL_STATUS,
            byteArrayOf(BleConstants.HOTSPOT_STARTING)
        )
    }

    fun markActivationFailed(clientAddress: String) {
        clientStatus[clientAddress] = BleConstants.ACTIVATION_FAILED
        notifyClient(
            clientAddress,
            BleConstants.CHAR_APPROVAL_STATUS,
            byteArrayOf(BleConstants.ACTIVATION_FAILED)
        )
    }

    private fun drainIndicationQueue() {
        if (indicationInFlight) return
        while (indicationQueue.isNotEmpty()) {
            val pending = indicationQueue.removeFirst()
            val device = runCatching { bluetoothAdapter?.getRemoteDevice(pending.address) }.getOrNull()
            val characteristic = gattServer?.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(pending.characteristicUuid)
            if (device == null || characteristic == null) {
                continue
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    true,
                    pending.payload
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = pending.payload
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, true) == true
            }
            if (started) {
                indicationInFlight = true
                activeIndication = pending
                return
            }
            LogUtils.w(TAG, "Unable to enqueue indication ${pending.characteristicUuid}")
        }
    }

    private fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // CHAR_DEVICE_ID - READ only
        val deviceIdChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_DEVICE_ID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(deviceIdChar)

        // CHAR_HOTSPOT_DATA - NOTIFY + READ (read fallback)
        val hotspotDataChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_HOTSPOT_DATA,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        hotspotDataChar.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(hotspotDataChar)

        // CHAR_APPROVAL_STATUS - NOTIFY + READ (read fallback)
        val approvalStatusChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_APPROVAL_STATUS,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        approvalStatusChar.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(approvalStatusChar)

        // CHAR_CLIENT_ID - WRITE
        val clientIdChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_CLIENT_ID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(clientIdChar)

        val sessionControlChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_SESSION_CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(sessionControlChar)

        val serverStatusChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_SERVER_STATUS,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        serverStatusChar.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(serverStatusChar)

        return service
    }

    private fun encodeHotspotData(credentials: HotspotCredentials): ByteArray {
        val ssidBytes = credentials.ssid.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = credentials.password.toByteArray(StandardCharsets.UTF_8)

        require(ssidBytes.size <= 255) { "SSID is too long" }
        require(passwordBytes.size <= 255) { "Passphrase is too long" }
        val buffer = ByteBuffer.allocate(5 + ssidBytes.size + passwordBytes.size)
        buffer.put(BleConstants.PROTOCOL_VERSION)
        buffer.put(credentials.securityType.ordinal.toByte())
        buffer.put(if (credentials.isHidden) 0x01 else 0x00)
        buffer.put(ssidBytes.size.toByte())
        buffer.put(ssidBytes)
        buffer.put(passwordBytes.size.toByte())
        buffer.put(passwordBytes)

        return buffer.array()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private inner class GattServerCallbackImpl : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            super.onServiceAdded(status, service)
            if (service.uuid != BleConstants.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _isRunning.value = true
                readyCallback?.invoke(Result.success(Unit))
            } else {
                _isRunning.value = false
                val error = IllegalStateException("GATT service registration failed ($status)")
                _gattServerError.value = error.message
                gattServer?.close()
                gattServer = null
                readyCallback?.invoke(Result.failure(error))
            }
            readyCallback = null
        }

        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LogUtils.i(TAG, "Client connected: ${device.address}")
                    val client = ClientConnection(device.address)
                    _connectedClients.value += client
                    clientConnectionStateCallback?.invoke(device.address, true, clientStableIds[device.address])

                    // Wait for client stable ID write before evaluating approval.
                    if (device.address !in approvedClients) {
                        _pendingApproval.value = client
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    LogUtils.i(TAG, "Client disconnected: ${device.address}")
                    clientConnectionStateCallback?.invoke(device.address, false, clientStableIds[device.address])
                    approvedClients.remove(device.address)
                    clientStatus.remove(device.address)
                    // Keep authenticated session material until the server transport stops so a
                    // short BLE distress burst can still be verified after the GATT link drops.
                    pairingCodes.remove(device.address)
                    authAssemblies.remove(device.address)
                    indicationQueue.removeAll { it.address == device.address }
                    if (activeIndication?.address == device.address) {
                        activeIndication = null
                        indicationInFlight = false
                        drainIndicationQueue()
                    }
                    approvalNotificationEnabledClients.remove(device.address)
                    hotspotNotificationEnabledClients.remove(device.address)
                    serverStatusNotificationEnabledClients.remove(device.address)
                    disconnectClient(device.address)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            LogUtils.d(TAG, "Read request: char=${characteristic.uuid}")

            when (characteristic.uuid) {
                BleConstants.CHAR_DEVICE_ID -> {
                    val fullPayload = serverHello?.encoded ?: byteArrayOf()
                    if (offset > fullPayload.size) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_OFFSET,
                            offset,
                            null
                        )
                        return
                    }
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        fullPayload.copyOfRange(offset, fullPayload.size)
                    )
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val approvalValue = byteArrayOf(
                        clientStatus[device.address] ?: BleConstants.APPROVAL_PENDING
                    )
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        approvalValue
                    )
                }
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    val credentials = hotspotCredentials
                    val isApproved = device.address in approvedClients
                    val fullPayload = if (isApproved && credentials != null) {
                        val key = sessionKeys[device.address]
                        val hello = serverHello
                        if (key != null && hello != null) {
                            val plaintext = encodeHotspotData(credentials)
                            BleSessionCrypto.encrypt(key, plaintext, hello.nonce).also {
                                plaintext.fill(0)
                            }
                        } else {
                            byteArrayOf()
                        }
                    } else {
                        LogUtils.d(TAG, "Responding with empty hotspot data (approved=$isApproved, credentials=${credentials != null})")
                        byteArrayOf()
                    }

                    if (offset > fullPayload.size) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_OFFSET,
                            offset,
                            null
                        )
                        return
                    }

                    val payload = if (offset == 0) {
                        fullPayload
                    } else {
                        fullPayload.copyOfRange(offset, fullPayload.size)
                    }

                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        payload
                    )
                }
                BleConstants.CHAR_SERVER_STATUS -> {
                    val fullPayload = encryptedServerStatus(device.address, serverStatus)
                        ?: byteArrayOf()
                    if (offset > fullPayload.size) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_OFFSET,
                            offset,
                            null
                        )
                        return
                    }
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        fullPayload.copyOfRange(offset, fullPayload.size)
                    )
                }
                else -> {
                    LogUtils.w(TAG, "Unknown characteristic read request: ${characteristic.uuid}")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                }
            }
        }

        override fun onNotificationSent(device: android.bluetooth.BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            val completed = activeIndication
            activeIndication = null
            indicationInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                LogUtils.w(TAG, "Indication failed for ${completed?.address} (status=$status)")
            }
            drainIndicationQueue()
        }

        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            if (characteristic.uuid == BleConstants.CHAR_CLIENT_ID && value != null && value.isNotEmpty()) {
                val assembled = runCatching {
                    require(!preparedWrite && offset == 0) { "Prepared authentication writes are unsupported" }
                    consumeAuthFrame(device.address, value)
                }
                if (assembled.isFailure) {
                    LogUtils.w(
                        TAG,
                        "Rejected invalid authentication frame",
                        assembled.exceptionOrNull() ?: IllegalArgumentException("Invalid frame")
                    )
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    }
                    return
                }
                val completeAuth = assembled.getOrNull()
                if (completeAuth != null) {
                    val authenticated = runCatching { authenticateClient(device.address, completeAuth) }
                    completeAuth.fill(0)
                    if (authenticated.isFailure) {
                        LogUtils.w(
                            TAG,
                            "Rejected unauthenticated client",
                            authenticated.exceptionOrNull() ?: IllegalArgumentException("Invalid client")
                        )
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_FAILURE,
                                offset,
                                null
                            )
                        }
                        return
                    }
                }
            }

            if (characteristic.uuid == BleConstants.CHAR_SESSION_CONTROL && value != null) {
                val accepted = runCatching {
                    val key = sessionKeys[device.address] ?: error("Unauthenticated session")
                    val hello = serverHello ?: error("Server handshake unavailable")
                    val plaintext = BleSessionCrypto.decrypt(key, value, hello.nonce)
                    try {
                        require(plaintext.size == 9) { "Invalid session control length" }
                        val buffer = ByteBuffer.wrap(plaintext)
                        val type = buffer.get()
                        require(type == BleConstants.CONTROL_HEARTBEAT || type == BleConstants.CONTROL_GOODBYE)
                        val counter = buffer.long
                        synchronized(this@GattServer) {
                            require(counter > (lastControlCounters[device.address] ?: 0L)) {
                                "Replayed session control message"
                            }
                            lastControlCounters[device.address] = counter
                        }
                        val stableId = clientStableIds[device.address] ?: error("Missing client identity")
                        sessionControlCallback?.invoke(stableId, type)
                    } finally {
                        plaintext.fill(0)
                    }
                }.isSuccess
                if (!accepted) {
                    LogUtils.w(TAG, "Rejected invalid session control from ${device.address}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                    return
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            if (descriptor.uuid != BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID || value == null) {
                return
            }

            val isEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)

            when (descriptor.characteristic.uuid) {
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    if (isEnabled) {
                        approvalNotificationEnabledClients.add(device.address)
                        LogUtils.d(TAG, "Approval CCCD enabled for ${device.address}")
                        if (device.address in approvedClients) {
                            notifyClient(
                                device.address,
                                BleConstants.CHAR_APPROVAL_STATUS,
                                byteArrayOf(BleConstants.APPROVAL_GRANTED)
                            )
                        }
                    } else {
                        approvalNotificationEnabledClients.remove(device.address)
                    }
                }
                BleConstants.CHAR_HOTSPOT_DATA -> {
                    if (isEnabled) {
                        hotspotNotificationEnabledClients.add(device.address)
                        LogUtils.d(TAG, "Hotspot CCCD enabled for ${device.address}")
                        val credentials = hotspotCredentials
                        if (device.address in approvedClients && credentials != null) {
                            sendHotspotCredentials(device.address, credentials)
                        }
                    } else {
                        hotspotNotificationEnabledClients.remove(device.address)
                    }
                }
                BleConstants.CHAR_SERVER_STATUS -> {
                    if (isEnabled) {
                        serverStatusNotificationEnabledClients.add(device.address)
                        sendServerStatus(device.address, serverStatus)
                    } else {
                        serverStatusNotificationEnabledClients.remove(device.address)
                    }
                }
            }
        }
    }
}
