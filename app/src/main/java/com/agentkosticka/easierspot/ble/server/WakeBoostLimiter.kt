package com.agentkosticka.easierspot.ble.server

/** Limits authenticated boosts per peer and across the whole server. */
internal class WakeBoostLimiter {
    companion object {
        const val PER_PEER_COOLDOWN_MS = 30_000L
        const val GLOBAL_COOLDOWN_MS = 15_000L
        const val GLOBAL_WINDOW_MS = 5 * 60_000L
        const val MAX_GLOBAL_BOOSTS_PER_WINDOW = 6
    }

    private val lastByPeer = mutableMapOf<String, Long>()
    private val globalBoosts = ArrayDeque<Long>()

    @Synchronized
    fun allow(peer: String, now: Long = System.currentTimeMillis()): Boolean {
        while (globalBoosts.isNotEmpty() && now - globalBoosts.first() >= GLOBAL_WINDOW_MS) {
            globalBoosts.removeFirst()
        }
        val lastPeer = lastByPeer[peer] ?: Long.MIN_VALUE
        val lastGlobal = globalBoosts.lastOrNull() ?: Long.MIN_VALUE
        if (lastPeer != Long.MIN_VALUE && now - lastPeer < PER_PEER_COOLDOWN_MS) return false
        if (lastGlobal != Long.MIN_VALUE && now - lastGlobal < GLOBAL_COOLDOWN_MS) return false
        if (globalBoosts.size >= MAX_GLOBAL_BOOSTS_PER_WINDOW) return false
        lastByPeer[peer] = now
        globalBoosts.addLast(now)
        return true
    }
}
