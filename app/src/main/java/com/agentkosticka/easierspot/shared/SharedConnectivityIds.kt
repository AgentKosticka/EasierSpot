package com.agentkosticka.easierspot.shared

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun stableSharedConnectivityDeviceId(fingerprint: String): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(fingerprint.toByteArray(StandardCharsets.UTF_8))
    return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE
}
