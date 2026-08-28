package com.agentkosticka.easierspot.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStatusParserTest {
    @Test
    fun parsesConnectedAndValidatedTarget() {
        val output = """
            Wifi is enabled
            WifiInfo: SSID: "Shared Phone", BSSID: 02:00:00:00:00:00, IP: /192.168.43.10, Supplicant state: COMPLETED
            NetworkCapabilities: Capabilities: INTERNET&TRUSTED&VALIDATED
        """.trimIndent()

        val observation = parseWifiStatus(output).single()

        assertEquals("Shared Phone", observation.ssid)
        assertTrue(observation.connected)
        assertTrue(observation.hasInternetCapability)
        assertTrue(observation.validated)
    }

    @Test
    fun distinguishesDifferentConnectedNetwork() {
        val output = """
            WifiInfo: SSID: "Home Wi-Fi", BSSID: 02:00:00:00:00:01, IP: /192.168.1.12, Supplicant state: COMPLETED
            NetworkCapabilities: Capabilities: INTERNET&TRUSTED&VALIDATED
        """.trimIndent()

        val observation = parseWifiStatus(output).single()

        assertEquals("Home Wi-Fi", observation.ssid)
        assertTrue(observation.connected)
    }

    @Test
    fun handlesMultipleWifiInterfacesAndDisconnectedEntry() {
        val output = """
            ==== ClientModeManager instance: primary ====
            WifiInfo: SSID: "Office", BSSID: 02:00:00:00:00:02, IP: /10.0.0.5, Supplicant state: COMPLETED
            NetworkCapabilities: Capabilities: INTERNET&VALIDATED
            ==== ClientModeManager instance: secondary ====
            WifiInfo: SSID: <unknown ssid>, BSSID: null, IP: /0.0.0.0, Supplicant state: DISCONNECTED
            NetworkCapabilities: null
        """.trimIndent()

        val observations = parseWifiStatus(output)

        assertEquals(2, observations.size)
        assertTrue(observations[0].connected)
        assertFalse(observations[1].connected)
    }

    @Test
    fun rejectsUnrecognizedOutput() {
        assertTrue(parseWifiStatus("Wifi is enabled but no client is connected").isEmpty())
    }
}
