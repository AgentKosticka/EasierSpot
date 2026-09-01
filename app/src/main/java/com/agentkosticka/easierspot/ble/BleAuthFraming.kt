package com.agentkosticka.easierspot.ble

import java.nio.ByteBuffer
import java.security.SecureRandom

/** MTU-independent framing for the signed client-auth message. */
object BleAuthFraming {
    const val FRAME_TYPE: Byte = 0x41
    const val HEADER_SIZE = 8
    private const val MAX_AUTH_SIZE = 512

    data class Frame(
        val messageId: Int,
        val index: Int,
        val count: Int,
        val totalLength: Int,
        val payload: ByteArray
    )

    fun encode(auth: ByteArray, mtu: Int, messageId: Int = SecureRandom().nextInt(0x10000)): List<ByteArray> {
        require(auth.isNotEmpty() && auth.size <= MAX_AUTH_SIZE)
        require(messageId in 0..0xffff)
        val chunkSize = (mtu - 3 - HEADER_SIZE).coerceAtLeast(1)
        val count = (auth.size + chunkSize - 1) / chunkSize
        require(count in 1..255)
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, auth.size)
            ByteBuffer.allocate(HEADER_SIZE + end - start)
                .put(BleConstants.PROTOCOL_VERSION)
                .put(FRAME_TYPE)
                .putShort(messageId.toShort())
                .put(index.toByte())
                .put(count.toByte())
                .putShort(auth.size.toShort())
                .put(auth, start, end - start)
                .array()
        }
    }

    fun parse(value: ByteArray): Frame {
        require(value.size > HEADER_SIZE) { "Authentication frame is empty" }
        val buffer = ByteBuffer.wrap(value)
        require(buffer.get() == BleConstants.PROTOCOL_VERSION) { "Authentication protocol mismatch" }
        require(buffer.get() == FRAME_TYPE) { "Unknown authentication frame" }
        val messageId = buffer.short.toInt() and 0xffff
        val index = buffer.get().toInt() and 0xff
        val count = buffer.get().toInt() and 0xff
        val totalLength = buffer.short.toInt() and 0xffff
        require(count in 1..255 && index < count) { "Invalid authentication frame index" }
        require(totalLength in 1..MAX_AUTH_SIZE) { "Invalid authentication length" }
        return Frame(
            messageId,
            index,
            count,
            totalLength,
            ByteArray(buffer.remaining()).also(buffer::get)
        )
    }

    class Assembler(first: Frame) {
        private val messageId = first.messageId
        private val count = first.count
        private val totalLength = first.totalLength
        private val chunks = ArrayList<ByteArray>(count)

        init { require(first.index == 0); chunks += first.payload }

        fun resultIfComplete(): ByteArray? = if (chunks.size == count) combine() else null

        fun accept(frame: Frame): ByteArray? {
            require(frame.messageId == messageId && frame.count == count && frame.totalLength == totalLength) {
                "Authentication message changed mid-transfer"
            }
            require(frame.index == chunks.size) { "Authentication frame is missing or replayed" }
            chunks += frame.payload
            if (chunks.size < count) return null
            return combine()
        }

        private fun combine(): ByteArray {
            val combined = ByteArray(chunks.sumOf(ByteArray::size))
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(combined, offset)
                offset += chunk.size
            }
            require(combined.size == totalLength) { "Authentication message length mismatch" }
            return combined
        }
    }
}
