package com.agentkosticka.easierspot.ble

import com.agentkosticka.easierspot.data.model.HotspotCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class BleDiscoveryProtocolTest {
    @Test
    fun serverBeacon_roundTripsMetadata() {
        val encoded = BleDiscoveryProtocol.encodeServer(
            "0123abcd",
            0x345678,
            73,
            BleConstants.FLAG_AUTOMATIC_ACTIVATION
        )
        val decoded = BleDiscoveryProtocol.parseServer(encoded)!!

        assertEquals(BleDiscoveryProtocol.PAYLOAD_SIZE, encoded.size)
        assertEquals("0123abcd", decoded.token)
        assertEquals(0x345678, decoded.networkRevision)
        assertEquals(73, decoded.advertisingSession)
        assertEquals(BleConstants.FLAG_AUTOMATIC_ACTIVATION, decoded.flags)
    }

    @Test
    fun legacyServerBeacon_isRejectedByProtocolV3() {
        val decoded = BleDiscoveryProtocol.parseServer(
            byteArrayOf(BleConstants.PROTOCOL_VERSION, 0x01, 0x23, 0xab.toByte(), 0xcd.toByte())
        )
        assertNull(decoded)
    }

    @Test
    fun networkRevision_changesWithCredentials() {
        val first = BleDiscoveryProtocol.networkRevision(HotspotCredentials("Spot", "password1"))
        val second = BleDiscoveryProtocol.networkRevision(HotspotCredentials("Spot", "password2"))
        assertNotEquals(first, second)
        assertTrue(first in 0..0x00ff_ffff)
        assertTrue(second in 0..0x00ff_ffff)
    }

    @Test
    fun freshAdvertisingSession_changesAlertIdentityWithoutChangingNetwork() {
        val revision = 0x123456
        val first = BleDiscoveryProtocol.alertIdentity(revision, 1)
        val second = BleDiscoveryProtocol.alertIdentity(revision, 2)

        assertNotEquals(first, second)
        assertEquals(first, BleDiscoveryProtocol.alertIdentity(revision, 1))
    }

    @Test
    fun distress_isAuthenticatedAndCounterCarrying() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val wrongKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val payload = BleDiscoveryProtocol.encodeDistress(key, 42)

        assertTrue(BleDiscoveryProtocol.verifyDistress(payload, key))
        assertEquals(42L, BleDiscoveryProtocol.distressCounter(payload))
        assertFalse(BleDiscoveryProtocol.verifyDistress(payload, wrongKey))
    }


    @Test
    fun wakeRequest_isAuthenticatedAndCarriesRouteAndCounter() {
        val key = SecretKeySpec(ByteArray(32) { (it * 3).toByte() }, "AES")
        val payload = BleDiscoveryProtocol.encodeWakeRequest(key, "ab1234567890cdef", 0x123456)
        val request = BleDiscoveryProtocol.parseWakeRequest(payload)!!

        assertEquals(BleDiscoveryProtocol.PAYLOAD_SIZE, payload.size)
        assertEquals(0xab, request.route)
        assertEquals(0x123456, request.counter)
        assertTrue(BleDiscoveryProtocol.verifyWakeRequest(payload, key))
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        assertFalse(BleDiscoveryProtocol.verifyWakeRequest(payload, key))
    }
}
