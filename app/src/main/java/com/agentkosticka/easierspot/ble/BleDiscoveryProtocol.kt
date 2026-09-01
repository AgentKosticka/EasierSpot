package com.agentkosticka.easierspot.ble

import com.agentkosticka.easierspot.data.model.HotspotCredentials
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

/** Compact service-data protocol that fits in a legacy 31-byte BLE advertisement. */
object BleDiscoveryProtocol {
    const val PAYLOAD_SIZE = 10

    data class ServerBeacon(
        val token: String,
        val networkRevision: Int,
        val advertisingSession: Int,
        val flags: Int
    )

    fun serverToken(context: android.content.Context): String =
        BleSessionCrypto.fingerprint(BleSessionCrypto.serverKeyPair(context).public).take(8)

    fun networkRevision(credentials: HotspotCredentials?): Int {
        if (credentials == null) return 0
        val digest = MessageDigest.getInstance("SHA-256").digest(
            buildString {
                append(credentials.ssid)
                append('\u0000')
                append(credentials.securityType.name)
                append('\u0000')
                append(credentials.isHidden)
                append('\u0000')
                append(credentials.password)
            }.toByteArray(Charsets.UTF_8)
        )
        // Three bytes leave one byte in the fixed legacy-advertisement payload for a fresh
        // advertising-session id. A 24-bit credential revision remains ample collision space.
        return ByteBuffer.wrap(digest, 0, 4).int and 0x00ff_ffff
    }

    fun encodeServer(
        token: String,
        networkRevision: Int,
        advertisingSession: Int,
        flags: Int
    ): ByteArray {
        require(networkRevision in 0..0x00ff_ffff)
        require(advertisingSession in 0..0xff)
        return ByteBuffer.allocate(PAYLOAD_SIZE)
            .put(BleConstants.PROTOCOL_VERSION)
            .put((BleConstants.MESSAGE_SERVER.toInt() or flags).toByte())
            .put(tokenBytes(token))
            .put((networkRevision ushr 16).toByte())
            .put((networkRevision ushr 8).toByte())
            .put(networkRevision.toByte())
            .put(advertisingSession.toByte())
            .array()
    }

    fun parseServer(payload: ByteArray): ServerBeacon? {
        if (payload.firstOrNull() != BleConstants.PROTOCOL_VERSION) return null
        if (payload.size != PAYLOAD_SIZE) return null
        val typeAndFlags = payload[1].toInt() and 0xff
        if (typeAndFlags and 0x0f != BleConstants.MESSAGE_SERVER.toInt()) return null
        return ServerBeacon(
            token = payload.copyOfRange(2, 6).toHex(),
            networkRevision = ((payload[6].toInt() and 0xff) shl 16) or
                ((payload[7].toInt() and 0xff) shl 8) or
                (payload[8].toInt() and 0xff),
            advertisingSession = payload[9].toInt() and 0xff,
            flags = typeAndFlags and 0xf0
        )
    }

    /** Stable de-duplication key for one credential revision in one server availability run. */
    fun alertIdentity(networkRevision: Int, advertisingSession: Int): Int {
        require(networkRevision in 0..0x00ff_ffff)
        require(advertisingSession in 0..0xff)
        return networkRevision xor (advertisingSession * 0x9e3779b9.toInt())
    }

    fun encodeDistress(key: SecretKeySpec, counter: Int): ByteArray {
        val prefix = byteArrayOf(
            BleConstants.PROTOCOL_VERSION,
            BleConstants.MESSAGE_DISTRESS
        ) + ByteBuffer.allocate(4).putInt(counter).array()
        return prefix + BleSessionCrypto.hmacTag(key.encoded, prefix, 4)
    }

    fun verifyDistress(payload: ByteArray, key: SecretKeySpec): Boolean {
        if (payload.size != PAYLOAD_SIZE ||
            payload[0] != BleConstants.PROTOCOL_VERSION ||
            payload[1] != BleConstants.MESSAGE_DISTRESS
        ) return false
        val expectedTag = BleSessionCrypto.hmacTag(key.encoded, payload.copyOfRange(0, 6), 4)
        return MessageDigest.isEqual(payload.copyOfRange(6, 10), expectedTag)
    }

    fun distressCounter(payload: ByteArray): Long =
        Integer.toUnsignedLong(ByteBuffer.wrap(payload, 2, 4).int)

    data class WakeRequest(val route: Int, val counter: Int)

    /** version + type + 8-bit route hint + 24-bit counter + 32-bit authenticator. */
    fun encodeWakeRequest(
        key: SecretKeySpec,
        clientFingerprint: String,
        counter: Int
    ): ByteArray {
        require(counter in 1..0xFF_FFFF) { "Wake counter is outside the 24-bit range" }
        val route = clientFingerprint.take(2).toInt(16).toByte()
        val prefix = byteArrayOf(
            BleConstants.PROTOCOL_VERSION,
            BleConstants.MESSAGE_WAKE_REQUEST,
            route,
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte()
        )
        return prefix + BleSessionCrypto.hmacTag(key.encoded, prefix, 4)
    }

    fun parseWakeRequest(payload: ByteArray): WakeRequest? {
        if (payload.size != PAYLOAD_SIZE ||
            payload[0] != BleConstants.PROTOCOL_VERSION ||
            payload[1] != BleConstants.MESSAGE_WAKE_REQUEST
        ) return null
        val counter = ((payload[3].toInt() and 0xff) shl 16) or
            ((payload[4].toInt() and 0xff) shl 8) or
            (payload[5].toInt() and 0xff)
        return WakeRequest(payload[2].toInt() and 0xff, counter)
    }

    fun verifyWakeRequest(payload: ByteArray, key: SecretKeySpec): Boolean {
        if (parseWakeRequest(payload) == null) return false
        val expected = BleSessionCrypto.hmacTag(key.encoded, payload.copyOfRange(0, 6), 4)
        return MessageDigest.isEqual(expected, payload.copyOfRange(6, 10))
    }

    private fun tokenBytes(token: String): ByteArray {
        require(token.length == 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Discovery token must contain eight hexadecimal characters"
        }
        return ByteArray(4) { index -> token.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
