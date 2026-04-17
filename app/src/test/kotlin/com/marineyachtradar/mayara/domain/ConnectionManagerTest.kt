package com.marineyachtradar.mayara.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.marineyachtradar.mayara.data.model.ConnectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Fake DataStore backed by an in-memory MutableStateFlow.
 * The edit() extension calls updateData(), so a real implementation lets us test
 * ConnectionManager without MockK static-mocking of extension functions.
 */
private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class ConnectionManagerTest {

    private lateinit var dataStore: FakePreferencesDataStore
    private lateinit var manager: ConnectionManager

    @BeforeEach
    fun setUp() {
        dataStore = FakePreferencesDataStore()
        manager = ConnectionManager(dataStore)
    }

    @Test
    fun `rememberedMode returns null when no mode stored`() = runTest {
        val result = manager.rememberedMode.first()
        assertNull(result)
    }

    @Test
    fun `rememberedMode returns Embedded when embedded value stored`() = runTest {
        manager.rememberMode(ConnectionMode.Embedded())

        val result = manager.rememberedMode.first()

        assertEquals(ConnectionMode.Embedded(), result)
    }

    @Test
    fun `rememberedMode returns Network when network value and host+port stored`() = runTest {
        manager.rememberMode(ConnectionMode.Network("http://192.168.1.10:3000"))

        val result = manager.rememberedMode.first()

        assertEquals(ConnectionMode.Network("http://192.168.1.10:3000"), result)
    }

    @Test
    fun `rememberedMode returns null when network stored but host missing`() = runTest {
        // Manually write a "network" mode key but no host
        dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[stringPreferencesKey("connection_mode")] = "network"
            }
        }

        val result = manager.rememberedMode.first()

        assertNull(result)
    }

    @Test
    fun `rememberMode persists Embedded mode`() = runTest {
        manager.rememberMode(ConnectionMode.Embedded())

        val prefs = dataStore.data.first()
        assertEquals("embedded", prefs[stringPreferencesKey("connection_mode")])
    }

    @Test
    fun `rememberMode persists Network mode with host and port`() = runTest {
        manager.rememberMode(ConnectionMode.Network("http://10.0.0.1:6502"))

        val prefs = dataStore.data.first()
        assertEquals("network", prefs[stringPreferencesKey("connection_mode")])
        assertEquals("10.0.0.1", prefs[stringPreferencesKey("remembered_host")])
        assertEquals("6502", prefs[stringPreferencesKey("remembered_port")])
    }

    @Test
    fun `forgetMode removes stored keys`() = runTest {
        manager.rememberMode(ConnectionMode.Embedded())
        manager.forgetMode()

        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("connection_mode")])
    }
}

