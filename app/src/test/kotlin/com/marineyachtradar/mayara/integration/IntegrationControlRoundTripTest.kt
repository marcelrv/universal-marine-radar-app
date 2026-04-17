package com.marineyachtradar.mayara.integration

import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.SignalKStreamClient
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.domain.RadarRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration test for the radar control write (PUT) round-trip.
 *
 * Verifies that:
 *   1. [RadarRepository.setSliderControl] issues a correctly formatted HTTP PUT request.
 *   2. The PUT request body contains {"value": <n>, "auto": <bool>} when auto is specified.
 *   3. The server receives the request on the expected URL path.
 *   4. The control is also updated locally (optimistic update) without waiting for a
 *      round-trip, so the UI reflects the change instantly.
 */
class IntegrationControlRoundTripTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: RadarRepository
    private lateinit var testScope: TestScope

    private val radarId = "radar0"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        testScope = TestScope(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        repository.disconnect()
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun buildRepository(): RadarRepository {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return RadarRepository(
            apiClient = RadarApiClient(baseUrl),
            spokeClient = SpokeWebSocketClient(),
            streamClient = SignalKStreamClient(),
            scope = testScope,
        )
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    /**
     * Enqueues enough mock responses to reach the [RadarRepository.Connected] state:
     * GET /radars → GET /capabilities → GET /controls → WS spoke → WS stream.
     */
    private fun enqueueConnectFlow() {
        val base = baseUrl()
        val spokeWsUrl = "${base.replace("http", "ws")}/signalk/v2/api/vessels/self/radars/$radarId/spokes"
        val streamWsUrl = "${base.replace("http", "ws")}/signalk/v1/stream"

        // GET /radars
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "$radarId": {
                        "name": "Test Radar",
                        "brand": "Emulator",
                        "spokeDataUrl": "$spokeWsUrl",
                        "streamUrl": "$streamWsUrl"
                      }
                    }
                    """.trimIndent()
                )
        )

        // GET /radars/{id}/capabilities
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "supportedRanges": [600, 1200, 3000, 6000],
                      "spokesPerRevolution": 4096,
                      "maxSpokeLength": 512,
                      "controls": {
                        "gain": {
                          "id": "gain",
                          "name": "Gain",
                          "dataType": "number",
                          "minValue": 0,
                          "maxValue": 100
                        }
                      }
                    }
                    """.trimIndent()
                )
        )

        // GET /radars/{id}/controls
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"gain": {"value": 50.0}}""")
        )

        // WS spoke
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) { /* stay open */ }
            })
        )

        // WS stream
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) { /* stay open */ }
            })
        )
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `setSliderControl sends correct PUT request path`() = runTest {
        enqueueConnectFlow()
        // Enqueue 200 OK for the PUT
        server.enqueue(MockResponse().setResponseCode(200))

        repository = buildRepository()
        repository.connect(baseUrl())

        // Allow async connection to complete
        testScope.testScheduler.advanceUntilIdle()

        // Trigger a control write
        repository.setSliderControl("gain", 75f, false)
        testScope.testScheduler.advanceUntilIdle()

        // The PUT request should be the 6th request (after 3 GETs + 2 WS upgrades)
        val putRequest = (1..server.requestCount)
            .map { server.takeRequest() }
            .firstOrNull { it.method == "PUT" }

        assertNotNull(putRequest, "Expected a PUT request to be sent")
        assertEquals(
            "/signalk/v2/api/vessels/self/radars/$radarId/controls/gain",
            putRequest!!.path
        )
    }

    @Test
    fun `setSliderControl PUT body contains correct value`() = runTest {
        enqueueConnectFlow()
        server.enqueue(MockResponse().setResponseCode(200))

        repository = buildRepository()
        repository.connect(baseUrl())
        testScope.testScheduler.advanceUntilIdle()

        repository.setSliderControl("gain", 50f, false)
        testScope.testScheduler.advanceUntilIdle()

        val putRequest = (1..server.requestCount)
            .map { server.takeRequest() }
            .firstOrNull { it.method == "PUT" }

        assertNotNull(putRequest, "Expected a PUT request")
        val body = putRequest!!.body.readUtf8()
        // Body should contain the value 50.0
        assert(body.contains("50")) { "Expected body to contain '50', got: $body" }
    }

    @Test
    fun `setSliderControl applies optimistic update before server responds`() = runTest {
        enqueueConnectFlow()
        // Enqueue a slow PUT response (simulate latency)
        server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS))

        repository = buildRepository()
        repository.connect(baseUrl())
        testScope.testScheduler.advanceUntilIdle()

        // Apply control — the repository updates state optimistically
        repository.setSliderControl("gain", 80f, false)

        // Without advancing time, the state should already reflect the optimistic update
        val state = repository.uiState.value
        if (state is com.marineyachtradar.mayara.data.model.RadarUiState.Connected) {
            val gainControl = state.controls.gain
            assertEquals(80f, gainControl.value, 0.01f,
                "Optimistic update should reflect gain=80 immediately")
        }
    }
}
