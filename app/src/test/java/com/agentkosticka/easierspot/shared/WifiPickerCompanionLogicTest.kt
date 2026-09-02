package com.agentkosticka.easierspot.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPickerCompanionLogicTest {
    @Test
    fun wifiPickerWindowDetection_acceptsPickerScreensAndRejectsDetails() {
        assertTrue(
            isLikelyWifiPickerWindow(
                "com.android.settings",
                "com.android.settings.Settings\$WifiSettingsActivity"
            )
        )
        assertTrue(
            isLikelyWifiPickerWindow(
                "com.android.settings",
                "com.samsung.android.settings.wifi.WifiSettings"
            )
        )
        assertTrue(
            isLikelyWifiPickerWindow(
                "com.android.settings",
                "com.android.settings.Settings\$NetworkProviderSettingsActivity"
            )
        )
        assertFalse(
            isLikelyWifiPickerWindow(
                "com.android.settings",
                "com.android.settings.Settings\$WifiDetailsSettingsActivity"
            )
        )
        assertFalse(
            isLikelyWifiPickerWindow(
                "com.example.app",
                "com.example.app.WifiSettingsActivity"
            )
        )
    }

    @Test
    fun enabledAccessibilityMerge_preservesOtherServicesAndDeduplicatesTarget() {
        val target = "com.agentkosticka.easierspot/com.agentkosticka.easierspot.shared.EasierSpotWifiPickerAccessibilityService"
        val talkBack = "com.google.android.marvin.talkback/.TalkBackService"
        val merged = mergeEnabledAccessibilityServices(talkBack, target)
        assertEquals("$talkBack:$target", merged)
        assertEquals(merged, mergeEnabledAccessibilityServices(merged, target))
        assertTrue(enabledAccessibilitySettingContains(merged, target))
        assertTrue(
            enabledAccessibilitySettingContains(
                "com.agentkosticka.easierspot/.shared.EasierSpotWifiPickerAccessibilityService",
                target
            )
        )
    }

    @Test
    fun companionConfigured_winsOverVisibleSuggestionFallback() {
        assertEquals(
            SystemWifiPickerState.SUGGESTION_ACTIVE,
            resolveSystemWifiPickerState(
                nativeRemoteEntriesActive = false,
                suggestionApprovalPending = false,
                suggestionApprovalRejected = false,
                trustedNetworkCount = 1,
                pickerSelectableSuggestionCount = 0,
                companionConfigured = true
            )
        )
    }
}
