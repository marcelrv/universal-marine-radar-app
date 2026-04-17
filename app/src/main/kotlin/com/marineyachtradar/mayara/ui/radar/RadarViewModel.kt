package com.marineyachtradar.mayara.ui.radar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.SignalKStreamClient
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.domain.RadarRepository
import com.marineyachtradar.mayara.domain.UnitsPreferences
import com.marineyachtradar.mayara.domain.mayaraDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for [RadarScreen].
 *
 * Creates its own [RadarRepository] using [viewModelScope] so all coroutines are
 * cancelled automatically when the ViewModel is cleared.
 *
 * **Connection modes:**
 * - **Embedded (default)**: The JNI Rust server runs on 127.0.0.1:6502 inside the Android app itself.
 *   This requires Phase 1 (JNI) to be complete and the .so library to load successfully.
 * - **Network (Phase 5)**: User selects a remote mayara-server URL from Settings.
 *
 * **NOTE (Phase 3):** At this stage, the embedded server may not be running yet (Phase 1 JNI
 * integration still in progress). The app will show "Connecting to radar…" and fail with a
 * connection error if the server is unreachable. This is expected until Phase 1 is complete.
 */
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val unitsPreferences = UnitsPreferences(application.mayaraDataStore)

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
        // Restore the persisted palette preference, then connect to the server.
        viewModelScope.launch {
            val savedPalette = try {
                unitsPreferences.colorPalette.first()
            } catch (_: Throwable) {
                ColorPalette.GREEN
            }
            repository.setPalette(savedPalette)
        }
        // Attempt to connect to the embedded JNI server.
        // If the JNI layer hasn't started the server yet, this will fail with a connection error,
        // which [RadarRepository] will surface in [uiState] as [RadarUiState.Error].
        // This is expected until Phase 1 (JNI integration) is fully verified.
        repository.connect(EMBEDDED_BASE_URL)
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
     *
     * [RadarCapabilities.ranges] is ordered ascending (smallest range first), so
     * "zoom in" means decreasing the index.
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
        /**
         * Embedded JNI server address.
         *
         * The Rust server (mayara-jni) runs inside the Android process and listens on
         * 127.0.0.1:6502 (process-local loopback).
         *
         * **On physical device:** 127.0.0.1 = the device itself (not the dev machine).
         * **On emulator:** 10.0.2.2 can be used instead to reach the host machine (not applicable here).
         *
         * This URL is hardcoded for embedded mode. Phase 5 (Settings) will allow users to
         * switch to a remote server URL.
         */
        const val EMBEDDED_BASE_URL = "http://127.0.0.1:6502"
    }
}
