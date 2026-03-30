package com.agentkosticka.easierspot.ble

import java.util.UUID

object BleConstants {
    // Main service UUID (replace with your own 128-bit UUID)
    val SERVICE_UUID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfb00")

    // Characteristics
    
    // Device ID (4 bytes) - READ only
    // Server publishes a 4-byte unique device ID
    val CHAR_DEVICE_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfb01")

    // Hotspot Data - NOTIFY
    // Server notifies with SSID + password credentials
    // Format: [1 byte SSID length][SSID][1 byte password length][password]
    val CHAR_HOTSPOT_DATA: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfb02")

    // Approval Status - INDICATE
    // Server indicates approval status: 0x00 = denied, 0x01 = approved
    val CHAR_APPROVAL_STATUS: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfb03")

    // Client ID - WRITE
    // Client writes its stable app/device identifier so server can remember approvals reliably
    val CHAR_CLIENT_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfb04")

    // Constants
    const val GATT_CONNECTION_TIMEOUT_MS = 10000L
    const val GATT_OPERATION_TIMEOUT_MS = 5000L
    const val SCAN_TIMEOUT_MS = 15000L
}
