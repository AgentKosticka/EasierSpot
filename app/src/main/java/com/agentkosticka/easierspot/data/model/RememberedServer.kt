package com.agentkosticka.easierspot.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remembered_servers")
data class RememberedServer(
    @PrimaryKey
    val deviceId: String, // Stable client identifier (not BLE MAC address)
    val deviceName: String,
    val deviceAddress: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
    val isApproved: Boolean = false
)
