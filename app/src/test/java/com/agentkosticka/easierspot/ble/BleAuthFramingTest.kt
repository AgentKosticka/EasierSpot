package com.agentkosticka.easierspot.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleAuthFramingTest {
    @Test
    fun defaultMtu_roundTripsLargeAuthentication() {
        val auth = ByteArray(173) { (it * 11).toByte() }
        val encoded = BleAuthFraming.encode(auth, mtu = 23, messageId = 7)
        assertEquals(15, encoded.size)
        val frames = encoded.map(BleAuthFraming::parse)
        val assembler = BleAuthFraming.Assembler(frames.first())
        frames.drop(1).dropLast(1).forEach { assertNull(assembler.accept(it)) }
        assertArrayEquals(auth, assembler.accept(frames.last()))
    }

    @Test
    fun negotiatedMtu_usesSingleFrame() {
        val auth = ByteArray(165)
        val encoded = BleAuthFraming.encode(auth, mtu = 185, messageId = 9)
        assertEquals(1, encoded.size)
        val frame = BleAuthFraming.parse(encoded.single())
        val assembler = BleAuthFraming.Assembler(frame)
        assertArrayEquals(auth, assembler.resultIfComplete())
    }

    @Test
    fun maximumNegotiatedMtu_usesMaximumSingleFramePayload() {
        // ATT reserves three bytes and our frame header reserves eight more: 517 - 3 - 8.
        val auth = ByteArray(506) { (it * 31).toByte() }
        val encoded = BleAuthFraming.encode(auth, mtu = 517, messageId = 10)
        assertEquals(1, encoded.size)
        val frame = BleAuthFraming.parse(encoded.single())
        val assembler = BleAuthFraming.Assembler(frame)
        assertArrayEquals(auth, assembler.resultIfComplete())
    }
}
