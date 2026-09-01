package com.agentkosticka.easierspot.ble

import java.nio.ByteBuffer

/** Small plaintext carried only inside the authenticated BLE session envelope. */
data class ServerStatusMessage(
    val type: Type,
    val activeClientCount: Int
) {
    enum class Type(val wireValue: Byte) {
        AVAILABLE(0x01),
        SHARING(0x02),
        SERVER_STOPPING(0x03),
        HOTSPOT_STOPPED(0x04),
        CLIENT_DISCONNECTED(0x05),
        PRIVILEGED_CONTROL_LOST(0x06);

        companion object {
            fun fromWire(value: Byte): Type? = entries.firstOrNull { it.wireValue == value }
        }
    }

    init {
        require(activeClientCount in 0..0xffff)
    }

    fun encode(): ByteArray = ByteBuffer.allocate(4)
        .put(FORMAT_VERSION)
        .put(type.wireValue)
        .putShort(activeClientCount.toShort())
        .array()

    companion object {
        private const val FORMAT_VERSION: Byte = 0x01

        fun decode(value: ByteArray): ServerStatusMessage? {
            if (value.size != 4) return null
            val buffer = ByteBuffer.wrap(value)
            if (buffer.get() != FORMAT_VERSION) return null
            val type = Type.fromWire(buffer.get()) ?: return null
            return ServerStatusMessage(type, buffer.short.toInt() and 0xffff)
        }
    }
}
