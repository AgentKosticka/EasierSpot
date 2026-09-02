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
    fun privilegedAndDeviceConfigParsers_failClosed() {
        assertEquals(0, parsePrivilegedUid("0\n"))
        assertEquals(2000, parsePrivilegedUid("2000"))
        assertNull(parsePrivilegedUid("uid=2000(shell)"))
        assertEquals(true, parseDeviceConfigBoolean("true\n"))
        assertEquals(false, parseDeviceConfigBoolean("0"))
        assertNull(parseDeviceConfigBoolean("null"))
        assertNull(parseDeviceConfigBoolean("permission denied"))
    }

    @Test
    fun rootFabricatedProviderValues_coverAllRequiredFrameworkResources() {
        val values = sharedConnectivityFabricatedValues(
            "com.agentkosticka.easierspot",
            SharedConnectivityActivation.SERVICE_ACTION
        )
        assertEquals(3, values.size)
        assertEquals(
            setOf(
                "android:string/config_sharedConnectivityServicePackage",
                "android:string/config_sharedConnectivityServiceIntentAction",
                "android:bool/config_hotspotNetworksEnabledForService"
            ),
            values.map { it.resourceName }.toSet()
        )
        assertEquals("com.agentkosticka.easierspot", values[0].value)
        assertEquals(SharedConnectivityActivation.SERVICE_ACTION, values[1].value)
        assertEquals("0x12", values[2].type)
        assertEquals("1", values[2].value)
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
