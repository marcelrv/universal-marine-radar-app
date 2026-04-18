package com.marineyachtradar.mayara.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marineyachtradar.mayara.BuildConfig
import com.marineyachtradar.mayara.data.model.BearingMode
import com.marineyachtradar.mayara.data.model.ConnectionMode
import com.marineyachtradar.mayara.data.model.DistanceUnit
import com.marineyachtradar.mayara.domain.ConnectionManager
import com.marineyachtradar.mayara.domain.UnitsPreferences
import com.marineyachtradar.mayara.domain.mayaraDataStore
import com.marineyachtradar.mayara.jni.RadarJni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for [SettingsActivity].
 *
 * Extends [AndroidViewModel] to access the [Application] context required to obtain the
 * [com.marineyachtradar.mayara.domain.mayaraDataStore] singleton. No DI framework is needed.
 *
 * Exposed [StateFlow]s are observed by the settings screens and survive configuration changes.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.mayaraDataStore
    private val connectionManager = ConnectionManager(dataStore)
    private val unitsPreferences = UnitsPreferences(dataStore)

    // ------------------------------------------------------------------
    // App version (compile-time constant from BuildConfig)
    // ------------------------------------------------------------------

    /** App version string from [BuildConfig.VERSION_NAME] (e.g. "0.1.0"). */
    val appVersion: String = BuildConfig.VERSION_NAME

    // ------------------------------------------------------------------
    // Connection state
    // ------------------------------------------------------------------

    /**
     * Current connection settings state, derived from [ConnectionManager.rememberedMode].
     * Defaults to an empty [ConnectionSettingsState] while the DataStore is loading.
     */
    val connectionState: StateFlow<ConnectionSettingsState> =
        connectionManager.rememberedMode
            .map { mode ->
                when (mode) {
                    is ConnectionMode.Embedded -> ConnectionSettingsState(
                        activeMode = mode,
                        displayLabel = "Embedded (127.0.0.1:${mode.port})",
                    )
                    is ConnectionMode.Network -> {
                        val uri = java.net.URI(mode.baseUrl)
                        val host = uri.host ?: ""
                        val port = (uri.port.takeIf { it > 0 } ?: 6502).toString()
                        ConnectionSettingsState(
                            activeMode = mode,
                            displayLabel = "Network: $host:$port",
                            rememberedHost = host,
                            rememberedPort = port,
                        )
                    }
                    is ConnectionMode.PcapDemo -> ConnectionSettingsState(
                        activeMode = mode,
                        displayLabel = "PCAP Demo: ${java.io.File(mode.pcapPath).name}",
                    )
                    null -> ConnectionSettingsState()
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionSettingsState(),
            )

    // ------------------------------------------------------------------
    // Units preferences
    // ------------------------------------------------------------------

    val distanceUnit: StateFlow<DistanceUnit> = unitsPreferences.distanceUnit
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DistanceUnit.NM,
        )

    val bearingMode: StateFlow<BearingMode> = unitsPreferences.bearingMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BearingMode.TRUE,
        )

    // ------------------------------------------------------------------
    // Server logs
    // ------------------------------------------------------------------

    private val _serverLogs = MutableStateFlow("")
    val serverLogs: StateFlow<String> = _serverLogs.asStateFlow()

    /**
     * Fetch the latest server logs from [RadarJni] on the IO dispatcher.
     *
     * Wrapped in [try/catch][Throwable] because [RadarJni] loads `libradar.so` in its `init`
     * block — if the library is absent (e.g. debug builds without the .so), accessing `RadarJni`
     * will throw [UnsatisfiedLinkError] at class initialisation.
     */
    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = try {
                RadarJni.getLogs()
            } catch (t: Throwable) {
                "[Embedded server not loaded — libradar.so unavailable]\n${t.message}"
            }
            _serverLogs.value = logs
        }
    }

    // ------------------------------------------------------------------
    // Connection actions
    // ------------------------------------------------------------------

    /**
     * Clear the remembered connection choice so the picker dialog is re-shown on next launch.
     */
    fun onSwitchConnection() {
        viewModelScope.launch { connectionManager.forgetMode() }
    }

    /**
     * Persist a manual Network connection override.
     *
     * @param host IPv4/hostname of the remote server
     * @param port TCP port (1–65535)
     */
    fun onSaveManualConnection(host: String, port: Int) {
        viewModelScope.launch {
            connectionManager.rememberMode(ConnectionMode.Network("http://$host:$port"))
        }
    }

    // ------------------------------------------------------------------
    // Units actions
    // ------------------------------------------------------------------

    fun onDistanceUnitChange(unit: DistanceUnit) {
        viewModelScope.launch { unitsPreferences.saveDistanceUnit(unit) }
    }

    fun onBearingModeChange(mode: BearingMode) {
        viewModelScope.launch { unitsPreferences.saveBearingMode(mode) }
    }
}

/**
 * Snapshot of connection settings state for the Connection Settings screen.
 *
 * @param activeMode The currently remembered connection mode, or null if not set.
 * @param displayLabel Human-readable description of the active mode.
 * @param rememberedHost Hostname from the stored Network URL (empty for Embedded).
 * @param rememberedPort Port string from the stored Network URL (default "6502").
 */
data class ConnectionSettingsState(
    val activeMode: ConnectionMode? = null,
    val displayLabel: String = "Not configured",
    val rememberedHost: String = "",
    val rememberedPort: String = "6502",
)
