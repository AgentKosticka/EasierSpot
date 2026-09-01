package com.agentkosticka.easierspot.service

import com.agentkosticka.easierspot.ui.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientConnectionPolicyTest {
    @Test
    fun unknownModeUsesSafeAutomaticDefault() {
        assertEquals(AppPreferences.WifiConnectionMode.AUTO, AppPreferences.WifiConnectionMode.fromValue(null))
        assertEquals(AppPreferences.WifiConnectionMode.AUTO, AppPreferences.WifiConnectionMode.fromValue("legacy"))
        assertEquals(
            AppPreferences.WifiConnectionMode.SHIZUKU_FORCE,
            AppPreferences.WifiConnectionMode.fromValue("shizuku_force")
        )
    }

    @Test
    fun softProgressNeverConsumesTerminalDeadline() {
        assertTrue(ClientConnectionPolicy.HOTSPOT_TOTAL_MS > ClientConnectionPolicy.HOTSPOT_SOFT_MS)
        assertTrue(ClientConnectionPolicy.HOTSPOT_TOTAL_MS <= 25_000L)
        assertTrue(ClientConnectionPolicy.WIFI_CHECKS * ClientConnectionPolicy.WIFI_POLL_MS <= 20_000L)
        assertEquals(45_000L, ClientConnectionPolicy.WHOLE_ATTEMPT_MS)
    }

    @Test
    fun stateCopyExplainsLateAndroidWifiSelection() {
        val (_, text) = ClientConnectionState.JoiningWifi(
            ssid = "EasierSpot",
            method = WifiJoinMethod.SHIZUKU,
            takingLonger = true
        ).titleAndText()
        assertTrue(text.contains("still switching"))
    }

    @Test
    fun rejectedShizukuCommand_isNeverAConnectionVerdict() {
        assertEquals(
            false,
            ClientConnectionPolicy.shouldStartSuggestionFallback(
                AppPreferences.WifiConnectionMode.SHIZUKU_FORCE,
                commandAccepted = false
            )
        )
        assertEquals(
            true,
            ClientConnectionPolicy.shouldStartSuggestionFallback(
                AppPreferences.WifiConnectionMode.AUTO,
                commandAccepted = false
            )
        )
        assertEquals(
            false,
            ClientConnectionPolicy.shouldStartSuggestionFallback(
                AppPreferences.WifiConnectionMode.AUTO,
                commandAccepted = true
            )
        )
    }
}
