package com.agentkosticka.easierspot.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Client-side trust. Hotspot passwords deliberately remain owned by Android, not this table. */
@Entity(tableName = "trusted_servers", indices = [Index("discoveryToken")])
data class TrustedServerEntity(
    @PrimaryKey val fingerprint: String,
    val discoveryToken: String,
    val displayName: String,
    val nickname: String? = null,
    val ssid: String,
    val advertisedRevision: Int = 0,
    val provisionedRevision: Int = 0,
    val securityType: String = "WPA2_PSK",
    val isHidden: Boolean = false,
    val lastSeen: Long = 0L,
    val serverPublicKey: String = "",
    val wakeCounter: Int = 0,
    val alertsEnabled: Boolean = true,
    val lastSuccessfulMethod: String? = null,
    val suggestionLatencyMs: Long = 0L,
    val shizukuLatencyMs: Long = 0L,
    val controlCounter: Long = 0L,
    val lastAlertAt: Long = 0L,
    val lastAlertRevision: Int = -1,
    val lastPresenceAt: Long = 0L
)
