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

    /** Controls visibility of the advanced radar control bottom sheet. */
    private val _showControlSheet = MutableStateFlow(false)
    val showControlSheet: StateFlow<Boolean> = _showControlSheet.asStateFlow()

    /** Controls visibility of the connection picker dialog. */
    private val _showConnectionPicker = MutableStateFlow(false)
    val showConnectionPicker: StateFlow<Boolean> = _showConnectionPicker.asStateFlow()

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
                // No remembered choice → show the picker.
                _showConnectionPicker.value = true
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
            }
            is ConnectionMode.Network -> {
                repository.connect(mode.baseUrl)
            }
            is ConnectionMode.PcapDemo -> {
                val started = try {
                    RadarJni.startServer(port = mode.port, pcapPath = mode.pcapPath)
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
    }

    fun onDismissConnectionPicker() {
        _showConnectionPicker.value = false
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
     * Zoom in: step to the next smaller range (lower index in capabilities.ranges).
     */
    fun onRangeUp() {
        val state = uiState.value as? RadarUiState.Connected ?: return
        val nextIndex = (state.currentRangeIndex - 1).coerceAtLeast(0)
        if (nextIndex != state.currentRangeIndex) {
            repository.setRangeIndex(nextIndex)
        }
    }

    /**
     * Zoom out: step to the next larger range (higher index in capabilities.ranges).
     */
    fun onRangeDown() {
        val state = uiState.value as? RadarUiState.Connected ?: return
        val nextIndex = (state.currentRangeIndex + 1)
            .coerceAtMost(state.capabilities.ranges.lastIndex)
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
