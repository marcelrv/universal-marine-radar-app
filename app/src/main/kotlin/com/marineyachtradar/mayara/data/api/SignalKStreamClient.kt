package com.marineyachtradar.mayara.data.api

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
 * Connects to the SignalK v1 stream WebSocket and emits [ControlUpdate] events.
 *
 * The stream URL is taken from [RadarInfo.streamUrl] or constructed as:
 * `ws://{host}:{port}/signalk/v1/stream`
 *
 * Server → Client JSON delta format:
 * ```json
 * { "updates": [{ "values": [{ "path": "radars.id.controls.cid", "value": 50 }] }] }
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
    }

    /**
     * Open a WebSocket to [url] and return a cold [Flow] emitting one [ControlUpdate]
     * per value received in the `updates[].values[]` array.
     *
     * Paths that are not radar control paths are silently skipped.
     * The flow completes when the server closes the connection normally.
     */
    fun connect(url: String): Flow<ControlUpdate> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    parseUpdates(text).forEach { trySend(it) }
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
                result.add(
                    ControlUpdate(
                        radarId = parsed.first,
                        controlId = parsed.second,
                        value = entry.optDouble("value", 0.0).toFloat(),
                        auto = entry.optBoolean("auto", false),
                    )
                )
            }
        }
        return result
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
