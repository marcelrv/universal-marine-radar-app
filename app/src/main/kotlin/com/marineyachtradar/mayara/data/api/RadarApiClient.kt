package com.marineyachtradar.mayara.data.api

import com.marineyachtradar.mayara.data.model.ControlDefinition
import com.marineyachtradar.mayara.data.model.ControlType
import com.marineyachtradar.mayara.data.model.RadarCapabilities
import com.marineyachtradar.mayara.data.model.RadarInfo
import com.marineyachtradar.mayara.domain.CapabilitiesMapper
import com.marineyachtradar.mayara.domain.ControlValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST client for all mayara-server HTTP endpoints.
 *
 * All methods are suspend functions that switch to [Dispatchers.IO] internally,
 * so they are safe to call from any coroutine context.
 *
 * @param baseUrl Base URL of the mayara-server, e.g. "http://127.0.0.1:6502".
 *                Must NOT have a trailing slash.
 * @param client OkHttp client (injectable for testing).
 */
class RadarApiClient(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    /** Mutable so [RadarRepository] can redirect the client when the connection URL changes. */
    var baseUrl: String = baseUrl

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        private const val RADARS_PATH = "/signalk/v2/api/vessels/self/radars"
    }

    /**
     * Fetch the list of available radars.
     *
     * Response is a JSON object keyed by radar ID:
     * `{ "id": { "name", "brand", "spokeDataUrl", "streamUrl" } }`
     *
     * @throws RadarNotFoundException if the server returns 404.
     * @throws RadarApiException for other HTTP or I/O errors.
     */
    suspend fun getRadars(): List<RadarInfo> = withContext(Dispatchers.IO) {
        val json = getJson(RADARS_PATH)
        val radars = mutableListOf<RadarInfo>()
        json.keys().forEach { id ->
            val obj = json.getJSONObject(id)
            radars.add(
                RadarInfo(
                    id = id,
                    name = obj.optString("name", id),
                    brand = obj.optString("brand", "Unknown"),
                    spokeDataUrl = obj.getString("spokeDataUrl"),
                )
            )
        }
        radars
    }

    /**
     * Fetch the capabilities for a specific radar.
     *
     * @throws RadarNotFoundException if the radar ID is unknown (404).
     * @throws RadarApiException for other errors.
     */
    suspend fun getCapabilities(radarId: String): RadarCapabilities = withContext(Dispatchers.IO) {
        val path = "$RADARS_PATH/$radarId/capabilities"
        val json = getJson(path)
        CapabilitiesMapper.parseCapabilities(radarId, json)
    }

    /**
     * Fetch current values for all controls of a radar.
     *
     * @throws RadarNotFoundException if the radar ID is unknown (404).
     * @throws RadarApiException for other errors.
     */
    suspend fun getControls(radarId: String): Map<String, ControlValue> = withContext(Dispatchers.IO) {
        val path = "$RADARS_PATH/$radarId/controls"
        val json = getJson(path)
        CapabilitiesMapper.parseControlValues(json)
    }

    /**
     * Write a control value.
     *
     * @param radarId The radar's ID.
     * @param controlId The control's ID (e.g. "gain", "power").
     * @param value The numeric value to set.
     * @param auto Optional auto mode flag (used for gain/sea controls).
     *
     * @throws RadarNotFoundException if the radar or control ID is unknown (404).
     * @throws RadarApiException for other errors.
     */
    suspend fun putControl(
        radarId: String,
        controlId: String,
        value: Float,
        auto: Boolean? = null,
    ) = withContext(Dispatchers.IO) {
        val path = "$RADARS_PATH/$radarId/controls/$controlId"
        val bodyObj = JSONObject()
        bodyObj.put("value", value.toDouble())
        if (auto != null) bodyObj.put("auto", auto)
        putJson(path, bodyObj.toString())
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun getJson(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw RadarNotFoundException("Not found: $path")
            if (!response.isSuccessful) {
                throw RadarApiException("HTTP ${response.code} for $path")
            }
            val body = response.body?.string()
                ?: throw RadarApiException("Empty response body for $path")
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw RadarApiException("Invalid JSON from $path: ${e.message}", e)
            }
        }
    }

    private fun putJson(path: String, body: String) {
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .put(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw RadarNotFoundException("Not found: $path")
            if (!response.isSuccessful) {
                throw RadarApiException("HTTP ${response.code} for PUT $path")
            }
        }
    }
}

/** Thrown when a radar or control resource returns HTTP 404. */
class RadarNotFoundException(message: String) : IOException(message)

/** Thrown for any other non-2xx response or network failure. */
class RadarApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
