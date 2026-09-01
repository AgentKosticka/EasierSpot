package com.agentkosticka.easierspot.ble.server

import android.content.Context
import android.util.Base64
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import javax.crypto.spec.SecretKeySpec

/** Database-backed paired-client keys and persistent replay counters. */
class WakePeerStore(context: Context) {
    data class ControlPeer(
        val fingerprint: String,
        val route: Int,
        val lastCounter: Long,
        val key: SecretKeySpec
    )

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).rememberedServerDao()

    suspend fun remember(fingerprint: String, publicKey: ByteArray) {
        val encoded = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        val existing = dao.getServerById(fingerprint)
        dao.insertServer(
            existing?.copy(clientPublicKey = encoded) ?: RememberedServer(
                deviceId = fingerprint,
                deviceName = "Client-$fingerprint",
                isApproved = true,
                clientPublicKey = encoded
            )
        )
    }

    suspend fun verifyAndAdvance(payload: ByteArray): String? {
        val request = BleDiscoveryProtocol.parseWakeRequest(payload) ?: return null
        val serverKeys = BleSessionCrypto.serverKeyPair(appContext)
        val accepted = dao.getAllNow().firstOrNull { peer ->
            peer.isApproved && peer.clientPublicKey.isNotBlank() &&
                peer.deviceId.take(2).toIntOrNull(16) == request.route &&
                request.counter > peer.wakeCounter && runCatching {
                    val publicKey = BleSessionCrypto.decodePeerPublicKey(
                        Base64.decode(peer.clientPublicKey, Base64.NO_WRAP)
                    )
                    val wakeKey = BleSessionCrypto.wakeKey(serverKeys.private, publicKey)
                    BleDiscoveryProtocol.verifyWakeRequest(payload, wakeKey)
                }.getOrDefault(false)
        } ?: return null
        return accepted.deviceId.takeIf { dao.acceptWakeCounter(it, request.counter) }
    }

    suspend fun controlPeers(route: Int): List<ControlPeer> {
        val serverKeys = BleSessionCrypto.serverKeyPair(appContext)
        return dao.getAllNow().mapNotNull { peer ->
            if (!peer.isApproved || peer.clientPublicKey.isBlank() ||
                peer.deviceId.take(4).toIntOrNull(16) != route
            ) return@mapNotNull null
            runCatching {
                val publicKey = BleSessionCrypto.decodePeerPublicKey(
                    Base64.decode(peer.clientPublicKey, Base64.NO_WRAP)
                )
                ControlPeer(
                    fingerprint = peer.deviceId,
                    route = route,
                    lastCounter = peer.controlCounter,
                    key = BleSessionCrypto.controlKey(serverKeys.private, publicKey)
                )
            }.getOrNull()
        }
    }

    suspend fun wakeRoutes(): Set<Int> = dao.getAllNow().asSequence()
            .filter { it.isApproved && it.clientPublicKey.isNotBlank() }
            .mapNotNull { it.deviceId.take(2).toIntOrNull(16) }
            .toSet()

    suspend fun acceptControlCounter(peer: ControlPeer, counter: Long): Boolean =
        dao.acceptControlCounter(peer.fingerprint, counter, System.currentTimeMillis())
}
