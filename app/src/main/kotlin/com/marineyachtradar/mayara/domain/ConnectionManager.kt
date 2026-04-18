package com.marineyachtradar.mayara.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.marineyachtradar.mayara.data.model.ConnectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI

/**
 * Manages the active connection mode (Embedded vs Network) and persists the user's
 * "Remember my choice" preference across app restarts.
 *
 * Injected as a singleton; consumers observe [connectionMode] as a [Flow].
 *
 * TODO Phase 2: inject DataStore and implement the full flow.
 */
class ConnectionManager(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_MODE = stringPreferencesKey("connection_mode")
        private val KEY_REMEMBERED_HOST = stringPreferencesKey("remembered_host")
        private val KEY_REMEMBERED_PORT = stringPreferencesKey("remembered_port")

        const val EMBEDDED_VALUE = "embedded"
        const val NETWORK_VALUE = "network"
    }

    /** The currently persisted connection mode, or null if no choice has been remembered. */
    val rememberedMode: Flow<ConnectionMode?> = dataStore.data.map { prefs ->
        when (prefs[KEY_MODE]) {
            EMBEDDED_VALUE -> ConnectionMode.Embedded()
            NETWORK_VALUE -> {
                val host = prefs[KEY_REMEMBERED_HOST] ?: return@map null
                val port = prefs[KEY_REMEMBERED_PORT]?.toIntOrNull() ?: return@map null
                ConnectionMode.Network("http://$host:$port")
            }
            else -> null
        }
    }

    /** Persist the chosen mode. Call when the user ticks "Remember my choice". */
    suspend fun rememberMode(mode: ConnectionMode) {
        dataStore.edit { prefs ->
            when (mode) {
                is ConnectionMode.Embedded -> {
                    prefs[KEY_MODE] = EMBEDDED_VALUE
                }
                is ConnectionMode.Network -> {
                    prefs[KEY_MODE] = NETWORK_VALUE
                    val uri = URI(mode.baseUrl)
                    prefs[KEY_REMEMBERED_HOST] = uri.host ?: ""
                    prefs[KEY_REMEMBERED_PORT] = (uri.port.takeIf { it > 0 } ?: 6502).toString()
                }
                is ConnectionMode.PcapDemo -> {
                    // PCAP demo is not persisted across restarts (file path may be temporary)
                }
            }
        }
    }

    /** Clear the remembered choice (re-triggers the picker dialog on next launch). */
    suspend fun forgetMode() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_MODE)
            prefs.remove(KEY_REMEMBERED_HOST)
            prefs.remove(KEY_REMEMBERED_PORT)
        }
    }
}
