package com.agentkosticka.easierspot.ble.client

import android.content.Context
import android.util.Base64
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.BleSessionCrypto
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.data.model.TrustedServerEntity

const val PRESENCE_WINDOW_MS = 90_000L

data class TrustedServerProfile(
    val fingerprint: String,
    val discoveryToken: String,
    val displayName: String,
    val ssid: String,
    val advertisedRevision: Int,
    val provisionedRevision: Int = 0,
    val securityType: String = HotspotCredentials.SecurityType.WPA2_PSK.name,
    val isHidden: Boolean = false,
    val lastSeen: Long,
    val serverPublicKey: String = "",
    val wakeCounter: Int = 0,
    val nickname: String? = null,
    val alertsEnabled: Boolean = true,
    val lastSuccessfulMethod: String? = null,
    val suggestionLatencyMs: Long = 0L,
    val shizukuLatencyMs: Long = 0L,
    val controlCounter: Long = 0L,
    val lastAlertAt: Long = 0L,
    val lastAlertRevision: Int = -1,
    val lastPresenceAt: Long = 0L,
    val lastPresenceFlags: Int = 0
) {
    val label: String get() = nickname?.takeIf(String::isNotBlank) ?: ssid.ifBlank { displayName }
    val networkRevision: Int get() = advertisedRevision
}

fun TrustedServerProfile.isRecentlyPresent(now: Long = System.currentTimeMillis()): Boolean =
    lastPresenceAt > 0L && now >= lastPresenceAt && now - lastPresenceAt < PRESENCE_WINDOW_MS

internal fun shouldAlertForPresence(
    profile: TrustedServerProfile,
    revision: Int,
    now: Long,
    absenceMs: Long
): Boolean {
    val newlyPresent = profile.lastPresenceAt == 0L || now - profile.lastPresenceAt >= absenceMs
    val networkChanged = profile.lastAlertRevision != revision
    return profile.alertsEnabled && (networkChanged || newlyPresent)
}

/** Client-side trust repository. Hotspot passwords remain owned by Android, never this store. */
class TrustedServerStore(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getDatabase(appContext).trustedServerDao()

    fun all(): List<TrustedServerProfile> = dao.getAll().map { entity -> entity.toProfile() }

    fun findByToken(token: String): TrustedServerProfile? = dao.findByToken(token)?.toProfile()

    fun findByFingerprint(fingerprint: String): TrustedServerProfile? =
        dao.findByFingerprint(fingerprint)?.toProfile()

    /**
     * Upserts trust data without allowing a stale connection snapshot to erase newer presence,
     * alert de-duplication, or authenticated monotonic counters written by background work.
     */
    @Synchronized
    fun remember(profile: TrustedServerProfile) {
        val current = findByFingerprint(profile.fingerprint)
        if (current == null) {
            dao.upsert(profile.toEntity())
            return
        }
        val keepCurrentPresence = current.lastPresenceAt > profile.lastPresenceAt
        val keepCurrentAlert = current.lastAlertAt > profile.lastAlertAt
        dao.upsert(
            profile.copy(
                lastSeen = maxOf(profile.lastSeen, current.lastSeen),
                wakeCounter = maxOf(profile.wakeCounter, current.wakeCounter),
                controlCounter = maxOf(profile.controlCounter, current.controlCounter),
                lastPresenceAt = maxOf(profile.lastPresenceAt, current.lastPresenceAt),
                lastPresenceFlags = if (keepCurrentPresence) {
                    current.lastPresenceFlags
                } else profile.lastPresenceFlags,
                lastAlertAt = maxOf(profile.lastAlertAt, current.lastAlertAt),
                lastAlertRevision = if (keepCurrentAlert) {
                    current.lastAlertRevision
                } else profile.lastAlertRevision
            ).toEntity()
        )
    }

    fun forget(fingerprint: String) = dao.delete(fingerprint)

    fun recordSuccessfulMethod(fingerprint: String, method: String) {
        val current = findByFingerprint(fingerprint) ?: return
        remember(current.copy(lastSuccessfulMethod = method))
    }

    fun recordMethodSuccess(fingerprint: String, method: String, latencyMs: Long) {
        val current = findByFingerprint(fingerprint) ?: return
        val bounded = latencyMs.coerceIn(1L, 120_000L)
        fun average(previous: Long) = if (previous <= 0L) bounded else (previous * 3L + bounded) / 4L
        remember(
            current.copy(
                lastSuccessfulMethod = method,
                suggestionLatencyMs = if (method == "SUGGESTION") average(current.suggestionLatencyMs) else current.suggestionLatencyMs,
                shizukuLatencyMs = if (method == "SHIZUKU") average(current.shizukuLatencyMs) else current.shizukuLatencyMs
            )
        )
    }

    fun nextControlProfile(fingerprint: String): TrustedServerProfile? =
        dao.advanceControlCounter(fingerprint)?.toProfile()

    /** Returns the updated profile and whether this observation should alert the user. */
    @Synchronized
    fun recordPresenceAndShouldAlert(
        token: String,
        revision: Int,
        _advertisingSession: Int,
        now: Long,
        flags: Int = 0,
        absenceMs: Long = PRESENCE_WINDOW_MS
    ): Pair<TrustedServerProfile, Boolean>? {
        val profile = findByToken(token) ?: return null
        // Advertising sessions rotate whenever the server restarts its advertiser. They are useful
        // for transport freshness but must not create another user alert for the same network.
        val alertIdentity = revision
        val shouldAlert = shouldAlertForPresence(profile, revision, now, absenceMs)
        val updated = profile.copy(
            advertisedRevision = revision,
            lastSeen = now,
            lastPresenceAt = now,
            lastPresenceFlags = flags,
            lastAlertAt = if (shouldAlert) now else profile.lastAlertAt,
            // Durable de-duplication survives process death and advertiser restarts.
            lastAlertRevision = if (shouldAlert) alertIdentity else profile.lastAlertRevision
        )
        remember(updated)
        return updated to shouldAlert
    }

    @Synchronized
    fun nextWakePayload(profileFingerprint: String): ByteArray? {
        val profile = dao.advanceWakeCounter(profileFingerprint) ?: return null
        if (profile.serverPublicKey.isBlank()) return null
        return runCatching {
            val serverPublic = BleSessionCrypto.decodePeerPublicKey(
                Base64.decode(profile.serverPublicKey, Base64.NO_WRAP)
            )
            val clientKeys = BleSessionCrypto.clientKeyPair(appContext)
            val wakeKey = BleSessionCrypto.wakeKey(clientKeys.private, serverPublic)
            BleDiscoveryProtocol.encodeWakeRequest(
                wakeKey,
                BleSessionCrypto.fingerprint(clientKeys.public),
                profile.wakeCounter
            )
        }.getOrNull()
    }

    private fun TrustedServerEntity.toProfile() = TrustedServerProfile(
        fingerprint = fingerprint,
        discoveryToken = discoveryToken,
        displayName = displayName,
        ssid = ssid,
        advertisedRevision = advertisedRevision,
        provisionedRevision = provisionedRevision,
        securityType = securityType,
        isHidden = isHidden,
        lastSeen = lastSeen,
        serverPublicKey = serverPublicKey,
        wakeCounter = wakeCounter,
        nickname = nickname,
        alertsEnabled = alertsEnabled,
        lastSuccessfulMethod = lastSuccessfulMethod,
        suggestionLatencyMs = suggestionLatencyMs,
        shizukuLatencyMs = shizukuLatencyMs,
        controlCounter = controlCounter,
        lastAlertAt = lastAlertAt,
        lastAlertRevision = lastAlertRevision,
        lastPresenceAt = lastPresenceAt,
        lastPresenceFlags = lastPresenceFlags
    )

    private fun TrustedServerProfile.toEntity() = TrustedServerEntity(
        fingerprint = fingerprint,
        discoveryToken = discoveryToken,
        displayName = displayName,
        nickname = nickname,
        ssid = ssid,
        advertisedRevision = advertisedRevision,
        provisionedRevision = provisionedRevision,
        securityType = securityType,
        isHidden = isHidden,
        lastSeen = lastSeen,
        serverPublicKey = serverPublicKey,
        wakeCounter = wakeCounter,
        alertsEnabled = alertsEnabled,
        lastSuccessfulMethod = lastSuccessfulMethod,
        suggestionLatencyMs = suggestionLatencyMs,
        shizukuLatencyMs = shizukuLatencyMs,
        controlCounter = controlCounter,
        lastAlertAt = lastAlertAt,
        lastAlertRevision = lastAlertRevision,
        lastPresenceAt = lastPresenceAt,
        lastPresenceFlags = lastPresenceFlags
    )
}
