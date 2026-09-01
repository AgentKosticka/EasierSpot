package com.agentkosticka.easierspot.service

import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.data.model.HotspotCredentials

data class FastPathDecision(
    val refreshCredentials: Boolean,
    val shizukuDelayAfterReadyMs: Long
)

object FastConnectCoordinator {
    fun decide(profile: TrustedServerProfile, suggestionExists: Boolean): FastPathDecision {
        val securityKnown = runCatching {
            HotspotCredentials.SecurityType.valueOf(profile.securityType)
        }.isSuccess
        val refresh = !suggestionExists || !securityKnown ||
            profile.advertisedRevision == 0 ||
            profile.advertisedRevision != profile.provisionedRevision
        val shizukuHistoricallyWins = profile.shizukuLatencyMs > 0L &&
            (profile.suggestionLatencyMs <= 0L || profile.shizukuLatencyMs + 250L < profile.suggestionLatencyMs)
        return FastPathDecision(
            refreshCredentials = refresh,
            shizukuDelayAfterReadyMs = if (shizukuHistoricallyWins) 0L else 1_000L
        )
    }
}
