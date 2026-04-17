package com.marineyachtradar.mayara.performance

import com.marineyachtradar.mayara.data.model.SpokeData
import com.marineyachtradar.mayara.domain.CapabilitiesMapper
import com.marineyachtradar.mayara.proto.RadarMessage
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM micro-benchmarks that verify key data-path operations stay within
 * performance budgets.
 *
 * These tests use warm-up iterations and measure median wall-clock time to
 * account for JIT compilation.  They run on JVM (not on device), so timings
 * do NOT reflect Android hardware directly — but they catch regressions in
 * algorithmic complexity.
 *
 * Performance budgets:
 *  - Capabilities JSON parsing: < 5 ms mean over 100 iterations (cold)
 *  - Protobuf RadarMessage decode: < 1 ms mean over 1 000 iterations (warm)
 *  - SpokeData creation (512 byte copy): < 0.1 ms mean over 5 000 iterations
 */
class PerformanceBenchmarkTest {

    // ------------------------------------------------------------------
    // Test data
    // ------------------------------------------------------------------

    private val capabilitiesJson = JSONObject(
        """
        {
          "supportedRanges": [300, 600, 1200, 3000, 6000, 12000, 24000, 48000],
          "spokesPerRevolution": 4096,
          "maxSpokeLength": 512,
          "controls": {
            "gain":                { "id": "gain",                "name": "Gain",                "dataType": "number", "minValue": 0,  "maxValue": 100 },
            "sea":                 { "id": "sea",                 "name": "Sea",                 "dataType": "number", "minValue": 0,  "maxValue": 100 },
            "rain":                { "id": "rain",                "name": "Rain",                "dataType": "number", "minValue": 0,  "maxValue": 100 },
            "interferenceRejection": { "id": "interferenceRejection", "name": "IR", "dataType": "enum",   "values": ["off","low","medium","high"] },
            "power":               { "id": "power",               "name": "Power",               "dataType": "enum",   "values": ["off","standby","transmit"] }
          }
        }
        """.trimIndent()
    )

    /** A minimal Wire-encoded RadarMessage with a single 512-byte spoke. */
    private val encodedSpokeBytes: ByteArray by lazy {
        val spoke = RadarMessage.Spoke(
            angle = 100,
            range = 6000,
            bearing = 100,
            data_ = ByteArray(512) { it.toByte() }.toByteString(),
        )
        val message = RadarMessage(spokes = listOf(spoke))
        RadarMessage.ADAPTER.encode(message)
    }

    // ------------------------------------------------------------------
    // Benchmarks
    // ------------------------------------------------------------------

    @Test
    fun `capabilities JSON parsing completes under 5ms mean over 100 iterations`() {
        // Warm up
        repeat(10) { CapabilitiesMapper.parseCapabilities("radar0", capabilitiesJson) }

        val times = LongArray(100) {
            val start = System.nanoTime()
            CapabilitiesMapper.parseCapabilities("radar0", capabilitiesJson)
            System.nanoTime() - start
        }

        val meanNs = times.average()
        val meanMs = meanNs / 1_000_000.0
        assertTrue(meanMs < 5.0) {
            "Capabilities JSON parse too slow: mean=${"%.3f".format(meanMs)}ms (budget: <5ms)"
        }
    }

    @Test
    fun `protobuf RadarMessage decode completes under 1ms mean over 1000 iterations`() {
        // Warm up
        repeat(50) { RadarMessage.ADAPTER.decode(encodedSpokeBytes) }

        val times = LongArray(1000) {
            val start = System.nanoTime()
            RadarMessage.ADAPTER.decode(encodedSpokeBytes)
            System.nanoTime() - start
        }

        val meanNs = times.average()
        val meanMs = meanNs / 1_000_000.0
        assertTrue(meanMs < 1.0) {
            "Protobuf decode too slow: mean=${"%.3f".format(meanMs)}ms (budget: <1ms)"
        }
    }

    @Test
    fun `SpokeData creation with 512-byte payload under 0_1ms mean over 5000 iterations`() {
        val rawData = ByteArray(512) { it.toByte() }

        // Warm up
        repeat(100) {
            SpokeData(
                angle = it % 4096,
                bearing = it % 4096,
                rangeMetres = 6000,
                data = rawData.copyOf(),
            )
        }

        val times = LongArray(5000) {
            val start = System.nanoTime()
            SpokeData(
                angle = it % 4096,
                bearing = it % 4096,
                rangeMetres = 6000,
                data = rawData.copyOf(),
            )
            System.nanoTime() - start
        }

        val meanNs = times.average()
        val meanMs = meanNs / 1_000_000.0
        assertTrue(meanMs < 0.1) {
            "SpokeData creation too slow: mean=${"%.4f".format(meanMs)}ms (budget: <0.1ms)"
        }
    }
}
