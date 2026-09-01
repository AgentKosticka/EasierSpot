package com.agentkosticka.easierspot.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedConnectivityPureLogicTest {
    @Test
    fun stableDeviceId_isDeterministicAndPositive() {
        val first = stableSharedConnectivityDeviceId("fingerprint-a")
        assertEquals(first, stableSharedConnectivityDeviceId("fingerprint-a"))
        assertTrue(first >= 0L)
        assertNotEquals(first, stableSharedConnectivityDeviceId("fingerprint-b"))
    }

    @Test
    fun overlayLookupParser_acceptsResolvedValuesAndFailsClosed() {
        assertEquals(
            "com.agentkosticka.easierspot",
            parseOverlayLookupValue(
                "android:string/config_sharedConnectivityServicePackage -> com.agentkosticka.easierspot"
            )
        )
        assertEquals(true, parseOverlayLookupBoolean("android:bool/config_hotspotNetworksEnabledForService -> true"))
        assertNull(parseOverlayLookupValue("Error: resource not found"))
        assertNull(parseOverlayLookupBoolean("manufacturer-specific gibberish"))
    }
}
