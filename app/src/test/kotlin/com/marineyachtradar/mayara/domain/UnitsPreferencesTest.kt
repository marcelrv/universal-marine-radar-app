package com.marineyachtradar.mayara.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.marineyachtradar.mayara.data.model.BearingMode
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.DistanceUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class FakeUnitsDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class UnitsPreferencesTest {

    private lateinit var dataStore: FakeUnitsDataStore
    private lateinit var prefs: UnitsPreferences

    @BeforeEach
    fun setUp() {
        dataStore = FakeUnitsDataStore()
        prefs = UnitsPreferences(dataStore)
    }

    // ── distanceUnit defaults ────────────────────────────────────────────

    @Test
    fun `distanceUnit returns NM by default when no value stored`() = runTest {
        val result = prefs.distanceUnit.first()
        assertEquals(DistanceUnit.NM, result)
    }

    // ── saveDistanceUnit ─────────────────────────────────────────────────

    @Test
    fun `saveDistanceUnit persists KM`() = runTest {
        prefs.saveDistanceUnit(DistanceUnit.KM)

        val result = prefs.distanceUnit.first()
        assertEquals(DistanceUnit.KM, result)
    }

    @Test
    fun `saveDistanceUnit persists SM`() = runTest {
        prefs.saveDistanceUnit(DistanceUnit.SM)

        val result = prefs.distanceUnit.first()
        assertEquals(DistanceUnit.SM, result)
    }

    @Test
    fun `saveDistanceUnit overwrites previous selection`() = runTest {
        prefs.saveDistanceUnit(DistanceUnit.KM)
        prefs.saveDistanceUnit(DistanceUnit.SM)

        val result = prefs.distanceUnit.first()
        assertEquals(DistanceUnit.SM, result)
    }

    // ── bearingMode defaults ─────────────────────────────────────────────

    @Test
    fun `bearingMode returns TRUE by default when no value stored`() = runTest {
        val result = prefs.bearingMode.first()
        assertEquals(BearingMode.TRUE, result)
    }

    // ── saveBearingMode ──────────────────────────────────────────────────

    @Test
    fun `saveBearingMode persists MAGNETIC`() = runTest {
        prefs.saveBearingMode(BearingMode.MAGNETIC)

        val result = prefs.bearingMode.first()
        assertEquals(BearingMode.MAGNETIC, result)
    }

    // ── resetToDefaults ──────────────────────────────────────────────────

    @Test
    fun `resetToDefaults clears both keys so defaults are returned`() = runTest {
        prefs.saveDistanceUnit(DistanceUnit.SM)
        prefs.saveBearingMode(BearingMode.MAGNETIC)

        prefs.resetToDefaults()

        assertEquals(DistanceUnit.NM, prefs.distanceUnit.first())
        assertEquals(BearingMode.TRUE, prefs.bearingMode.first())
    }

    @Test
    fun `resetToDefaults removes distance_unit key from store`() = runTest {
        prefs.saveDistanceUnit(DistanceUnit.KM)

        prefs.resetToDefaults()

        val rawValue = dataStore.data.first()[stringPreferencesKey("distance_unit")]
        assertEquals(null, rawValue)
    }

    // ── colorPalette ─────────────────────────────────────────────────────

    @Test
    fun `colorPalette returns GREEN by default when no value stored`() = runTest {
        val result = prefs.colorPalette.first()
        assertEquals(ColorPalette.GREEN, result)
    }

    @Test
    fun `saveColorPalette persists NIGHT_RED`() = runTest {
        prefs.saveColorPalette(ColorPalette.NIGHT_RED)

        val result = prefs.colorPalette.first()
        assertEquals(ColorPalette.NIGHT_RED, result)
    }

    @Test
    fun `saveColorPalette persists YELLOW`() = runTest {
        prefs.saveColorPalette(ColorPalette.YELLOW)

        val result = prefs.colorPalette.first()
        assertEquals(ColorPalette.YELLOW, result)
    }

    @Test
    fun `saveColorPalette persists MULTI_COLOR`() = runTest {
        prefs.saveColorPalette(ColorPalette.MULTI_COLOR)

        val result = prefs.colorPalette.first()
        assertEquals(ColorPalette.MULTI_COLOR, result)
    }

    @Test
    fun `resetToDefaults clears color_palette key so GREEN is returned`() = runTest {
        prefs.saveColorPalette(ColorPalette.NIGHT_RED)

        prefs.resetToDefaults()

        assertEquals(ColorPalette.GREEN, prefs.colorPalette.first())
    }
}
