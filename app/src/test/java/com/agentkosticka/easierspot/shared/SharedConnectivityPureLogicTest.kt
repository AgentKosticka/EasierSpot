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

    @Test
    fun wifiPickerState_nativeProviderAlwaysWins() {
        assertEquals(
            SystemWifiPickerState.NATIVE_REMOTE_ENTRIES,
            resolveSystemWifiPickerState(
                nativeRemoteEntriesActive = true,
                suggestionApprovalPending = false,
                suggestionApprovalRejected = true,
                trustedNetworkCount = 1,
                pickerSelectableSuggestionCount = 0
            )
        )
    }

    @Test
    fun wifiPickerState_tracksSuggestionFallbackLifecycle() {
        assertEquals(
            SystemWifiPickerState.SUGGESTION_READY,
            resolveSystemWifiPickerState(false, false, false, 0, 0)
        )
        assertEquals(
            SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH,
            resolveSystemWifiPickerState(false, false, false, 1, 0)
        )
        assertEquals(
            SystemWifiPickerState.SUGGESTION_ACTIVE,
            resolveSystemWifiPickerState(false, false, false, 1, 1)
        )
        assertEquals(
            SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING,
            resolveSystemWifiPickerState(false, true, false, 1, 1)
        )
        assertEquals(
            SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED,
            resolveSystemWifiPickerState(false, false, true, 1, 1)
        )
    }
}
