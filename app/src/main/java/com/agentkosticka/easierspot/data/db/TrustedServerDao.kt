package com.agentkosticka.easierspot.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.agentkosticka.easierspot.data.model.TrustedServerEntity

@Dao
interface TrustedServerDao {
    @Query("SELECT * FROM trusted_servers ORDER BY lastSeen DESC")
    fun getAll(): List<TrustedServerEntity>

    @Query("SELECT * FROM trusted_servers WHERE discoveryToken = :token COLLATE NOCASE LIMIT 1")
    fun findByToken(token: String): TrustedServerEntity?

    @Query("SELECT * FROM trusted_servers WHERE fingerprint = :fingerprint LIMIT 1")
    fun findByFingerprint(fingerprint: String): TrustedServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(server: TrustedServerEntity)

    @Query("DELETE FROM trusted_servers WHERE fingerprint = :fingerprint")
    fun delete(fingerprint: String)

    @Query("SELECT COUNT(*) FROM trusted_servers")
    fun count(): Int

    @Transaction
    fun advanceWakeCounter(fingerprint: String): TrustedServerEntity? {
        val profile = findByFingerprint(fingerprint) ?: return null
        val next = if (profile.wakeCounter >= 0xFF_FFFE) 1 else profile.wakeCounter + 1
        val updated = profile.copy(wakeCounter = next)
        upsert(updated)
        return updated
    }

    @Transaction
    fun advanceControlCounter(fingerprint: String): TrustedServerEntity? {
        val profile = findByFingerprint(fingerprint) ?: return null
        val next = if (profile.controlCounter == Long.MAX_VALUE) 1L else profile.controlCounter + 1L
        val updated = profile.copy(controlCounter = next)
        upsert(updated)
        return updated
    }
}
