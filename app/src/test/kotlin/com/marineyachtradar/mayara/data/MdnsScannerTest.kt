package com.marineyachtradar.mayara.data

import com.marineyachtradar.mayara.data.nsd.MdnsScanner
import com.marineyachtradar.mayara.data.nsd.NsdDiscoveryListener
import com.marineyachtradar.mayara.data.nsd.NsdResolveListener
import com.marineyachtradar.mayara.data.nsd.NsdServiceInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MdnsScannerTest {

    /**
     * Build a [MdnsScanner] with controllable lambdas.
     *
     * [capturedListeners] accumulates every discovery listener registered.
     * [resolveImmediately] controls whether resolve calls succeed instantly with a fixed host/port.
     */
    private fun buildScanner(
        capturedListeners: MutableList<Pair<String, NsdDiscoveryListener>> = mutableListOf(),
        resolveResult: NsdServiceInfo? = null,
    ): MdnsScanner {
        return MdnsScanner(
            startDiscovery = { type, listener -> capturedListeners.add(type to listener) },
            stopDiscovery = { _ -> },
            resolveService = { info, resolveListener ->
                if (resolveResult != null) {
                    resolveListener.onServiceResolved(resolveResult)
                } else {
                    resolveListener.onResolveFailed(info, 0)
                }
            },
        )
    }

    @Test
    fun `startScanning registers listeners for both service types`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val scanner = buildScanner(capturedListeners = captured)

        scanner.startScanning()

        val types = captured.map { it.first }.toSet()
        assertTrue(MdnsScanner.SERVICE_TYPE_SIGNALK in types)
        assertTrue(MdnsScanner.SERVICE_TYPE_MAYARA in types)
    }

    @Test
    fun `service found emits DiscoveredServer`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val resolvedInfo = NsdServiceInfo(
            name = "Navico Halo",
            serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK,
            host = "192.168.1.100",
            port = 3000,
        )
        val scanner = buildScanner(capturedListeners = captured, resolveResult = resolvedInfo)

        scanner.startScanning()

        // Simulate a service found event on the first listener
        val (_, listener) = captured.first()
        listener.onServiceFound(
            NsdServiceInfo(name = "Navico Halo", serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK)
        )

        val servers = scanner.discovered.value
        assertEquals(1, servers.size)
        assertEquals("Navico Halo", servers[0].name)
        assertEquals("192.168.1.100", servers[0].host)
        assertEquals(3000, servers[0].port)
    }

    @Test
    fun `service lost removes server from discovered list`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val resolvedInfo = NsdServiceInfo(
            name = "Navico Halo",
            serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK,
            host = "192.168.1.100",
            port = 3000,
        )
        val scanner = buildScanner(capturedListeners = captured, resolveResult = resolvedInfo)

        scanner.startScanning()
        val (_, listener) = captured.first()

        // Add the service
        listener.onServiceFound(
            NsdServiceInfo(name = "Navico Halo", serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK)
        )
        assertEquals(1, scanner.discovered.value.size)

        // Remove it
        listener.onServiceLost(
            NsdServiceInfo(name = "Navico Halo", serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK)
        )
        assertEquals(0, scanner.discovered.value.size)
    }

    @Test
    fun `duplicate discoveries do not add the same server twice`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val resolvedInfo = NsdServiceInfo(
            name = "SignalK Server",
            serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK,
            host = "10.0.0.1",
            port = 3000,
        )
        val scanner = buildScanner(capturedListeners = captured, resolveResult = resolvedInfo)

        scanner.startScanning()
        val (_, listener) = captured.first()

        val serviceInfo = NsdServiceInfo(
            name = "SignalK Server",
            serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK
        )
        listener.onServiceFound(serviceInfo)
        listener.onServiceFound(serviceInfo)  // duplicate

        assertEquals(1, scanner.discovered.value.size)
    }

    @Test
    fun `startScanning second call is a no-op`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val scanner = buildScanner(capturedListeners = captured)

        scanner.startScanning()
        val firstCount = captured.size

        scanner.startScanning()  // second call
        assertEquals(firstCount, captured.size)
    }

    @Test
    fun `clearDiscovered empties the list`() = runTest {
        val captured = mutableListOf<Pair<String, NsdDiscoveryListener>>()
        val resolvedInfo = NsdServiceInfo(
            name = "MyRadar",
            serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK,
            host = "192.168.0.5",
            port = 3001,
        )
        val scanner = buildScanner(capturedListeners = captured, resolveResult = resolvedInfo)
        scanner.startScanning()
        val (_, listener) = captured.first()
        listener.onServiceFound(NsdServiceInfo(name = "MyRadar", serviceType = MdnsScanner.SERVICE_TYPE_SIGNALK))
        assertEquals(1, scanner.discovered.value.size)

        scanner.clearDiscovered()

        assertEquals(0, scanner.discovered.value.size)
    }
}
