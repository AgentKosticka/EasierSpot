package com.agentkosticka.easierspot.ble

import java.util.UUID

object BleConstants {
    const val PROTOCOL_VERSION: Byte = 0x03

    // Protocol v3 is deliberately a clean break. Old builds do not advertise, discover, or
    // authenticate against this service, which keeps the fast path free of compatibility forks.
    val SERVICE_UUID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd00")

    // Characteristics
    
    // Server handshake (nonce + authenticated P-256 public key) - READ only
    val CHAR_DEVICE_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd01")

    // AES-GCM encrypted hotspot data - INDICATE + READ fallback
    val CHAR_HOTSPOT_DATA: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd02")

    // Approval Status - INDICATE
    // Server indicates approval status: 0x00 pending, 0x01 approved, 0x02 denied
    val CHAR_APPROVAL_STATUS: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd03")

    // Signed client authentication message - WRITE
    val CHAR_CLIENT_ID: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd04")

    // Authenticated liveness/control messages - WRITE/WRITE_NO_RESPONSE
    val CHAR_SESSION_CONTROL: UUID = UUID.fromString("2df83cb5-ddb1-425a-b36f-535638fbfd05")

    // Client Characteristic Configuration Descriptor (CCCD)
    // Used by clients to enable notifications/indications
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val APPROVAL_PENDING: Byte = 0x00
    const val APPROVAL_GRANTED: Byte = 0x01
    const val APPROVAL_DENIED: Byte = 0x02
    const val HOTSPOT_STARTING: Byte = 0x03
    const val ACTIVATION_FAILED: Byte = 0x04

    const val STARTUP_BURST_MS = 20_000L
    const val SCAN_FAST_PHASE_MS = 5_000L
    const val DEFAULT_SCAN_TIMEOUT_MS = 30_000L

    const val MESSAGE_SERVER: Byte = 0x01
    const val MESSAGE_DISTRESS: Byte = 0x02
    const val MESSAGE_WAKE_REQUEST: Byte = 0x03
    const val FLAG_AUTOMATIC_ACTIVATION: Int = 0x10
    const val FLAG_HOTSPOT_ACTIVE: Int = 0x20
    const val FLAG_HOTSPOT_STARTING: Int = 0x40

    const val UDP_CONTROL_VERSION: Byte = 0x01
    const val UDP_CONTROL_PORT = 47_645
    const val UDP_HELLO: Byte = 0x01
    const val UDP_ACK: Byte = 0x02
    const val UDP_HEARTBEAT: Byte = 0x03
    const val UDP_GOODBYE: Byte = 0x04
    const val UDP_HEARTBEAT_INTERVAL_MS = 60_000L
    const val UDP_CLIENT_EXPIRY_MS = UDP_HEARTBEAT_INTERVAL_MS * 3
    const val HOTSPOT_WAKE_LEASE_MS = 30_000L

    const val CONTROL_HEARTBEAT: Byte = 0x01
    const val CONTROL_GOODBYE: Byte = 0x02
    const val HEARTBEAT_INTERVAL_MS = 25_000L
    const val MAX_MISSED_HEARTBEATS = 3
    const val DISTRESS_BURST_MS = 5_000L
    const val WAKE_REQUEST_BURST_MS = 8_000L
    const val FAST_GATT_RESCUE_MS = 1_200L
    const val WAKE_PROBE_INTERVAL_MS = 2 * 60_000L
    const val WAKE_BOOST_MS = 15_000L
}
