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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    private var activeStreamJob: Job? = null
    private var activeSpokeJob: Job? = null
    private var currentRadarId: String? = null

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
        disconnect()
        _uiState.value = RadarUiState.Loading
        scope.launch {
            try {
                // 1. Discover radars — use the first one
                val radars = apiClient.getRadars()
                if (radars.isEmpty()) {
                    _uiState.value = RadarUiState.Error("No radars found at $baseUrl")
                    return@launch
                }
                val radar = radars.first()
                currentRadarId = radar.id

                // 2. Fetch capabilities and current control values
                val capabilities = apiClient.getCapabilities(radar.id)
                val controlValues = apiClient.getControls(radar.id)
                val controlsState = CapabilitiesMapper.buildInitialControlsState(capabilities, controlValues)

                // 3. Derive initial power state from the "power" control value
                val powerValue = controlValues["power"]?.value?.toInt() ?: 0
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

                // 5. Subscribe to spoke stream (drives GL renderer)
                launchSpokeStream(radar)

                // 6. Subscribe to SignalK control updates
                val streamUrl = baseUrl.replace("http://", "ws://")
                    .replace("https://", "wss://") + "/signalk/v1/stream"
                launchControlStream(radar.id, streamUrl)

            } catch (e: Exception) {
                _uiState.value = RadarUiState.Error(e.message ?: "Connection failed")
            }
        }
    }

    /** Disconnect all active WebSockets and reset state to [RadarUiState.Loading]. */
    fun disconnect() {
        activeStreamJob?.cancel()
        activeSpokeJob?.cancel()
        activeStreamJob = null
        activeSpokeJob = null
        currentRadarId = null
        _uiState.value = RadarUiState.Loading
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

    /** Update navigation data (heading, SOG, COG) from an external NMEA/SignalK source. */
    fun updateNavigationData(navData: NavigationData?) {
        val state = _uiState.value as? RadarUiState.Connected ?: return
        _uiState.value = state.copy(navigationData = navData)
    }

    // ------------------------------------------------------------------
    // Internal stream launchers
    // ------------------------------------------------------------------

    private fun launchSpokeStream(radar: RadarInfo) {
        activeSpokeJob = spokeClient.connect(radar.spokeDataUrl)
            .onEach { _spokeFlow.value = it }
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
                val idx = state.capabilities.ranges.indexOfFirst { it == update.value.toInt() }
                if (idx >= 0) _uiState.value = state.copy(currentRangeIndex = idx)
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
        else -> PowerState.STANDBY
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
