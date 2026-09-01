package com.agentkosticka.easierspot.control

import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/** Compact authenticated packets used only on the active hotspot network. */
sealed interface ControlPacket {
    val route: Int
    val counter: Long

    data class Request(
        val type: Byte,
        override val route: Int,
        override val counter: Long,
        val nonce: ByteArray
    ) : ControlPacket

    data class Ack(
        override val route: Int,
        override val counter: Long,
        val clientNonce: ByteArray,
        val serverNonce: ByteArray
    ) : ControlPacket

    companion object Codec {
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val REQUEST_SIZE = 1 + 1 + 2 + 8 + NONCE_SIZE + TAG_SIZE
        const val ACK_SIZE = 1 + 1 + 2 + 8 + NONCE_SIZE + NONCE_SIZE + TAG_SIZE

        fun request(type: Byte, route: Int, counter: Long): Request {
            require(type in setOf(BleConstants.UDP_HELLO, BleConstants.UDP_HEARTBEAT, BleConstants.UDP_GOODBYE))
            require(route in 0..0xffff)
            require(counter > 0)
            return Request(type, route, counter, randomNonce())
        }

        fun ack(request: Request): Ack = Ack(
            route = request.route,
            counter = request.counter,
            clientNonce = request.nonce.copyOf(),
            serverNonce = randomNonce()
        )

        fun encode(packet: ControlPacket, key: SecretKeySpec): ByteArray {
            val unsigned = when (packet) {
                is Request -> ByteBuffer.allocate(REQUEST_SIZE - TAG_SIZE)
                    .put(BleConstants.UDP_CONTROL_VERSION)
                    .put(packet.type)
                    .putShort(packet.route.toShort())
                    .putLong(packet.counter)
                    .put(packet.nonce.requireNonce())
                    .array()
                is Ack -> ByteBuffer.allocate(ACK_SIZE - TAG_SIZE)
                    .put(BleConstants.UDP_CONTROL_VERSION)
                    .put(BleConstants.UDP_ACK)
                    .putShort(packet.route.toShort())
                    .putLong(packet.counter)
                    .put(packet.clientNonce.requireNonce())
                    .put(packet.serverNonce.requireNonce())
                    .array()
            }
            return unsigned + BleSessionCrypto.hmacTag(key.encoded, unsigned, TAG_SIZE)
        }

        fun decode(value: ByteArray, key: SecretKeySpec): ControlPacket? {
            if (value.size != REQUEST_SIZE && value.size != ACK_SIZE) return null
            val unsigned = value.copyOf(value.size - TAG_SIZE)
            val expected = BleSessionCrypto.hmacTag(key.encoded, unsigned, TAG_SIZE)
            if (!MessageDigest.isEqual(expected, value.copyOfRange(unsigned.size, value.size))) return null
            val buffer = ByteBuffer.wrap(unsigned)
            if (buffer.get() != BleConstants.UDP_CONTROL_VERSION) return null
            val type = buffer.get()
            val route = buffer.short.toInt() and 0xffff
            val counter = buffer.long
            if (counter <= 0) return null
            return if (type == BleConstants.UDP_ACK && value.size == ACK_SIZE) {
                val clientNonce = ByteArray(NONCE_SIZE).also(buffer::get)
                val serverNonce = ByteArray(NONCE_SIZE).also(buffer::get)
                Ack(route, counter, clientNonce, serverNonce)
            } else if (type in setOf(
                    BleConstants.UDP_HELLO,
                    BleConstants.UDP_HEARTBEAT,
                    BleConstants.UDP_GOODBYE
                ) && value.size == REQUEST_SIZE
            ) {
                Request(type, route, counter, ByteArray(NONCE_SIZE).also(buffer::get))
            } else null
        }

        fun routeFrom(value: ByteArray): Int? {
            if (value.size != REQUEST_SIZE && value.size != ACK_SIZE) return null
            if (value[0] != BleConstants.UDP_CONTROL_VERSION) return null
            return ByteBuffer.wrap(value, 2, 2).short.toInt() and 0xffff
        }

        fun isAckFor(ack: Ack, request: Request): Boolean =
            ack.route == request.route && ack.counter == request.counter &&
                MessageDigest.isEqual(ack.clientNonce, request.nonce)

        private fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        private fun ByteArray.requireNonce(): ByteArray = apply { require(size == NONCE_SIZE) }
    }
}
