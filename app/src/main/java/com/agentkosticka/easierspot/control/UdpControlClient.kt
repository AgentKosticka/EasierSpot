package com.agentkosticka.easierspot.control

import android.net.Network
import android.os.SystemClock
import com.agentkosticka.easierspot.ble.BleConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.crypto.spec.SecretKeySpec

class UdpControlClient(
    private val key: SecretKeySpec,
    private val route: Int,
    private val nextCounter: () -> Long
) {
    private var session: Session? = null

    private data class Session(val gateway: InetAddress, val socket: DatagramSocket)

    suspend fun hello(network: Network, gateway: InetAddress): Boolean = withContext(Dispatchers.IO) {
        close()
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(0))
            network.bindSocket(socket)
            socket.soTimeout = 180
            val request = ControlPacket.request(BleConstants.UDP_HELLO, route, nextCounter())
            val encoded = ControlPacket.encode(request, key)
            val startedAt = SystemClock.elapsedRealtime()
            for (targetMs in longArrayOf(0L, 200L, 500L, 1_000L)) {
                val waitMs = targetMs - (SystemClock.elapsedRealtime() - startedAt)
                if (waitMs > 0) delay(waitMs)
                socket.send(DatagramPacket(encoded, encoded.size, gateway, BleConstants.UDP_CONTROL_PORT))
                val response = ByteArray(ControlPacket.ACK_SIZE)
                val packet = DatagramPacket(response, response.size)
                try {
                    socket.receive(packet)
                    val ack = ControlPacket.decode(response.copyOf(packet.length), key) as? ControlPacket.Ack
                    if (ack != null && ControlPacket.isAckFor(ack, request)) {
                        session = Session(gateway, socket)
                        return@withContext true
                    }
                } catch (_: SocketTimeoutException) {
                    // The bounded retry schedule keeps the five-second path responsive.
                }
            }
            socket.close()
            false
        } catch (_: Throwable) {
            socket.close()
            false
        }
    }

    suspend fun heartbeat(): Boolean = send(BleConstants.UDP_HEARTBEAT)

    suspend fun goodbye() {
        send(BleConstants.UDP_GOODBYE)
        close()
    }

    @Synchronized
    fun close() {
        session?.socket?.close()
        session = null
    }

    private suspend fun send(type: Byte): Boolean = withContext(Dispatchers.IO) {
        val active = session ?: return@withContext false
        val request = ControlPacket.request(type, route, nextCounter())
        val encoded = ControlPacket.encode(request, key)
        runCatching {
            active.socket.send(
                DatagramPacket(encoded, encoded.size, active.gateway, BleConstants.UDP_CONTROL_PORT)
            )
            if (type == BleConstants.UDP_GOODBYE) return@runCatching true
            active.socket.soTimeout = 500
            val response = ByteArray(ControlPacket.ACK_SIZE)
            val packet = DatagramPacket(response, response.size)
            active.socket.receive(packet)
            val ack = ControlPacket.decode(response.copyOf(packet.length), key) as? ControlPacket.Ack
            ack != null && ControlPacket.isAckFor(ack, request)
        }.getOrDefault(false)
    }
}
