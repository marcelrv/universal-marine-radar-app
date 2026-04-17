package com.marineyachtradar.mayara.data

import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.RadarApiException
import com.marineyachtradar.mayara.data.api.RadarNotFoundException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertThrows as assertThrowsKt

class RadarApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RadarApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = RadarApiClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // getRadars
    // ------------------------------------------------------------------

    @Test
    fun `getRadars parses radar list from JSON object`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "emulator_1034A": {
                        "name": "Emulator Navico Halo 4XR",
                        "brand": "Emulator",
                        "spokeDataUrl": "ws://localhost:6502/signalk/v2/api/vessels/self/radars/emulator_1034A/spokes",
                        "streamUrl": "ws://localhost:6502/signalk/v1/stream",
                        "radarIpAddress": "127.0.0.1"
                      }
                    }
                    """.trimIndent()
                )
        )

        val radars = client.getRadars()

        assertEquals(1, radars.size)
        assertEquals("emulator_1034A", radars[0].id)
        assertEquals("Emulator Navico Halo 4XR", radars[0].name)
        assertEquals("Emulator", radars[0].brand)
        assertEquals(
            "ws://localhost:6502/signalk/v2/api/vessels/self/radars/emulator_1034A/spokes",
            radars[0].spokeDataUrl
        )
    }

    @Test
    fun `getRadars returns empty list when JSON object is empty`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
        )

        val radars = client.getRadars()

        assertEquals(0, radars.size)
    }

    // ------------------------------------------------------------------
    // getCapabilities
    // ------------------------------------------------------------------

    @Test
    fun `getCapabilities parses supportedRanges array`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "maxRange": 192000,
                      "minRange": 300,
                      "supportedRanges": [300, 600, 1200, 3000, 6000, 12000, 24000, 48000, 96000, 192000],
                      "spokesPerRevolution": 4096,
                      "maxSpokeLength": 512,
                      "pixelValues": 256,
                      "hasDoppler": false,
                      "hasDualRadar": true,
                      "hasDualRange": false,
                      "hasSparseSpokes": false,
                      "noTransmitSectors": 2,
                      "controls": {
                        "gain": { "id": "gain", "name": "Gain", "dataType": "number", "minValue": 0, "maxValue": 100 }
                      },
                      "legend": {}
                    }
                    """.trimIndent()
                )
        )

        val caps = client.getCapabilities("emulator_1034A")

        assertEquals(10, caps.ranges.size)
        assertEquals(300, caps.ranges.first())
        assertEquals(192000, caps.ranges.last())
        assertEquals(4096, caps.spokesPerRevolution)
        assertEquals("emulator_1034A", caps.radarId)
        assertEquals(1, caps.controls.size)
    }

    // ------------------------------------------------------------------
    // putControl
    // ------------------------------------------------------------------

    @Test
    fun `putControl sends correct JSON body and returns on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.putControl("radar1", "gain", 75f)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        val body = request.body.readUtf8()
        assert(body.contains("\"value\"")) { "Expected 'value' in body: $body" }
        assert(body.contains("75")) { "Expected '75' in body: $body" }
    }

    @Test
    fun `putControl sends auto flag when provided`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.putControl("radar1", "gain", 0f, auto = true)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        assert(body.contains("\"auto\"")) { "Expected 'auto' in body: $body" }
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    @Test
    fun `getCapabilities throws RadarNotFoundException on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrowsKt<RadarNotFoundException> {
            client.getCapabilities("nonexistent")
        }
    }

    @Test
    fun `getRadars throws RadarApiException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        assertThrowsKt<RadarApiException> {
            client.getRadars()
        }
    }

    @Test
    fun `getControls parses control values including auto flag`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "gain": { "value": 45 },
                      "sea": { "value": 30, "auto": true },
                      "power": { "value": 2 }
                    }
                    """.trimIndent()
                )
        )

        val controls = client.getControls("radar1")

        assertEquals(45f, controls["gain"]!!.value)
        assertEquals(30f, controls["sea"]!!.value)
        assertEquals(true, controls["sea"]!!.auto)
        assertEquals(2f, controls["power"]!!.value)
    }
}
