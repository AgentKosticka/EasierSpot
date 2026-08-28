package com.agentkosticka.easierspot.ble

import java.util.UUID

object BleConstants {
    const val PROTOCOL_VERSION: Byte = 0x02

    // Protocol v2 intentionally uses a new service UUID. V1 exposed plaintext credentials and
    // trusted a caller-provided identifier, so it is not advertised or accepted anymore.
    val SERVICE_UUID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfc00")

    // Characteristics
    
    // Server handshake (nonce + authenticated P-256 public key) - READ only
    val CHAR_DEVICE_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfc01")

    // AES-GCM encrypted hotspot data - INDICATE + READ fallback
    val CHAR_HOTSPOT_DATA: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfc02")

    // Approval Status - INDICATE
    // Server indicates approval status: 0x00 pending, 0x01 approved, 0x02 denied
    val CHAR_APPROVAL_STATUS: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfc03")

    // Signed client authentication message - WRITE
    val CHAR_CLIENT_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfc04")

    // Client Characteristic Configuration Descriptor (CCCD)
    // Used by clients to enable notifications/indications
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val APPROVAL_PENDING: Byte = 0x00
    const val APPROVAL_GRANTED: Byte = 0x01
    const val APPROVAL_DENIED: Byte = 0x02

    const val STARTUP_BURST_MS = 20_000L
    const val SCAN_FAST_PHASE_MS = 5_000L
    const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L
}
