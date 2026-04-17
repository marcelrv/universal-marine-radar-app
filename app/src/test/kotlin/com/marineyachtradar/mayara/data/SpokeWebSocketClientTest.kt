package com.marineyachtradar.mayara.data

import app.cash.turbine.test
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.proto.RadarMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpokeWebSocketClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SpokeWebSocketClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SpokeWebSocketClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `binary frame decoded to correct SpokeData`() = runTest {
        // Encode a RadarMessage with one spoke using Wire
        val spokeData = byteArrayOf(10, 20, 30, 40, 50)
        val spoke = RadarMessage.Spoke(
            angle = 100,
            bearing = 200,
            range = 6000,
            data_ = okio.ByteString.of(*spokeData),
        )
        val msg = RadarMessage(spokes = listOf(spoke))
        val encoded = RadarMessage.ADAPTER.encode(msg)

        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send(encoded.toByteString())
                        webSocket.close(1000, "done")
                    }
                })
        )

        val wsUrl = server.url("/spokes").toString().replace("http", "ws")

        client.connect(wsUrl).test {
            val item = awaitItem()
            assertEquals(100, item.angle)
            assertEquals(200, item.bearing)
            assertEquals(6000, item.rangeMetres)
            assertArrayEquals(spokeData, item.data)
            awaitComplete()
        }
    }

    @Test
    fun `flow emits multiple spokes from one RadarMessage`() = runTest {
        val msg = RadarMessage(
            spokes = listOf(
                RadarMessage.Spoke(angle = 0, range = 1200, data_ = okio.ByteString.of(1, 2)),
                RadarMessage.Spoke(angle = 1, range = 1200, data_ = okio.ByteString.of(3, 4)),
                RadarMessage.Spoke(angle = 2, range = 1200, data_ = okio.ByteString.of(5, 6)),
            )
        )
        val encoded = RadarMessage.ADAPTER.encode(msg)

        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send(encoded.toByteString())
                        webSocket.close(1000, "done")
                    }
                })
        )

        val wsUrl = server.url("/spokes").toString().replace("http", "ws")

        client.connect(wsUrl).test {
            assertEquals(0, awaitItem().angle)
            assertEquals(1, awaitItem().angle)
            assertEquals(2, awaitItem().angle)
            awaitComplete()
        }
    }

    @Test
    fun `server close completes the flow normally`() = runTest {
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.close(1000, "server side close")
                    }
                })
        )

        val wsUrl = server.url("/spokes").toString().replace("http", "ws")

        client.connect(wsUrl).test {
            awaitComplete()
        }
    }

    @Test
    fun `spoke with null bearing is decoded correctly`() = runTest {
        val spoke = RadarMessage.Spoke(
            angle = 50,
            bearing = null,
            range = 3000,
            data_ = okio.ByteString.of(99.toByte()),
        )
        val msg = RadarMessage(spokes = listOf(spoke))
        val encoded = RadarMessage.ADAPTER.encode(msg)

        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send(encoded.toByteString())
                        webSocket.close(1000, "done")
                    }
                })
        )

        val wsUrl = server.url("/spokes").toString().replace("http", "ws")

        client.connect(wsUrl).test {
            val item = awaitItem()
            assertEquals(50, item.angle)
            assertEquals(null, item.bearing)
            awaitComplete()
        }
    }
}
