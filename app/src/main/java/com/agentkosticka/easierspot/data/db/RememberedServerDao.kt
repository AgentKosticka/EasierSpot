package com.agentkosticka.easierspot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agentkosticka.easierspot.data.model.RememberedServer
import kotlinx.coroutines.flow.Flow

@Dao
interface RememberedServerDao {
    @Query("SELECT * FROM remembered_servers ORDER BY lastSeen DESC")
    fun getAllServers(): Flow<List<RememberedServer>>

    @Query("SELECT * FROM remembered_servers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getServerById(deviceId: String): RememberedServer?

    @Query("SELECT * FROM remembered_servers")
    suspend fun getAllNow(): List<RememberedServer>

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

    @Query("SELECT COUNT(*) FROM remembered_servers")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM remembered_servers WHERE approvalPolicy = :policy")
    suspend fun getCountByPolicy(policy: String): Int

    @Query("UPDATE remembered_servers SET wakeCounter = :counter WHERE deviceId = :deviceId")
    suspend fun updateWakeCounter(deviceId: String, counter: Int)

    @Query("UPDATE remembered_servers SET controlCounter = :counter, lastControlSeen = :seenAt WHERE deviceId = :deviceId")
    suspend fun updateControlCounter(deviceId: String, counter: Long, seenAt: Long)

    @Transaction
    suspend fun acceptWakeCounter(deviceId: String, counter: Int): Boolean {
        val current = getServerById(deviceId) ?: return false
        if (counter <= current.wakeCounter) return false
        updateWakeCounter(deviceId, counter)
        return true
    }

    @Transaction
    suspend fun acceptControlCounter(deviceId: String, counter: Long, seenAt: Long): Boolean {
        val current = getServerById(deviceId) ?: return false
        if (counter <= current.controlCounter) return false
        updateControlCounter(deviceId, counter, seenAt)
        return true
    }
}
