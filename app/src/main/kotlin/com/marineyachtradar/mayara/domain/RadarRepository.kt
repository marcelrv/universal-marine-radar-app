package com.marineyachtradar.mayara.domain

import com.marineyachtradar.mayara.data.api.ControlUpdate
import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.SignalKStreamClient
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.ControlsState
import com.marineyachtradar.mayara.data.model.NavigationData
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarInfo
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.data.model.SliderControlState
import com.marineyachtradar.mayara.data.model.SpokeData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Single source of truth for all radar state observed by the Compose UI.
 *
 * The repository orchestrates:
 * 1. REST handshake: GET radars → GET capabilities → GET controls
 * 2. Spoke WebSocket: emits per-spoke data consumed by the GL renderer
 * 3. SignalK stream: updates [RadarUiState.Connected.controls] on each server push
 * 4. Control writes: PUT to server; optimistic update applied immediately
 *
 * Composables must **never** call [RadarApiClient] or any client directly.
 * All mutation flows through this class.
 *
 * @param apiClient REST client for HTTP calls.
 * @param spokeClient WebSocket client for binary spoke stream.
 * @param streamClient WebSocket client for SignalK JSON deltas.
 * @param scope CoroutineScope (should be ViewModelScope in production,
 *              or a test scope in unit tests).
 */
class RadarRepository(
    private val apiClient: RadarApiClient,
    private val spokeClient: SpokeWebSocketClient,
    private val streamClient: SignalKStreamClient,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow<RadarUiState>(RadarUiState.Loading)

    /** Observed by all Compose screens. Starts as [RadarUiState.Loading]. */
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    /** Emits [SpokeData] objects for consumption by the GL renderer. */
    private val _spokeFlow = MutableStateFlow<SpokeData?>(null)
    val spokeFlow: StateFlow<SpokeData?> = _spokeFlow.asStateFlow()

    /** Incremented each time a full revolution is detected (spoke angle wraps). */
    private val _revolutionCount = MutableStateFlow(0L)
    val revolutionCount: StateFlow<Long> = _revolutionCount.asStateFlow()

    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var activeStreamJob: Job? = null
    private var activeSpokeJob: Job? = null
    private var currentRadarId: String? = null

    /** All radars discovered during the last successful `getRadars()` call. */
    private val _availableRadars = MutableStateFlow<List<RadarInfo>>(emptyList())
    val availableRadars: StateFlow<List<RadarInfo>> = _availableRadars.asStateFlow()

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /**
     * Connect to a radar server at [baseUrl] and begin the handshake.
     *
     * Cancels any existing connection before starting the new one.
     * Updates [uiState] to [RadarUiState.Loading] immediately, then [RadarUiState.Connected]
     * on success or [RadarUiState.Error] on failure.
     */
    fun connect(baseUrl: String) {
        apiClient.baseUrl = baseUrl
        disconnect()
        _uiState.value = RadarUiState.Loading
        connectJob = scope.launch {
            try {
                android.util.Log.i("RadarRepository", "Discovering radars at $baseUrl...")

                // Retry the initial HTTP call to handle server startup race conditions.
                // The embedded Rust server may not have its TCP listener ready immediately.
                // Only retry on connection-level errors (e.g. ConnectException), not HTTP errors.
                var radars: List<RadarInfo> = emptyList()
                var lastError: Exception? = null
                for (attempt in 1..5) {
                    try {
                        radars = apiClient.getRadars()
                        lastError = null
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: java.net.ConnectException) {
                        lastError = e
                        android.util.Log.w("RadarRepository",
                            "Connection attempt $attempt failed: ${e.message}")
                        if (attempt < 5) delay(1_000)
                    } catch (e: java.net.SocketException) {
                        lastError = e
                        android.util.Log.w("RadarRepository",
                            "Connection attempt $attempt failed: ${e.message}")
                        if (attempt < 5) delay(1_000)
                    }
                }
                if (lastError != null) throw lastError!!
                if (radars.isEmpty()) {
                    android.util.Log.i("RadarRepository", "No radars found yet at $baseUrl, retrying in 3s...")
                    retryJob = scope.launch {
                        delay(3_000)
                        connect(baseUrl)
                    }
                    return@launch
                }
                android.util.Log.i("RadarRepository", "Found radars: $radars")
                _availableRadars.value = radars
                val radar = radars.first()
                currentRadarId = radar.id

                android.util.Log.i("RadarRepository", "Fetching capabilities/controls for ${radar.id}")
                // 2. Fetch capabilities and current control values
                val capabilities = apiClient.getCapabilities(radar.id)
                val controlValues = apiClient.getControls(radar.id)
                val controlsState = CapabilitiesMapper.buildInitialControlsState(capabilities, controlValues)

                // 3. Derive initial power state from the "power" control value.
                // Default to STANDBY (1) if absent — safer than OFF for already-transmitting radars.
                val powerValue = controlValues["power"]?.value?.toInt() ?: 1
                val powerState = powerValueToState(powerValue)

                // 4. Determine the initial range index
                val currentRangeMetres = controlValues["range"]?.value?.toInt()
                val rangeIndex = capabilities.ranges.indexOfFirst { it == currentRangeMetres }
                    .takeIf { it >= 0 } ?: 0

                _uiState.value = RadarUiState.Connected(
                    radar = radar,
                    capabilities = capabilities,
                    controls = controlsState,
                    powerState = powerState,
                    currentRangeIndex = rangeIndex,
                    navigationData = null,
                )

                // Update the app-wide radar info snapshot for Settings screens
                RadarInfoHolder.update(
                    RadarInfoSnapshot(
                        radarName = radar.name,
                        brand = radar.brand,
                        modelName = controlValues["modelName"]?.stringValue,
                        serialNumber = controlValues["serialNumber"]?.stringValue,
                        firmwareVersion = controlValues["firmwareVersion"]?.stringValue,
                        spokesPerRevolution = capabilities.spokesPerRevolution,
                        maxSpokeLength = capabilities.maxSpokeLength,
                        operatingTimeSeconds = controlValues["operatingTime"]?.value?.takeIf { it > 0 },
                        transmitTimeSeconds = controlValues["transmitTime"]?.value?.takeIf { it > 0 },
                    )
                )

                // 5. Subscribe to spoke stream (drives GL renderer)
                launchSpokeStream(radar)

                // 6. Subscribe to SignalK control updates
                val streamUrl = baseUrl.replace("http://", "ws://")
                    .replace("https://", "wss://") + "/signalk/v1/stream"
                launchControlStream(radar.id, streamUrl)

            } catch (e: CancellationException) {
                throw e  // Always propagate cancellation
            } catch (e: Exception) {
                android.util.Log.e("RadarRepository", "Connection failed", e)
                _uiState.value = RadarUiState.Error(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Switch to a specific radar by [radarId] on the already-connected server.
     * Reuses the current [apiClient.baseUrl].
     */
    fun connectToRadar(radarId: String) {
        val baseUrl = apiClient.baseUrl
        disconnect()
        _uiState.value = RadarUiState.Loading
        connectJob = scope.launch {
            try {
                val radar = _availableRadars.value.firstOrNull { it.id == radarId }
                    ?: run {
                        _uiState.value = RadarUiState.Error("Radar $radarId not found")
                        return@launch
                    }
                currentRadarId = radar.id

                val capabilities = apiClient.getCapabilities(radar.id)
                val controlValues = apiClient.getControls(radar.id)
                val controlsState = CapabilitiesMapper.buildInitialControlsState(capabilities, controlValues)

                val powerValue = controlValues["power"]?.value?.toInt() ?: 1
                val powerState = powerValueToState(powerValue)

                val currentRangeMetres = controlValues["range"]?.value?.toInt()
                val rangeIndex = capabilities.ranges.indexOfFirst { it == currentRangeMetres }
                    .takeIf { it >= 0 } ?: 0

                _uiState.value = RadarUiState.Connected(
                    radar = radar,
                    capabilities = capabilities,
                    controls = controlsState,
                    powerState = powerState,
                    currentRangeIndex = rangeIndex,
                    navigationData = null,
                )

                RadarInfoHolder.update(
                    RadarInfoSnapshot(
                        radarName = radar.name,
                        brand = radar.brand,
                        modelName = controlValues["modelName"]?.stringValue,
                        serialNumber = controlValues["serialNumber"]?.stringValue,
                        firmwareVersion = controlValues["firmwareVersion"]?.stringValue,
                        spokesPerRevolution = capabilities.spokesPerRevolution,
                        maxSpokeLength = capabilities.maxSpokeLength,
                        operatingTimeSeconds = controlValues["operatingTime"]?.value?.takeIf { it > 0 },
                        transmitTimeSeconds = controlValues["transmitTime"]?.value?.takeIf { it > 0 },
                    )
                )

                launchSpokeStream(radar)

                val streamUrl = baseUrl.replace("http://", "ws://")
                    .replace("https://", "wss://") + "/signalk/v1/stream"
                launchControlStream(radar.id, streamUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RadarRepository", "Switch to radar $radarId failed", e)
                _uiState.value = RadarUiState.Error(e.message ?: "Switch failed")
            }
        }
    }

    /** Disconnect all active WebSockets and reset state to [RadarUiState.Loading]. */
    fun disconnect() {
        connectJob?.cancel()
        retryJob?.cancel()
        activeStreamJob?.cancel()
        activeSpokeJob?.cancel()
        connectJob = null
        retryJob = null
        activeStreamJob = null
        activeSpokeJob = null
        currentRadarId = null
        _uiState.value = RadarUiState.Loading
        RadarInfoHolder.clear()
    }

    // ------------------------------------------------------------------
    // Control mutations
    // ------------------------------------------------------------------

    /**
     * Write a slider control value.  Applies an optimistic update immediately,
     * then sends the REST PUT in the background.
     */
    fun setSliderControl(controlId: String, value: Float, auto: Boolean? = null) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        val radarId = state.radar.id

        // Optimistic update
        _uiState.value = state.copy(controls = state.controls.withSlider(controlId, value, auto))

        scope.launch {
            try {
                apiClient.putControl(radarId, controlId, value, auto)
            } catch (_: Exception) {
                // Server-side revert will come via the stream; no local rollback needed
            }
        }
    }

    /**
     * Request a power state change.
     *
     * The server processes the transition asynchronously; the UI state will update
     * when the SignalK stream confirms the new power value.
     */
    fun setPowerState(target: PowerState) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        val numericValue = powerStateToValue(target)

        scope.launch {
            try {
                apiClient.putControl(state.radar.id, "power", numericValue.toFloat())
            } catch (_: Exception) {
                // Ignore; current power state remains unchanged
            }
        }
    }

    /**
     * Step to a new range by index into [RadarCapabilities.ranges].
     */
    fun setRangeIndex(index: Int) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        val ranges = state.capabilities.ranges
        if (index !in ranges.indices) return
        val rangeMetres = ranges[index]

        // Optimistic update
        _uiState.value = state.copy(currentRangeIndex = index)

        scope.launch {
            try {
                apiClient.putControl(state.radar.id, "range", rangeMetres.toFloat())
            } catch (_: Exception) {
                // Revert on stream confirmation
            }
        }
    }

    /** Update the palette without a server call (UI-only preference). */
    fun setPalette(palette: ColorPalette) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        _uiState.value = state.copy(controls = state.controls.copy(palette = palette))
    }

    /** Update the orientation without a server call (UI-only preference). */
    fun setOrientation(orientation: RadarOrientation) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        _uiState.value = state.copy(controls = state.controls.copy(orientation = orientation))
    }

    /**
     * Write an enum (segmented/chip) control value.
     *
     * Applies an optimistic update immediately, then sends the REST PUT in the background.
     * The [index] is stored as a float on the wire (e.g., 0 = Off, 1 = Low, …).
     */
    fun setEnumControl(controlId: String, index: Int) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        val radarId = state.radar.id

        // Optimistic update
        _uiState.value = state.copy(controls = state.controls.withEnum(controlId, index))

        scope.launch {
            try {
                apiClient.putControl(radarId, controlId, index.toFloat())
            } catch (_: Exception) {
                // Server-side revert will arrive via the stream
            }
        }
    }

    /** Update navigation data (heading, SOG, COG) from an external NMEA/SignalK source. */
    fun updateNavigationData(navData: NavigationData?) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        _uiState.value = state.copy(navigationData = navData)
    }

    /** Set the connection label shown in the HUD (e.g. "Embedded (127.0.0.1:6502)"). */
    fun setConnectionLabel(label: String) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        _uiState.value = state.copy(connectionLabel = label)
    }

    // ------------------------------------------------------------------
    // Internal stream launchers
    // ------------------------------------------------------------------

    private fun launchSpokeStream(radar: RadarInfo) {
        var lastRangeUpdateAngle = -1
        activeSpokeJob = spokeClient.connect(radar.spokeDataUrl)
            .onEach { spoke ->
                _spokeFlow.value = spoke
                // Spoke-based range detection: update range if spoke reports a different range.
                // Throttled: only once per full revolution (when angle wraps around).
                if (spoke.rangeMetres > 0 && (lastRangeUpdateAngle < 0 || spoke.angle < lastRangeUpdateAngle)) {
                    lastRangeUpdateAngle = spoke.angle
                    // Signal a new revolution so the GL renderer can clear stale data
                    _revolutionCount.value++
                    val state = _uiState.value as? RadarUiState.Connected ?: return@onEach
                    val received = spoke.rangeMetres
                    val idx = state.capabilities.ranges.indexOfFirst {
                        Math.abs(it - received) <= maxOf(1, (it * 0.05).toInt())
                    }
                    if (idx >= 0 && idx != state.currentRangeIndex) {
                        _uiState.value = state.copy(currentRangeIndex = idx)
                    }
                } else {
                    lastRangeUpdateAngle = spoke.angle
                }
            }
            .catch { /* spoke stream error — silently stop until reconnect */ }
            .launchIn(scope)
    }

    private fun launchControlStream(radarId: String, streamUrl: String) {
        activeStreamJob = streamClient.connect(streamUrl)
            .onEach { update -> applyControlUpdate(radarId, update) }
            .catch { /* stream error — silently stop; UI shows stale data */ }
            .launchIn(scope)
    }

    private fun applyControlUpdate(radarId: String, update: ControlUpdate) {
        if (update.radarId != radarId) return
        val state = _uiState.value as? RadarUiState.Connected ?: return

        when (update.controlId) {
            "power" -> {
                val newPower = powerValueToState(update.value.toInt())
                _uiState.value = state.copy(powerState = newPower)
            }
            "range" -> {
                val received = update.value.toInt()
                val idx = state.capabilities.ranges.indexOfFirst {
                    Math.abs(it - received) <= maxOf(1, (it * 0.05).toInt())
                }
                if (idx >= 0) _uiState.value = state.copy(currentRangeIndex = idx)
            }
            "interferenceRejection" -> {
                _uiState.value = state.copy(
                    controls = state.controls.withEnum(update.controlId, update.value.toInt())
                )
            }
            else -> {
                _uiState.value = state.copy(
                    controls = state.controls.withSlider(update.controlId, update.value, update.auto)
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun powerValueToState(value: Int): PowerState = when (value) {
        0 -> PowerState.OFF
        1 -> PowerState.STANDBY
        2 -> PowerState.TRANSMIT
        else -> {
            android.util.Log.w("RadarRepository",
                "Unknown power value $value, defaulting to STANDBY")
            // TODO: future iterations should derive the mapping from the capabilities
            // schema if radar vendors use non-standard numeric power codes.
            PowerState.STANDBY
        }
    }

    private fun powerStateToValue(state: PowerState): Int = when (state) {
        PowerState.OFF -> 0
        PowerState.STANDBY -> 1
        PowerState.TRANSMIT -> 2
        PowerState.WARMUP -> 2  // transitional state — send TRANSMIT
    }
}

// ------------------------------------------------------------------
// ControlsState extension helpers
// ------------------------------------------------------------------

private fun ControlsState.withSlider(id: String, value: Float, auto: Boolean?): ControlsState {
    val autoVal = auto ?: false
    return when (id) {
        "gain" -> copy(gain = gain.copy(value = value, isAuto = autoVal))
        "sea" -> copy(seaClutter = seaClutter.copy(value = value, isAuto = autoVal))
        "rain" -> copy(rainClutter = rainClutter?.copy(value = value, isAuto = autoVal))
        else -> this
    }
}

private fun ControlsState.withEnum(id: String, index: Int): ControlsState = when (id) {
    "interferenceRejection" -> copy(
        interferenceRejection = interferenceRejection?.copy(selectedIndex = index)
    )
    else -> this
}
