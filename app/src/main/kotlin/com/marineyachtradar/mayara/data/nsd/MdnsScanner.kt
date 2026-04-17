package com.marineyachtradar.mayara.data.nsd

import com.marineyachtradar.mayara.data.model.DiscoveredServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scans the local network for mayara-server and SignalK instances using mDNS (DNS-SD).
 *
 * ## Testability
 *
 * Rather than coupling directly to [android.net.nsd.NsdManager], this class accepts three
 * injectable functional interfaces:
 *
 * - [startDiscovery]: called with `(serviceType, listener)` to begin scanning.
 * - [stopDiscovery]: called with `(listener)` to stop scanning.
 * - [resolveService]: called with `(serviceInfo, resolveListener)` to resolve a found service
 *                     into a host + port.
 *
 * In production, wire these to the real `NsdManager` methods.  In tests, supply lightweight
 * lambdas that simulate discovery and resolution callbacks.
 *
 * ## Service types scanned
 *
 * - `_signalk-ws._tcp.` — Standard SignalK service advertisement
 * - `_mayara._tcp.` — mayara-server-specific advertisement (if supported in future)
 *
 * @param startDiscovery Lambda wrapping `NsdManager.discoverServices`.
 * @param stopDiscovery Lambda wrapping `NsdManager.stopServiceDiscovery`.
 * @param resolveService Lambda wrapping `NsdManager.resolveService`.
 */
class MdnsScanner(
    private val startDiscovery: (serviceType: String, listener: NsdDiscoveryListener) -> Unit,
    private val stopDiscovery: (listener: NsdDiscoveryListener) -> Unit,
    private val resolveService: (serviceInfo: NsdServiceInfo, listener: NsdResolveListener) -> Unit,
) {

    companion object {
        const val SERVICE_TYPE_SIGNALK = "_signalk-ws._tcp."
        const val SERVICE_TYPE_MAYARA = "_mayara._tcp."
    }

    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())

    /** Current list of discovered servers, updated as services are found or lost. */
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered.asStateFlow()

    private val listeners = mutableListOf<NsdDiscoveryListener>()

    /**
     * Start scanning for both SignalK and Mayara service types.
     * Safe to call multiple times — a second call is a no-op if already scanning.
     */
    fun startScanning() {
        if (listeners.isNotEmpty()) return
        listOf(SERVICE_TYPE_SIGNALK, SERVICE_TYPE_MAYARA).forEach { serviceType ->
            val listener = buildDiscoveryListener(serviceType)
            listeners.add(listener)
            startDiscovery(serviceType, listener)
        }
    }

    /** Stop all active discovery listeners and clear the listener list. */
    fun stopScanning() {
        listeners.forEach { stopDiscovery(it) }
        listeners.clear()
    }

    /** Clear all discovered servers (e.g. when switching connection mode). */
    fun clearDiscovered() {
        _discovered.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun buildDiscoveryListener(serviceType: String) = NsdDiscoveryListener(
        onServiceFound = { info ->
            resolveService(info, NsdResolveListener(
                onServiceResolved = { resolved ->
                    val server = DiscoveredServer(
                        name = resolved.name,
                        host = resolved.host,
                        port = resolved.port,
                        isSignalK = serviceType == SERVICE_TYPE_SIGNALK,
                    )
                    _discovered.value = (_discovered.value + server).distinctBy { it.baseUrl }
                },
                onResolveFailed = { _, _ -> /* silently ignore */ },
            ))
        },
        onServiceLost = { info ->
            _discovered.value = _discovered.value.filter { it.name != info.name }
        },
        onDiscoveryStarted = { /* no-op */ },
        onDiscoveryStopped = { /* no-op */ },
        onStartDiscoveryFailed = { _, _ -> /* no-op */ },
        onStopDiscoveryFailed = { _, _ -> /* no-op */ },
    )
}

// ---------------------------------------------------------------------------
// Lightweight data carriers (testable without android.net.nsd)
// ---------------------------------------------------------------------------

/**
 * Minimal representation of a discovered (but not yet resolved) network service.
 * Mirrors the fields of `android.net.nsd.NsdServiceInfo` needed before resolution.
 */
data class NsdServiceInfo(
    val name: String,
    val serviceType: String,
    val host: String = "",
    val port: Int = 0,
)

/**
 * Functional interface for service discovery callbacks.
 * Mirrors `android.net.nsd.NsdManager.DiscoveryListener`.
 */
data class NsdDiscoveryListener(
    val onServiceFound: (NsdServiceInfo) -> Unit,
    val onServiceLost: (NsdServiceInfo) -> Unit,
    val onDiscoveryStarted: (String) -> Unit,
    val onDiscoveryStopped: (String) -> Unit,
    val onStartDiscoveryFailed: (String, Int) -> Unit,
    val onStopDiscoveryFailed: (String, Int) -> Unit,
)

/**
 * Functional interface for service resolution callbacks.
 * Mirrors `android.net.nsd.NsdManager.ResolveListener`.
 */
data class NsdResolveListener(
    val onServiceResolved: (NsdServiceInfo) -> Unit,
    val onResolveFailed: (NsdServiceInfo, Int) -> Unit,
)
