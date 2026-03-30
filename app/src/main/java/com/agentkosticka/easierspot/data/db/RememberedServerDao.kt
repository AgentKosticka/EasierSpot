package com.agentkosticka.easierspot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentkosticka.easierspot.data.model.RememberedServer
import kotlinx.coroutines.flow.Flow

@Dao
interface RememberedServerDao {
    @Query("SELECT * FROM remembered_servers ORDER BY lastSeen DESC")
    fun getAllServers(): Flow<List<RememberedServer>>

    @Query("SELECT * FROM remembered_servers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getServerById(deviceId: String): RememberedServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: RememberedServer)

    @Query("DELETE FROM remembered_servers WHERE deviceId = :deviceId")
    suspend fun deleteServer(deviceId: String)

    @Query("UPDATE remembered_servers SET lastSeen = :timestamp WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM remembered_servers WHERE deviceAddress = :deviceAddress LIMIT 1")
    suspend fun getServerByAddress(deviceAddress: String): RememberedServer?

    @Query("UPDATE remembered_servers SET nickname = :nickname WHERE deviceId = :deviceId")
    suspend fun updateNickname(deviceId: String, nickname: String?)

    @Query("UPDATE remembered_servers SET approvalPolicy = :approvalPolicy WHERE deviceId = :deviceId")
    suspend fun updateApprovalPolicy(deviceId: String, approvalPolicy: String)

    @Query("UPDATE remembered_servers SET lastApprovedAt = :lastApprovedAt WHERE deviceId = :deviceId")
    suspend fun updateLastApprovedAt(deviceId: String, lastApprovedAt: Long)
}
