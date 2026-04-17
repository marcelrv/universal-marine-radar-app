package com.marineyachtradar.mayara.domain

import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.ControlDefinition
import com.marineyachtradar.mayara.data.model.ControlType
import com.marineyachtradar.mayara.data.model.ControlsState
import com.marineyachtradar.mayara.data.model.EnumControlState
import com.marineyachtradar.mayara.data.model.RadarCapabilities
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.SliderControlState
import org.json.JSONObject

/**
 * Maps the raw JSON response from GET .../capabilities into strongly-typed domain models.
 *
 * JSON shape (all fields documented in mayara-server CLIENT.md):
 * ```json
 * {
 *   "maxRange": 192000,
 *   "minRange": 300,
 *   "supportedRanges": [300, 600, ...],
 *   "spokesPerRevolution": 4096,
 *   "maxSpokeLength": 512,
 *   "controls": {
 *     "gain": { "id": "gain", "name": "Gain", "dataType": "number", "minValue": 0, "maxValue": 100 },
 *     "power": { "id": "power", "name": "Power", "dataType": "enum", "values": ["off","standby","transmit"] }
 *   }
 * }
 * ```
 */
object CapabilitiesMapper {

    /**
     * Parse a capabilities JSON string into [RadarCapabilities].
     *
     * @throws org.json.JSONException if the JSON is malformed.
     * @throws IllegalArgumentException if required top-level fields are missing.
     */
    fun parseCapabilities(radarId: String, json: JSONObject): RadarCapabilities {
        val rangesArray = json.getJSONArray("supportedRanges")
        val ranges = (0 until rangesArray.length()).map { rangesArray.getInt(it) }

        val spokesPerRevolution = json.getInt("spokesPerRevolution")
        val maxSpokeLength = json.getInt("maxSpokeLength")

        val controls = mutableMapOf<String, ControlDefinition>()
        val controlsObj = json.optJSONObject("controls") ?: JSONObject()
        controlsObj.keys().forEach { key ->
            val c = controlsObj.getJSONObject(key)
            val def = parseControlDefinition(c)
            controls[key] = def
        }

        return RadarCapabilities(
            radarId = radarId,
            ranges = ranges,
            spokesPerRevolution = spokesPerRevolution,
            maxSpokeLength = maxSpokeLength,
            controls = controls,
        )
    }

    /**
     * Build an initial [ControlsState] from a [RadarCapabilities] and the current controls values.
     *
     * [controlValues] is a map of controlId → { value, auto? } from GET .../controls.
     * Controls not present in capabilities are unsupported and set accordingly.
     */
    fun buildInitialControlsState(
        capabilities: RadarCapabilities,
        controlValues: Map<String, ControlValue>,
    ): ControlsState {
        return ControlsState(
            gain = buildSliderState("gain", capabilities, controlValues),
            seaClutter = buildSliderState("sea", capabilities, controlValues),
            rainClutter = if (capabilities.controls.containsKey("rain"))
                buildSliderState("rain", capabilities, controlValues)
            else null,
            interferenceRejection = buildEnumState("interferenceRejection", capabilities, controlValues),
            palette = ColorPalette.GREEN,
            orientation = RadarOrientation.HEAD_UP,
        )
    }

    private fun buildSliderState(
        id: String,
        capabilities: RadarCapabilities,
        values: Map<String, ControlValue>,
    ): SliderControlState {
        val definition = capabilities.controls[id]
        if (definition == null || definition.type != ControlType.RANGE_SLIDER) {
            return SliderControlState(value = 0f, isAuto = false, isSupported = false)
        }
        val cv = values[id]
        return SliderControlState(
            value = cv?.value ?: 0f,
            isAuto = cv?.auto ?: false,
            isSupported = true,
        )
    }

    private fun buildEnumState(
        id: String,
        capabilities: RadarCapabilities,
        values: Map<String, ControlValue>,
    ): EnumControlState? {
        val definition = capabilities.controls[id] ?: return null
        if (definition.type != ControlType.ENUM) return null
        val cv = values[id]
        return EnumControlState(
            selectedIndex = cv?.value?.toInt() ?: 0,
            options = definition.options,
        )
    }

    private fun parseControlDefinition(json: JSONObject): ControlDefinition {
        val id = json.getString("id")
        val name = json.optString("name", id)
        val dataType = json.optString("dataType", "number")

        val controlType = when (dataType) {
            "enum" -> ControlType.ENUM
            "boolean" -> ControlType.BOOLEAN
            else -> ControlType.RANGE_SLIDER
        }

        val options = mutableListOf<String>()
        val valuesArr = json.optJSONArray("values")
        if (valuesArr != null) {
            for (i in 0 until valuesArr.length()) {
                options.add(valuesArr.getString(i))
            }
        }

        return ControlDefinition(
            id = id,
            name = name,
            type = controlType,
            minValue = if (json.has("minValue")) json.getDouble("minValue").toFloat() else null,
            maxValue = if (json.has("maxValue")) json.getDouble("maxValue").toFloat() else null,
            supportsAuto = json.optBoolean("supportsAuto", false),
            options = options,
        )
    }

    /**
     * Parse the controls values response from GET .../controls.
     *
     * JSON shape: `{ "gain": {"value": 50}, "sea": {"value": 30, "auto": true} }`
     */
    fun parseControlValues(json: JSONObject): Map<String, ControlValue> {
        val result = mutableMapOf<String, ControlValue>()
        json.keys().forEach { key ->
            val obj = json.optJSONObject(key) ?: return@forEach
            result[key] = ControlValue(
                value = obj.optDouble("value", 0.0).toFloat(),
                auto = obj.optBoolean("auto", false),
            )
        }
        return result
    }
}

/** Lightweight holder for a control's current value + auto flag from the server. */
data class ControlValue(
    val value: Float,
    val auto: Boolean = false,
)
