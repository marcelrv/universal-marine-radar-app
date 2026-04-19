package com.marineyachtradar.mayara.ui.radar

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.SignalKStreamClient
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.ConnectionMode
import com.marineyachtradar.mayara.data.model.DistanceUnit
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.domain.ConnectionManager
import com.marineyachtradar.mayara.domain.RadarRepository
import com.marineyachtradar.mayara.domain.UnitsPreferences
import com.marineyachtradar.mayara.domain.mayaraDataStore
import com.marineyachtradar.mayara.jni.RadarJni
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "RadarViewModel"

/**
 * ViewModel for [RadarScreen].
 *
 * Startup logic:
 * 1. Restore colour palette from DataStore.
 * 2. Check [ConnectionManager] for a remembered connection mode.
 * 3. If remembered: auto-connect (start JNI server if Embedded, then HTTP connect).
 * 4. If not remembered: show [ConnectionPickerDialog].
 *
 * **Embedded mode**: starts the Rust server via [RadarJni] on 127.0.0.1:6502
 * then connects the HTTP/WebSocket client to that address.
 * **Network mode**: connects directly to the provided URL.
 *
 * [RadarJni] is loaded lazily — if the JNI library is absent (e.g. x86_64 emulator)
 * the error is caught and the app falls back to showing an error state rather than crashing.
 */
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val unitsPreferences = UnitsPreferences(application.mayaraDataStore)
    private val connectionManager = ConnectionManager(application.mayaraDataStore)

    private val mdnsScanner: com.marineyachtradar.mayara.data.nsd.MdnsScanner = run {
        val nsdManager = application.getSystemService(android.net.nsd.NsdManager::class.java)
        val listenerMap = mutableMapOf<com.marineyachtradar.mayara.data.nsd.NsdDiscoveryListener,
                android.net.nsd.NsdManager.DiscoveryListener>()
        com.marineyachtradar.mayara.data.nsd.MdnsScanner(
            startDiscovery = { serviceType, listener ->
                val nsdListener = object : android.net.nsd.NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) { listener.onDiscoveryStarted(regType) }
                    override fun onDiscoveryStopped(serviceType: String) { listener.onDiscoveryStopped(serviceType) }
                    override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                        listener.onServiceFound(
                            com.marineyachtradar.mayara.data.nsd.NsdServiceInfo(
                                name = serviceInfo.serviceName ?: "",
                                serviceType = serviceInfo.serviceType ?: "",
                            )
                        )
                    }
                    override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                        listener.onServiceLost(
                            com.marineyachtradar.mayara.data.nsd.NsdServiceInfo(
                                name = serviceInfo.serviceName ?: "",
                                serviceType = serviceInfo.serviceType ?: "",
                            )
                        )
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        listener.onStartDiscoveryFailed(serviceType, errorCode)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        listener.onStopDiscoveryFailed(serviceType, errorCode)
                    }
                }
                listenerMap[listener] = nsdListener
                try {
                    nsdManager.discoverServices(serviceType, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdListener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start mDNS discovery for $serviceType", e)
                }
            },
            stopDiscovery = { listener ->
                listenerMap.remove(listener)?.let { nsdListener ->
                    try {
                        nsdManager.stopServiceDiscovery(nsdListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop mDNS discovery", e)
                    }
                }
            },
            resolveService = { serviceInfo, resolveListener ->
                val nsdInfo = android.net.nsd.NsdServiceInfo().apply {
                    serviceName = serviceInfo.name
                    serviceType = serviceInfo.serviceType
                }
                try {
                    nsdManager.resolveService(nsdInfo, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(si: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                            resolveListener.onResolveFailed(serviceInfo, errorCode)
                        }
                        override fun onServiceResolved(si: android.net.nsd.NsdServiceInfo) {
                            resolveListener.onServiceResolved(
                                com.marineyachtradar.mayara.data.nsd.NsdServiceInfo(
                                    name = si.serviceName ?: "",
                                    serviceType = si.serviceType ?: "",
                                    host = si.host?.hostAddress ?: "",
                                    port = si.port,
                                )
                            )
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve mDNS service ${serviceInfo.name}", e)
                }
            },
        )
    }

    private val repository = RadarRepository(
        apiClient = RadarApiClient(EMBEDDED_BASE_URL),
        spokeClient = SpokeWebSocketClient(),
        streamClient = SignalKStreamClient(),
        scope = viewModelScope,
    )

    /** Observed by [RadarScreen]. Never null; starts as [RadarUiState.Loading]. */
    val uiState: StateFlow<RadarUiState> = repository.uiState

    /** Exposed so the GL renderer can subscribe to spoke data. */
    val spokeFlow = repository.spokeFlow

    /** Revolution counter — incremented each full sweep so GL can clear stale data. */
    val revolutionCount = repository.revolutionCount

    /** All radars discovered on the connected server. */
    val availableRadars: StateFlow<List<com.marineyachtradar.mayara.data.model.RadarInfo>> = repository.availableRadars

    /** Controls visibility of the radar picker dialog. */
    private val _showRadarPicker = MutableStateFlow(false)
    val showRadarPicker: StateFlow<Boolean> = _showRadarPicker.asStateFlow()

    /** Controls visibility of the advanced radar control bottom sheet. */
    private val _showControlSheet = MutableStateFlow(false)
    val showControlSheet: StateFlow<Boolean> = _showControlSheet.asStateFlow()

    /** Controls visibility of the connection picker dialog. */
    private val _showConnectionPicker = MutableStateFlow(false)
    val showConnectionPicker: StateFlow<Boolean> = _showConnectionPicker.asStateFlow()

    /** Discovered servers from mDNS scanning. */
    val discoveredServers: StateFlow<List<com.marineyachtradar.mayara.data.model.DiscoveredServer>> = mdnsScanner.discovered
    
    val lastNetworkHost: StateFlow<String> = connectionManager.lastNetworkHost
        .map { it ?: "" }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "")

    val lastNetworkPort: StateFlow<String> = connectionManager.lastNetworkPort
        .map { it ?: "6502" }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "6502")

    /** User's preferred distance unit from settings. */
    val distanceUnit: StateFlow<DistanceUnit> = unitsPreferences.distanceUnit
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, DistanceUnit.NM)

    init {
        viewModelScope.launch {
            // Restore saved colour palette first.
            val savedPalette = try {
                unitsPreferences.colorPalette.first()
            } catch (_: Throwable) {
                ColorPalette.GREEN
            }
            repository.setPalette(savedPalette)

            // Check for a remembered connection; auto-connect if found.
            val remembered = try { connectionManager.rememberedMode.first() } catch (_: Throwable) { null }
            if (remembered != null) {
                connectWithMode(remembered)
            } else {
                _showConnectionPicker.value = true
            }

            // Watch for "Switch Connection" clearing the remembered mode.
            connectionManager.rememberedMode.collect { mode ->
                if (mode == null) {
                    _showConnectionPicker.value = true
                    repository.disconnect()
                }
            }
        }
        
        // Also watch for explicit disconnect events
        viewModelScope.launch {
            var isFirst = true
            connectionManager.forceDisconnectEvent.collect { timestamp ->
                if (isFirst) {
                    isFirst = false
                    return@collect
                }
                _showConnectionPicker.value = true
                repository.disconnect()
            }
        }
    }

    /**
     * Called when the user confirms a connection in [ConnectionPickerDialog].
     *
     * @param mode The chosen [ConnectionMode].
     * @param remember Whether to persist the choice for future launches.
     */
    fun onConnect(mode: ConnectionMode, remember: Boolean) {
        _showConnectionPicker.value = false
        
        if (mode is ConnectionMode.Network) {
            viewModelScope.launch {
                val uri = java.net.URI(mode.baseUrl)
                try { connectionManager.saveLastNetworkLocation(uri.host ?: "", (uri.port.takeIf { it > 0 } ?: 6502).toString()) } catch (_: Throwable) {}
            }
        }

        if (remember) {
            viewModelScope.launch {
                try { connectionManager.rememberMode(mode) } catch (_: Throwable) { /* ignore */ }
            }
        }
        viewModelScope.launch { connectWithMode(mode) }
    }

    /**
     * Start the JNI server (if Embedded) and connect the HTTP/WS client.
     *
     * Gracefully handles [UnsatisfiedLinkError] so the app does not crash on
     * emulators where libradar.so is absent or incompatible.
     */
    private suspend fun connectWithMode(mode: ConnectionMode) {
        when (mode) {
            is ConnectionMode.Embedded -> {
                val started = try {
                    RadarJni.startServer(port = mode.port, emulator = mode.emulator)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "libradar.so not available on this device: ${e.message}")
                    false
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start embedded radar server", e)
                    false
                }
                if (!started) {
                    Log.w(TAG, "Embedded server did not start; connecting anyway (may fail)")
                }
                val url = "http://127.0.0.1:${mode.port}"
                repository.connect(url)
                repository.setConnectionLabel("Embedded (127.0.0.1:${mode.port})")
            }
            is ConnectionMode.Network -> {
                repository.connect(mode.baseUrl)
                repository.setConnectionLabel("Network (${mode.baseUrl.removePrefix("http://").removePrefix("https://")})")
            }
            is ConnectionMode.PcapDemo -> {
                val sanitizedPath = mode.pcapPath
                if (sanitizedPath.contains("..")) {
                    Log.e(TAG, "Rejected pcap path with path traversal: ${mode.pcapPath}")
                    return
                }
                if (!java.io.File(sanitizedPath).exists()) {
                    Log.e(TAG, "Rejected pcap path — file does not exist: ${mode.pcapPath}")
                    return
                }
                val started = try {
                    RadarJni.startServer(port = mode.port, pcapPath = sanitizedPath)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "libradar.so not available on this device: ${e.message}")
                    false
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start embedded radar server (pcap demo)", e)
                    false
                }
                if (!started) {
                    Log.w(TAG, "PCAP demo server did not start; connecting anyway (may fail)")
                }
                val url = "http://127.0.0.1:${mode.port}"
                repository.connect(url)
                repository.setConnectionLabel("PCAP Demo (127.0.0.1:${mode.port})")
            }
        }
    }

    // ------------------------------------------------------------------
    // Bottom sheet visibility
    // ------------------------------------------------------------------

    fun onShowControlSheet() {
        _showControlSheet.value = true
    }

    fun onDismissControlSheet() {
        _showControlSheet.value = false
    }

    fun onShowConnectionPicker() {
        _showConnectionPicker.value = true
        mdnsScanner.startScanning()
    }

    fun onDismissConnectionPicker() {
        _showConnectionPicker.value = false
        mdnsScanner.stopScanning()
    }

    // ------------------------------------------------------------------
    // Radar picker (multi-radar switching)
    // ------------------------------------------------------------------

    /** Called when the user taps the radar name. Shows picker only when >1 radar. */
    fun onRadarNameTapped() {
        if (availableRadars.value.size > 1) {
            _showRadarPicker.value = true
        }
    }

    fun onDismissRadarPicker() {
        _showRadarPicker.value = false
    }

    /** Switch to a different radar on the same server. */
    fun onSwitchRadar(radarId: String) {
        _showRadarPicker.value = false
        // Reconnect to the same base URL; repository will auto-select the given radar
        // For now we just reconnect — the repository picks radars.first().
        // TODO: Pass the desired radarId to the repository so it selects that one.
        viewModelScope.launch {
            val state = uiState.value as? RadarUiState.Connected ?: return@launch
            repository.connectToRadar(radarId)
        }
    }

    // ------------------------------------------------------------------
    // Power control
    // ------------------------------------------------------------------

    fun onPowerAction(target: PowerState) {
        repository.setPowerState(target)
    }

    // ------------------------------------------------------------------
    // Range controls
    // ------------------------------------------------------------------

    /**
     * Zoom in: step to the next smaller range that is a "round" value for the selected unit.
     */
    fun onRangeUp() {
        val state = uiState.value as? RadarUiState.Connected ?: return
        val nextIndex = com.marineyachtradar.mayara.ui.radar.overlay.RangeStepper.findNextRoundRange(
            state.capabilities.ranges, state.currentRangeIndex, -1, distanceUnit.value
        )
        if (nextIndex != state.currentRangeIndex) {
            repository.setRangeIndex(nextIndex)
        }
    }

    /**
     * Zoom out: step to the next larger range that is a "round" value for the selected unit.
     */
    fun onRangeDown() {
        val state = uiState.value as? RadarUiState.Connected ?: return
        val nextIndex = com.marineyachtradar.mayara.ui.radar.overlay.RangeStepper.findNextRoundRange(
            state.capabilities.ranges, state.currentRangeIndex, +1, distanceUnit.value
        )
        if (nextIndex != state.currentRangeIndex) {
            repository.setRangeIndex(nextIndex)
        }
    }

    // ------------------------------------------------------------------
    // Slider controls (Gain, Sea, Rain)
    // ------------------------------------------------------------------

    fun onGainChange(value: Float, isAuto: Boolean) {
        repository.setSliderControl("gain", value, isAuto)
    }

    fun onSeaChange(value: Float, isAuto: Boolean) {
        repository.setSliderControl("sea", value, isAuto)
    }

    fun onRainChange(value: Float) {
        repository.setSliderControl("rain", value, false)
    }

    // ------------------------------------------------------------------
    // Enum controls (Interference Rejection)
    // ------------------------------------------------------------------

    fun onInterferenceChange(index: Int) {
        repository.setEnumControl("interferenceRejection", index)
    }

    // ------------------------------------------------------------------
    // UI-only preferences (no server call)
    // ------------------------------------------------------------------

    fun onPaletteChange(palette: ColorPalette) {
        repository.setPalette(palette)
        viewModelScope.launch {
            try { unitsPreferences.saveColorPalette(palette) } catch (_: Throwable) { /* ignore */ }
        }
    }

    fun onOrientationChange(orientation: RadarOrientation) {
        repository.setOrientation(orientation)
    }

    override fun onCleared() {
        repository.disconnect()
    }

    companion object {
        /** Default embedded JNI server URL (used as the initial apiClient placeholder). */
        const val EMBEDDED_BASE_URL = "http://127.0.0.1:6502"
    }
}
