package com.marineyachtradar.mayara.domain

import com.marineyachtradar.mayara.data.model.ControlType
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilitiesMapperTest {

    // ------------------------------------------------------------------
    // parseCapabilities
    // ------------------------------------------------------------------

    @Test
    fun `parseCapabilities parses supportedRanges correctly`() {
        val json = JSONObject(
            """
            {
              "maxRange": 192000,
              "minRange": 300,
              "supportedRanges": [300, 600, 1200, 3000, 6000, 12000],
              "spokesPerRevolution": 4096,
              "maxSpokeLength": 512,
              "controls": {}
            }
            """.trimIndent()
        )

        val caps = CapabilitiesMapper.parseCapabilities("radar1", json)

        assertEquals(listOf(300, 600, 1200, 3000, 6000, 12000), caps.ranges)
        assertEquals(4096, caps.spokesPerRevolution)
        assertEquals(512, caps.maxSpokeLength)
        assertEquals("radar1", caps.radarId)
    }

    @Test
    fun `parseCapabilities maps number dataType to RANGE_SLIDER`() {
        val json = capabilitiesJson(
            controls = """
            "gain": { "id": "gain", "name": "Gain", "dataType": "number", "minValue": 0, "maxValue": 100 }
            """
        )

        val caps = CapabilitiesMapper.parseCapabilities("r1", json)

        val gainDef = caps.controls["gain"]
        assertNotNull(gainDef)
        assertEquals(ControlType.RANGE_SLIDER, gainDef!!.type)
        assertEquals(0f, gainDef.minValue)
        assertEquals(100f, gainDef.maxValue)
    }

    @Test
    fun `parseCapabilities maps enum dataType to ENUM with options`() {
        val json = capabilitiesJson(
            controls = """
            "power": { "id": "power", "name": "Power", "dataType": "enum", "values": ["off","standby","transmit"] }
            """
        )

        val caps = CapabilitiesMapper.parseCapabilities("r1", json)

        val powerDef = caps.controls["power"]
        assertNotNull(powerDef)
        assertEquals(ControlType.ENUM, powerDef!!.type)
        assertEquals(listOf("off", "standby", "transmit"), powerDef.options)
    }

    @Test
    fun `parseCapabilities handles missing controls object gracefully`() {
        val json = JSONObject(
            """
            {
              "maxRange": 12000,
              "minRange": 300,
              "supportedRanges": [300],
              "spokesPerRevolution": 2048,
              "maxSpokeLength": 256
            }
            """.trimIndent()
        )

        val caps = CapabilitiesMapper.parseCapabilities("r1", json)

        assertTrue(caps.controls.isEmpty())
    }

    // ------------------------------------------------------------------
    // buildInitialControlsState
    // ------------------------------------------------------------------

    @Test
    fun `buildInitialControlsState sets rainClutter to null when rain not in capabilities`() {
        val caps = minimalCapabilitiesWithControls(
            controlIds = listOf("gain", "sea")  // no "rain"
        )
        val values = mapOf(
            "gain" to ControlValue(50f),
            "sea" to ControlValue(20f),
        )

        val state = CapabilitiesMapper.buildInitialControlsState(caps, values)

        assertNull(state.rainClutter)
        assertTrue(state.gain.isSupported)
        assertTrue(state.seaClutter.isSupported)
    }

    @Test
    fun `buildInitialControlsState sets rainClutter when rain is in capabilities`() {
        val caps = minimalCapabilitiesWithControls(
            controlIds = listOf("gain", "sea", "rain")
        )
        val values = mapOf(
            "gain" to ControlValue(50f),
            "sea" to ControlValue(10f),
            "rain" to ControlValue(5f),
        )

        val state = CapabilitiesMapper.buildInitialControlsState(caps, values)

        assertNotNull(state.rainClutter)
        assertEquals(5f, state.rainClutter!!.value)
        assertTrue(state.rainClutter!!.isSupported)
    }

    @Test
    fun `buildInitialControlsState sets isAuto when server reports auto mode`() {
        val caps = minimalCapabilitiesWithControls(listOf("gain", "sea"))
        val values = mapOf(
            "gain" to ControlValue(50f, auto = true),
            "sea" to ControlValue(0f, auto = false),
        )

        val state = CapabilitiesMapper.buildInitialControlsState(caps, values)

        assertTrue(state.gain.isAuto)
        assertFalse(state.seaClutter.isAuto)
    }

    @Test
    fun `buildInitialControlsState marks unsupported control when missing from capabilities`() {
        // capabilities has no "gain" control
        val caps = minimalCapabilitiesWithControls(listOf("sea"))
        val values = emptyMap<String, ControlValue>()

        val state = CapabilitiesMapper.buildInitialControlsState(caps, values)

        assertFalse(state.gain.isSupported)
    }

    // ------------------------------------------------------------------
    // parseControlValues
    // ------------------------------------------------------------------

    @Test
    fun `parseControlValues parses value and auto fields`() {
        val json = JSONObject(
            """
            {
              "gain": { "value": 75 },
              "sea": { "value": 30, "auto": true },
              "power": { "value": 2 }
            }
            """.trimIndent()
        )

        val values = CapabilitiesMapper.parseControlValues(json)

        assertEquals(75f, values["gain"]!!.value)
        assertFalse(values["gain"]!!.auto)
        assertEquals(30f, values["sea"]!!.value)
        assertTrue(values["sea"]!!.auto)
        assertEquals(2f, values["power"]!!.value)
    }

    @Test
    fun `parseControlValues returns empty map for empty object`() {
        val values = CapabilitiesMapper.parseControlValues(JSONObject("{}"))
        assertTrue(values.isEmpty())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun capabilitiesJson(controls: String): JSONObject = JSONObject(
        """
        {
          "maxRange": 192000,
          "minRange": 300,
          "supportedRanges": [300, 600, 1200],
          "spokesPerRevolution": 4096,
          "maxSpokeLength": 512,
          "controls": { $controls }
        }
        """.trimIndent()
    )

    private fun minimalCapabilitiesWithControls(controlIds: List<String>): com.marineyachtradar.mayara.data.model.RadarCapabilities {
        val controls = controlIds.associateWith { id ->
            com.marineyachtradar.mayara.data.model.ControlDefinition(
                id = id,
                name = id.replaceFirstChar { it.uppercase() },
                type = ControlType.RANGE_SLIDER,
                minValue = 0f,
                maxValue = 100f,
            )
        }
        return com.marineyachtradar.mayara.data.model.RadarCapabilities(
            radarId = "r1",
            ranges = listOf(300, 1200, 6000),
            spokesPerRevolution = 4096,
            maxSpokeLength = 512,
            controls = controls,
        )
    }
}
