package com.marineyachtradar.mayara.data.api

import com.marineyachtradar.mayara.data.model.NavigationData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Connects to the SignalK v1 stream WebSocket and emits [StreamUpdate] events.
 *
 * The stream URL is taken from [RadarInfo.streamUrl] or constructed as:
 * `ws://{host}:{port}/signalk/v1/stream`
 *
 * Server → Client JSON delta format:
 * ```json
 * { "updates": [{ "values": [{ "path": "radars.id.controls.cid", "value": 50 }] }] }
 * { "updates": [{ "values": [{ "path": "navigation.headingTrue", "value": 1.5708 }] }] }
 * ```
 *
 * @param client OkHttp client (injectable for testing).
 */
class SignalKStreamClient(
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** Prefix of SignalK paths that carry radar control updates. */
        private const val RADAR_PATH_PREFIX = "radars."
        private const val CONTROLS_SEGMENT = ".controls."

        // SignalK navigation paths (values in SI units: radians, m/s)
        private const val PATH_HEADING_TRUE = "navigation.headingTrue"
        private const val PATH_COG_TRUE = "navigation.courseOverGroundTrue"
        private const val PATH_SOG = "navigation.speedOverGround"
        private const val M_S_TO_KNOTS = 1.94384f
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }

    /**
     * Open a WebSocket to [url] and return a cold [Flow] emitting [StreamUpdate] events —
     * either [StreamUpdate.Control] for radar control changes or [StreamUpdate.Navigation]
     * for navigation sensor updates (heading, COG, SOG).
     *
     * The flow completes when the server closes the connection normally.
     */
    fun connect(url: String): Flow<StreamUpdate> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val controls = parseUpdates(text)
                    controls.forEach { trySend(StreamUpdate.Control(it)) }
                    val nav = parseNavigationUpdate(text)
                    if (nav != null) {
                        trySend(StreamUpdate.Navigation(nav))
                    }
                } catch (_: JSONException) {
                    // Malformed frame — skip silently
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.close(t)
            }
        })

        awaitClose {
            ws.close(1000, "collector cancelled")
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    internal fun parseUpdates(json: String): List<ControlUpdate> {
        val root = JSONObject(json)
        val updates = root.optJSONArray("updates") ?: return emptyList()
        val result = mutableListOf<ControlUpdate>()

        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            val values = update.optJSONArray("values") ?: continue
            for (j in 0 until values.length()) {
                val entry = values.optJSONObject(j) ?: continue
                val path = entry.optString("path") ?: continue
                val parsed = parseRadarControlPath(path) ?: continue

                // The server wraps control values in a BareControlValue object:
                //   { "path": "...", "value": { "value": 50, "auto": false } }
                // Unwrap the nested object; fall back to flat format for compatibility.
                val valueObj = entry.optJSONObject("value")
                val numericValue: Float
                val autoValue: Boolean
                if (valueObj != null) {
                    numericValue = valueObj.optDouble("value", 0.0).toFloat()
                    autoValue = valueObj.optBoolean("auto", false)
                } else {
                    numericValue = entry.optDouble("value", 0.0).toFloat()
                    autoValue = entry.optBoolean("auto", false)
                }

                result.add(
                    ControlUpdate(
                        radarId = parsed.first,
                        controlId = parsed.second,
                        value = numericValue,
                        auto = autoValue,
                    )
                )
            }
        }
        return result
    }

    /**
     * Parse navigation paths from a SignalK delta frame.
     * Returns a [NavigationData] if any navigation values were present, null otherwise.
     * Values are converted from SI units (radians, m/s) to user-friendly units (degrees, knots).
     */
    internal fun parseNavigationUpdate(json: String): NavigationData? {
        val root = JSONObject(json)
        val updates = root.optJSONArray("updates") ?: return null

        var headingDeg: Float? = null
        var cogDeg: Float? = null
        var sogKnots: Float? = null

        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            val values = update.optJSONArray("values") ?: continue
            for (j in 0 until values.length()) {
                val entry = values.optJSONObject(j) ?: continue
                val path = entry.optString("path") ?: continue
                when (path) {
                    PATH_HEADING_TRUE -> {
                        val rad = entry.optDouble("value", Double.NaN)
                        if (!rad.isNaN()) headingDeg = (rad * RAD_TO_DEG).toFloat()
                    }
                    PATH_COG_TRUE -> {
                        val rad = entry.optDouble("value", Double.NaN)
                        if (!rad.isNaN()) cogDeg = (rad * RAD_TO_DEG).toFloat()
                    }
                    PATH_SOG -> {
                        val ms = entry.optDouble("value", Double.NaN)
                        if (!ms.isNaN()) sogKnots = (ms * M_S_TO_KNOTS).toFloat()
                    }
                }
            }
        }

        return if (headingDeg != null || cogDeg != null || sogKnots != null) {
            NavigationData(headingDeg = headingDeg, sogKnots = sogKnots, cogDeg = cogDeg)
        } else {
            null
        }
    }

    /**
     * Parse `radars.{radarId}.controls.{controlId}` → Pair(radarId, controlId), or null if
     * the path doesn't match.
     */
    private fun parseRadarControlPath(path: String): Pair<String, String>? {
        if (!path.startsWith(RADAR_PATH_PREFIX)) return null
        val afterRadars = path.removePrefix(RADAR_PATH_PREFIX)
        val controlsIdx = afterRadars.indexOf(CONTROLS_SEGMENT)
        if (controlsIdx < 0) return null
        val radarId = afterRadars.substring(0, controlsIdx)
        val controlId = afterRadars.substring(controlsIdx + CONTROLS_SEGMENT.length)
        if (radarId.isEmpty() || controlId.isEmpty()) return null
        return radarId to controlId
    }
}

/**
 * A discriminated update emitted by [SignalKStreamClient.connect].
 */
sealed interface StreamUpdate {
    /** A radar control value changed. */
    data class Control(val update: ControlUpdate) : StreamUpdate
    /** Navigation sensor data received (heading, COG, SOG). */
    data class Navigation(val data: NavigationData) : StreamUpdate
}

/**
 * A single control value update received from the SignalK stream.
 *
 * @param radarId The radar whose control changed.
 * @param controlId The control that changed (e.g. "gain", "power").
 * @param value The new numeric value.
 * @param auto Whether the control is now in auto mode.
 */
data class ControlUpdate(
    val radarId: String,
    val controlId: String,
    val value: Float,
    val auto: Boolean = false,
)
