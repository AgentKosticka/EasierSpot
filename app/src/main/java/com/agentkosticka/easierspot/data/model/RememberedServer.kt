package com.agentkosticka.easierspot.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remembered_servers")
data class RememberedServer(
    @PrimaryKey
    val deviceId: String, // Stable client identifier (not BLE MAC address)
    val deviceName: String,
    val deviceAddress: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
    val isApproved: Boolean = false,
    val nickname: String? = null,
    @ColumnInfo(defaultValue = "'approved'")
    val approvalPolicy: String = APPROVAL_POLICY_APPROVED,
    @ColumnInfo(defaultValue = "0")
    val lastApprovedAt: Long = 0L
) {
    companion object {
        const val APPROVAL_POLICY_APPROVED = "approved"
        const val APPROVAL_POLICY_ASK = "ask"
        const val APPROVAL_POLICY_DENIED = "denied"
    }
}
