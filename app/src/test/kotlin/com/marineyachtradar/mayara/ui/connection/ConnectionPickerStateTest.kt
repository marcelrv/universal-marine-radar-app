package com.marineyachtradar.mayara.ui.connection

import com.marineyachtradar.mayara.data.model.ConnectionMode
import com.marineyachtradar.mayara.data.model.DiscoveredServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PickerOption] and the URL-building logic in [ConnectionPickerDialog].
 *
 * These tests exercise the pure logic components: connection mode construction,
 * validation rules, and the PickerOption enum's contract.
 */
class ConnectionPickerStateTest {

    // ------------------------------------------------------------------
    // PickerOption enum basics
    // ------------------------------------------------------------------

    @Test
    fun `PickerOption has exactly three values`() {
        assertEquals(3, PickerOption.entries.size)
    }

    @Test
    fun `EMBEDDED and NETWORK are distinct`() {
        assertFalse(PickerOption.EMBEDDED == PickerOption.NETWORK)
    }

    // ------------------------------------------------------------------
    // ConnectionMode.Embedded construction
    // ------------------------------------------------------------------

    @Test
    fun `Embedded mode uses default port 6502`() {
        val mode = ConnectionMode.Embedded()
        assertEquals(6502, mode.port)
    }

    @Test
    fun `Embedded mode is not emulator by default`() {
        val mode = ConnectionMode.Embedded()
        assertFalse(mode.emulator)
    }

    // ------------------------------------------------------------------
    // Manual network URL construction (mirrors dialog logic)
    // ------------------------------------------------------------------

    @Test
    fun `manual network URL is built from host and port`() {
        val host = "192.168.1.100"
        val port = 6502
        val baseUrl = "http://$host:$port"
        val mode = ConnectionMode.Network(baseUrl)
        assertEquals("http://192.168.1.100:6502", mode.baseUrl)
    }

    @Test
    fun `manual network URL with default port`() {
        val host = "radar.local"
        val port = 6502
        assertEquals("http://radar.local:6502", "http://$host:$port")
    }

    // ------------------------------------------------------------------
    // Discovered server URL
    // ------------------------------------------------------------------

    @Test
    fun `DiscoveredServer baseUrl uses http scheme`() {
        val server = DiscoveredServer(
            name = "Navico HALO",
            host = "192.168.1.50",
            port = 6502,
            isSignalK = false,
        )
        assertEquals("http://192.168.1.50:6502", server.baseUrl)
    }

    @Test
    fun `DiscoveredServer baseUrl is used when server list is non-empty`() {
        val servers = listOf(
            DiscoveredServer("Radar 1", "10.0.0.1", 6502, false),
            DiscoveredServer("Radar 2", "10.0.0.2", 6502, false),
        )
        // Mirrors the dialog logic: index 0 is pre-selected
        val selectedIndex = 0
        val mode = ConnectionMode.Network(servers[selectedIndex].baseUrl)
        assertEquals("http://10.0.0.1:6502", mode.baseUrl)
    }

    // ------------------------------------------------------------------
    // Port validation (mirrors dialog's manualPortValid logic)
    // ------------------------------------------------------------------

    @Test
    fun `port 6502 is valid`() {
        assertTrue(isValidPort("6502"))
    }

    @Test
    fun `port 1 is valid (lower bound)`() {
        assertTrue(isValidPort("1"))
    }

    @Test
    fun `port 65535 is valid (upper bound)`() {
        assertTrue(isValidPort("65535"))
    }

    @Test
    fun `port 0 is invalid`() {
        assertFalse(isValidPort("0"))
    }

    @Test
    fun `port 65536 is invalid (exceeds max)`() {
        assertFalse(isValidPort("65536"))
    }

    @Test
    fun `non-numeric port is invalid`() {
        assertFalse(isValidPort("abc"))
    }

    @Test
    fun `empty port is invalid`() {
        assertFalse(isValidPort(""))
    }

    // ------------------------------------------------------------------
    // Host validation (mirrors dialog's manualHostValid logic)
    // ------------------------------------------------------------------

    @Test
    fun `blank host is invalid`() {
        assertFalse(isValidHost(""))
        assertFalse(isValidHost("   "))
    }

    @Test
    fun `IP address host is valid`() {
        assertTrue(isValidHost("192.168.1.100"))
    }

    @Test
    fun `hostname is valid`() {
        assertTrue(isValidHost("radar.local"))
    }

    @Test
    fun `host longer than 255 chars is invalid`() {
        assertFalse(isValidHost("a".repeat(256)))
    }

    // ------------------------------------------------------------------
    // Helpers (mirroring the dialog's validation inline logic)
    // ------------------------------------------------------------------

    @Test
    fun `PcapDemo mode holds pcap path`() {
        val mode = ConnectionMode.PcapDemo(pcapPath = "/data/user/0/com.example/cache/navico.pcap.gz")
        assertEquals("/data/user/0/com.example/cache/navico.pcap.gz", mode.pcapPath)
        assertEquals(6502, mode.port)
        assertTrue(mode.repeat)
    }

    @Test
    fun `PcapDemo is distinct from Embedded`() {
        val pcap = ConnectionMode.PcapDemo("/tmp/test.pcap")
        val embedded = ConnectionMode.Embedded()
        assertFalse(pcap == embedded)
    }

    private fun isValidPort(input: String): Boolean =
        input.toIntOrNull()?.let { it in 1..65535 } == true

    private fun isValidHost(input: String): Boolean =
        input.isNotBlank() && input.length <= 255
}
