package com.marineyachtradar.mayara.data.api

import com.marineyachtradar.mayara.data.model.SpokeData
import com.marineyachtradar.mayara.proto.RadarMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Connects to the binary spoke WebSocket and emits each decoded [SpokeData] as a [Flow].
 *
 * The WebSocket URL is taken from [RadarInfo.spokeDataUrl], e.g.:
 * `ws://127.0.0.1:6502/signalk/v2/api/vessels/self/radars/{id}/spokes`
 *
 * Binary frames carry a protobuf-encoded [RadarMessage].  Each call to [connect]
 * opens a new WebSocket; cancelling the collecting coroutine closes the connection.
 *
 * @param client OkHttp client (injectable for testing).
 */
class SpokeWebSocketClient(
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // indefinite read — server pushes spokes
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Open a WebSocket to [url] and return a cold [Flow] that emits one [SpokeData]
     * per spoke received.  The flow completes when the server closes the connection
     * and fails with the underlying exception on network error.
     *
     * The WebSocket is closed automatically when the collector's coroutine is cancelled.
     */
    fun connect(url: String): Flow<SpokeData> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val msg = RadarMessage.ADAPTER.decode(bytes.toByteArray())
                    msg.spokes.forEach { spoke ->
                        val spokeData = SpokeData(
                            angle = spoke.angle,
                            bearing = spoke.bearing,
                            rangeMetres = spoke.range,
                            data = spoke.data_.toByteArray(),
                        )
                        // trySend is non-blocking; drops silently if buffer is full (back-pressure)
                        trySend(spokeData)
                    }
                } catch (e: Exception) {
                    // Malformed proto frame — log and skip, keep connection alive
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
}
