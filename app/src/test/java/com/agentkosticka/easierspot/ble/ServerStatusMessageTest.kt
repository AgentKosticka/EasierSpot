package com.agentkosticka.easierspot.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerStatusMessageTest {
    @Test
    fun statusRoundTripsWithRosterCount() {
        val original = ServerStatusMessage(ServerStatusMessage.Type.SHARING, 3)
        assertEquals(original, ServerStatusMessage.decode(original.encode()))
    }

    @Test
    fun unknownOrTruncatedStatusIsRejected() {
        assertNull(ServerStatusMessage.decode(byteArrayOf(1, 2)))
        assertNull(ServerStatusMessage.decode(byteArrayOf(1, 0x7f, 0, 1)))
    }
}
