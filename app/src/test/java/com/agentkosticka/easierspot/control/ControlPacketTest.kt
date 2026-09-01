package com.agentkosticka.easierspot.control

import com.agentkosticka.easierspot.ble.BleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class ControlPacketTest {
    private val key = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "HmacSHA256")

    @Test
    fun requestAndAck_areAuthenticatedAndCorrelated() {
        val request = ControlPacket.request(BleConstants.UDP_HELLO, 0xabcd, 42L)
        val requestBytes = ControlPacket.encode(request, key)
        val decoded = ControlPacket.decode(requestBytes, key) as ControlPacket.Request
        val ack = ControlPacket.ack(decoded)
        val decodedAck = ControlPacket.decode(ControlPacket.encode(ack, key), key) as ControlPacket.Ack

        assertEquals(0xabcd, ControlPacket.routeFrom(requestBytes))
        assertEquals(42L, decoded.counter)
        assertTrue(ControlPacket.isAckFor(decodedAck, request))
    }

    @Test
    fun tamperingAndWrongKey_areRejected() {
        val request = ControlPacket.request(BleConstants.UDP_HEARTBEAT, 12, 8L)
        val encoded = ControlPacket.encode(request, key)
        val wrongKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")

        assertNull(ControlPacket.decode(encoded, wrongKey))
        encoded[10] = (encoded[10].toInt() xor 1).toByte()
        assertNull(ControlPacket.decode(encoded, key))
    }

    @Test
    fun unrelatedAck_doesNotConfirmConnection() {
        val first = ControlPacket.request(BleConstants.UDP_HELLO, 99, 10L)
        val second = ControlPacket.request(BleConstants.UDP_HELLO, 99, 11L)
        assertFalse(ControlPacket.isAckFor(ControlPacket.ack(first), second))
    }
}
