package com.agentkosticka.easierspot.control

import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.server.WakePeerStore
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** Listens only while the server owns or observes an active hotspot. */
class UdpControlServer(
    private val peers: WakePeerStore,
    private val onAuthenticated: (fingerprint: String, type: Byte, sourceAddress: InetAddress) -> Unit
) {
    companion object { private const val TAG = "UdpControlServer" }

    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private data class CachedAck(val requestBytes: ByteArray, val ackBytes: ByteArray, val createdAt: Long)
    private val recentAcks = linkedMapOf<String, CachedAck>()

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val activeSocket = runCatching {
                DatagramSocket(BleConstants.UDP_CONTROL_PORT).apply {
                    soTimeout = 1_000
                    broadcast = false
                }
            }.getOrElse {
                LogUtils.w(TAG, "Could not open authenticated control port", it)
                return@launch
            }
            socket = activeSocket
            try {
                val storage = ByteArray(ControlPacket.ACK_SIZE)
                while (isActive && !activeSocket.isClosed) {
                    val datagram = DatagramPacket(storage, storage.size)
                    try {
                        activeSocket.receive(datagram)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val value = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                    val route = ControlPacket.routeFrom(value) ?: continue
                    val accepted = peers.controlPeers(route).firstNotNullOfOrNull { peer ->
                        val request = ControlPacket.decode(value, peer.key) as? ControlPacket.Request
                            ?: return@firstNotNullOfOrNull null
                        val cacheKey = "${peer.fingerprint}:${request.counter}"
                        val cached = recentAcks[cacheKey]
                        if (!peers.acceptControlCounter(peer, request.counter)) {
                            if (cached != null && System.currentTimeMillis() - cached.createdAt <= 5_000L &&
                                MessageDigest.isEqual(cached.requestBytes, value)
                            ) return@firstNotNullOfOrNull Triple(peer, request, cached.ackBytes)
                            return@firstNotNullOfOrNull null
                        }
                        val ack = ControlPacket.encode(ControlPacket.ack(request), peer.key)
                        recentAcks[cacheKey] = CachedAck(value, ack, System.currentTimeMillis())
                        recentAcks.entries.removeAll { System.currentTimeMillis() - it.value.createdAt > 5_000L }
                        Triple(peer, request, ack)
                    } ?: continue
                    val (peer, request, ack) = accepted
                    if (request.counter > peer.lastCounter) {
                        onAuthenticated(peer.fingerprint, request.type, datagram.address)
                    }
                    activeSocket.send(DatagramPacket(ack, ack.size, datagram.address, datagram.port))
                }
            } finally {
                activeSocket.close()
                if (socket === activeSocket) socket = null
            }
        }
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        job?.cancel()
        job = null
        recentAcks.clear()
    }
}
