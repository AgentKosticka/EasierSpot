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
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
    private val clientStableIds = mutableMapOf<String, String>()
    private val approvalNotificationEnabledClients = mutableSetOf<String>()
    private val hotspotNotificationEnabledClients = mutableSetOf<String>()
    private var hotspotCredentials: HotspotCredentials? = null
    private var newClientCallback: ((String, String?) -> Unit)? = null

    fun setNewClientCallback(callback: (clientAddress: String, clientStableId: String?) -> Unit) {
        newClientCallback = callback
    }

    fun startServer() {
        if (!hasBluetoothPermissions()) {
            _gattServerError.value = "Missing Bluetooth permissions"
            return
        }

        if (_isRunning.value) {
            return
        }

        val gattService = createGattService()
        gattServer = bluetoothManager.openGattServer(context, GattServerCallbackImpl())
        gattServer?.addService(gattService)
        _isRunning.value = true
        _gattServerError.value = null
    }

    fun stopServer() {
        gattServer?.close()
        gattServer = null
        _isRunning.value = false
        _connectedClients.value = emptyList()
        clientStableIds.clear()
    }

    fun approveClient(clientAddress: String) {
        approvedClients.add(clientAddress)
        _pendingApproval.value = null
        LogUtils.i(TAG, "Client approved: $clientAddress")

        // Notify client of approval via CHAR_APPROVAL_STATUS
        notifyClient(clientAddress, BleConstants.CHAR_APPROVAL_STATUS, byteArrayOf(0x01))
    }

    fun denyClient(clientAddress: String) {
        LogUtils.i(TAG, "Client denied: $clientAddress")
        // Notify client of denial
        notifyClient(clientAddress, BleConstants.CHAR_APPROVAL_STATUS, byteArrayOf(0x00))

        // Remove from pending
        _pendingApproval.value = null
    }

    fun sendHotspotCredentials(clientAddress: String, credentials: HotspotCredentials) {
        this.hotspotCredentials = credentials
        val payload = encodeHotspotData(credentials)
        LogUtils.i(TAG, "Sending hotspot credentials to $clientAddress (ssid=${credentials.ssid})")
        notifyClient(clientAddress, BleConstants.CHAR_HOTSPOT_DATA, payload)
    }

    fun disconnectClient(clientAddress: String) {
        val updatedList = _connectedClients.value.filter { it.address != clientAddress }
        _connectedClients.value = updatedList
    }

    private fun notifyClient(clientAddress: String, characteristicUuid: java.util.UUID, payload: ByteArray) {
        val device = bluetoothAdapter?.getRemoteDevice(clientAddress) ?: return
        
        // Must get the characteristic from the registered service, not create a new one
        val service = gattServer?.getService(BleConstants.SERVICE_UUID)
        if (service == null) {
            LogUtils.e(TAG, "Service not found for notification")
            return
        }
        
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            LogUtils.e(TAG, "Characteristic $characteristicUuid not found")
            return
        }
        
        if (characteristicUuid == BleConstants.CHAR_APPROVAL_STATUS && clientAddress !in approvalNotificationEnabledClients) {
            LogUtils.d(TAG, "Skipping approval notification for $clientAddress until CCCD is enabled")
            return
        }
        if (characteristicUuid == BleConstants.CHAR_HOTSPOT_DATA && clientAddress !in hotspotNotificationEnabledClients) {
            LogUtils.d(TAG, "Skipping hotspot notification for $clientAddress until CCCD is enabled")
            return
        }

        characteristic.value = payload
        val notified = gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
        LogUtils.d(TAG, "Notify $characteristicUuid to $clientAddress success=$notified")
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
        deviceIdChar.value = deviceId.toByteArray(StandardCharsets.UTF_8).take(4).toByteArray()
        service.addCharacteristic(deviceIdChar)

        // CHAR_HOTSPOT_DATA - NOTIFY + READ (read fallback)
        val hotspotDataChar = BluetoothGattCharacteristic(
            BleConstants.CHAR_HOTSPOT_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
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
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
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

        return service
    }

    private fun encodeHotspotData(credentials: HotspotCredentials): ByteArray {
        val ssidBytes = credentials.ssid.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = credentials.password.toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(2 + ssidBytes.size + passwordBytes.size)
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
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LogUtils.i(TAG, "Client connected: ${device.address}")
                    val client = ClientConnection(device.address)
                    _connectedClients.value += client

                    // Wait for client stable ID write before evaluating approval.
                    if (device.address !in approvedClients) {
                        _pendingApproval.value = client
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    LogUtils.i(TAG, "Client disconnected: ${device.address}")
                    approvedClients.remove(device.address)
                    clientStableIds.remove(device.address)
                    approvalNotificationEnabledClients.remove(device.address)
                    hotspotNotificationEnabledClients.remove(device.address)
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
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        characteristic.value
                    )
                }
                BleConstants.CHAR_APPROVAL_STATUS -> {
                    val isApproved = device.address in approvedClients
                    val approvalValue = if (isApproved) byteArrayOf(0x01) else byteArrayOf(0x00)
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
                        LogUtils.d(TAG, "Responding with hotspot credentials: ssid=${credentials.ssid}")
                        encodeHotspotData(credentials)
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
                else -> {
                    LogUtils.w(TAG, "Unknown characteristic read request: ${characteristic.uuid}")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                }
            }
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
                val stableId = String(value, StandardCharsets.UTF_8).trim()
                if (stableId.isNotEmpty()) {
                    clientStableIds[device.address] = stableId
                    LogUtils.i(TAG, "Received client stable ID for ${device.address}: $stableId")
                    newClientCallback?.invoke(device.address, stableId)
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
                            notifyClient(device.address, BleConstants.CHAR_APPROVAL_STATUS, byteArrayOf(0x01))
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
            }
        }
    }
}
