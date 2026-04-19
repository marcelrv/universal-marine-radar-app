package com.marineyachtradar.mayara.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure JVM tests that verify the [SettingsScreen] route strings are unique, stable,
 * and match the expected values baked into navigation deep-links and tests.
 *
 * No Android dependencies — just string assertions.
 */
class SettingsNavigationTest {

    @Test
    fun `all SettingsScreen routes are unique`() {
        val routes = SettingsScreen.all.map { it.route }
        val unique = routes.toSet()
        assertEquals(unique.size, routes.size, "Duplicate routes found: $routes")
    }

    @Test
    fun `SettingsScreen Home route is settings_home`() {
        assertEquals("settings_home", SettingsScreen.Home.route)
    }

    @Test
    fun `SettingsScreen ConnectionSettings route is connection_settings`() {
        assertEquals("connection_settings", SettingsScreen.ConnectionSettings.route)
    }

    @Test
    fun `SettingsScreen EmbeddedServerLogs route is server_logs`() {
        assertEquals("server_logs", SettingsScreen.EmbeddedServerLogs.route)
    }

    @Test
    fun `SettingsScreen Units route is units`() {
        assertEquals("units", SettingsScreen.Units.route)
    }

    @Test
    fun `SettingsScreen AppInfo route is app_info`() {
        assertEquals("app_info", SettingsScreen.AppInfo.route)
    }

    @Test
    fun `SettingsScreen RadarInfo route is radar_info`() {
        assertEquals("radar_info", SettingsScreen.RadarInfo.route)
    }

    @Test
    fun `SettingsScreen all contains exactly six destinations`() {
        assertEquals(6, SettingsScreen.all.size)
    }

    @Test
    fun `SettingsScreen all contains all expected destinations`() {
        val routes = SettingsScreen.all.map { it.route }.toSet()
        assertTrue("settings_home" in routes)
        assertTrue("connection_settings" in routes)
        assertTrue("server_logs" in routes)
        assertTrue("units" in routes)
        assertTrue("radar_info" in routes)
        assertTrue("app_info" in routes)
    }
}
