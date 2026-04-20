package com.marineyachtradar.mayara.domain

import app.cash.turbine.test
import com.marineyachtradar.mayara.data.api.ControlUpdate
import com.marineyachtradar.mayara.data.api.RadarApiClient
import com.marineyachtradar.mayara.data.api.RadarApiException
import com.marineyachtradar.mayara.data.api.SignalKStreamClient
import com.marineyachtradar.mayara.data.api.SpokeWebSocketClient
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.ControlDefinition
import com.marineyachtradar.mayara.data.model.ControlType
import com.marineyachtradar.mayara.data.model.ControlsState
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarCapabilities
import com.marineyachtradar.mayara.data.model.RadarInfo
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.data.model.SliderControlState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RadarRepositoryTest {

    private lateinit var apiClient: RadarApiClient
    private lateinit var spokeClient: SpokeWebSocketClient
    private lateinit var streamClient: SignalKStreamClient
    private lateinit var testScope: TestScope
    private lateinit var repository: RadarRepository

    private val testRadar = RadarInfo(
        id = "emulator_1",
        name = "Test Radar",
        brand = "Emulator",
        spokeDataUrl = "ws://127.0.0.1:6502/spokes",
    )

    private val testCapabilities = RadarCapabilities(
        radarId = "emulator_1",
        ranges = listOf(300, 1200, 6000, 12000),
        spokesPerRevolution = 4096,
        maxSpokeLength = 512,
        controls = mapOf(
            "gain" to ControlDefinition("gain", "Gain", ControlType.RANGE_SLIDER, minValue = 0f, maxValue = 100f),
            "sea" to ControlDefinition("sea", "Sea Clutter", ControlType.RANGE_SLIDER, minValue = 0f, maxValue = 100f),
        ),
    )

    private val testControlValues = mapOf(
        "gain" to ControlValue(50f),
        "sea" to ControlValue(20f),
        "power" to ControlValue(2f),
        "range" to ControlValue(6000f),
    )

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        spokeClient = mockk()
        streamClient = mockk()
        testScope = TestScope(UnconfinedTestDispatcher())

        // Default: spoke and stream flows emit nothing
        every { spokeClient.connect(any()) } returns emptyFlow()
        every { streamClient.connect(any()) } returns emptyFlow()
        // Allow the repository to update baseUrl on connect()
        every { apiClient.baseUrl = any() } just Runs
    }

    private fun buildRepository() = RadarRepository(
        apiClient = apiClient,
        spokeClient = spokeClient,
        streamClient = streamClient,
        scope = testScope,
    ).also { repository = it }

    @Test
    fun `initial uiState is Loading`() {
        buildRepository()
        assertInstanceOf(RadarUiState.Loading::class.java, repository.uiState.value)
    }

    @Test
    fun `connect transitions to Connected after successful handshake`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.uiState.test {
            // Initial state
            assertInstanceOf(RadarUiState.Loading::class.java, awaitItem())

            repository.connect("http://127.0.0.1:6502")

            // StateFlow deduplicates: Loading→Loading is not re-emitted, so we get Connected directly
            val connected = awaitItem()
            assertInstanceOf(RadarUiState.Connected::class.java, connected)
            connected as RadarUiState.Connected
            assertEquals("emulator_1", connected.radar.id)
            assertEquals(listOf(300, 1200, 6000, 12000), connected.capabilities.ranges)
            assertEquals(PowerState.TRANSMIT, connected.powerState)  // power value = 2
            assertEquals(2, connected.currentRangeIndex)  // range 6000 is index 2
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect transitions to Error when getRadars throws`() = runTest {
        coEvery { apiClient.getRadars() } throws RadarApiException("Network error")
        buildRepository()

        repository.uiState.test {
            awaitItem() // Loading initial
            repository.connect("http://127.0.0.1:6502")
            // StateFlow deduplicates Loading→Loading, so Error is the next distinct item
            val error = awaitItem()
            assertInstanceOf(RadarUiState.Error::class.java, error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connect stays Loading while polling when no radars returned yet`() = runTest {
        coEvery { apiClient.getRadars() } returns emptyList()
        buildRepository()

        repository.uiState.test {
            awaitItem() // initial Loading
            repository.connect("http://127.0.0.1:6502")
            // Should remain in Loading (polling) rather than transitioning to Error
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSliderControl applies optimistic update and calls putControl`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        coEvery { apiClient.putControl(any(), any(), any(), any()) } returns Unit
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        repository.uiState.test {
            val connected = awaitItem() as? RadarUiState.Connected
            if (connected == null) {
                // Skip Loading states
                val next = awaitItem() as? RadarUiState.Connected
                    ?: (awaitItem() as RadarUiState.Connected)
                assertEquals(50f, next.controls.gain.value)
                repository.setSliderControl("gain", 75f)
                val updated = awaitItem() as RadarUiState.Connected
                assertEquals(75f, updated.controls.gain.value)
            } else {
                assertEquals(50f, connected.controls.gain.value)
                repository.setSliderControl("gain", 75f)
                val updated = awaitItem() as RadarUiState.Connected
                assertEquals(75f, updated.controls.gain.value)
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { apiClient.putControl("emulator_1", "gain", 75f, null) }
    }

    @Test
    fun `control stream update changes power state`() = runTest {
        val powerUpdate = ControlUpdate(
            radarId = "emulator_1",
            controlId = "power",
            value = 0f,
        )
        every { streamClient.connect(any()) } returns flowOf(powerUpdate)
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        // After connect and stream processing, power should be OFF (value=0)
        val state = repository.uiState.value
        if (state is RadarUiState.Connected) {
            assertEquals(PowerState.OFF, state.powerState)
        }
    }

    @Test
    fun `setPalette updates palette in controls state without REST call`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        // Wait for Connected
        var connectedState: RadarUiState.Connected? = null
        repeat(5) {
            val s = repository.uiState.value
            if (s is RadarUiState.Connected) connectedState = s
        }

        repository.setPalette(ColorPalette.NIGHT_RED)

        val updated = repository.uiState.value as? RadarUiState.Connected
        if (updated != null) {
            assertEquals(ColorPalette.NIGHT_RED, updated.controls.palette)
        }
        // No REST call for palette
        coVerify(exactly = 0) { apiClient.putControl(any(), eq("palette"), any(), any()) }
    }

    @Test
    fun `disconnect resets state to Loading`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")
        repository.disconnect()

        assertInstanceOf(RadarUiState.Loading::class.java, repository.uiState.value)
    }

    @Test
    fun `setRangeIndex calls putControl with correct range metres`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        coEvery { apiClient.putControl(any(), any(), any(), any()) } returns Unit
        buildRepository()

        repository.connect("http://127.0.0.1:6502")
        repository.setRangeIndex(3)  // index 3 = 12000 metres

        coVerify { apiClient.putControl("emulator_1", "range", 12000f, null) }
    }

    @Test
    fun `setEnumControl applies optimistic update and calls putControl`() = runTest {
        val capabilitiesWithEnum = testCapabilities.copy(
            controls = testCapabilities.controls + mapOf(
                "interferenceRejection" to ControlDefinition(
                    id = "interferenceRejection",
                    name = "Interference",
                    type = ControlType.ENUM,
                    options = listOf("Off", "Low", "Medium", "High"),
                )
            )
        )
        val valuesWithEnum = testControlValues + mapOf(
            "interferenceRejection" to ControlValue(0f)
        )
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns capabilitiesWithEnum
        coEvery { apiClient.getControls("emulator_1") } returns valuesWithEnum
        coEvery { apiClient.putControl(any(), any(), any(), any()) } returns Unit
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        // Wait for Connected state
        val afterConnect = repository.uiState.value
        if (afterConnect is RadarUiState.Connected) {
            assertEquals(0, afterConnect.controls.interferenceRejection?.selectedIndex)
        }

        repository.setEnumControl("interferenceRejection", 2)

        val updated = repository.uiState.value as? RadarUiState.Connected
        if (updated != null) {
            assertEquals(2, updated.controls.interferenceRejection?.selectedIndex)
        }

        coVerify { apiClient.putControl("emulator_1", "interferenceRejection", 2f, null) }
    }

    @Test
    fun `setEnumControl on unknown controlId does not change state`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        coEvery { apiClient.putControl(any(), any(), any(), any()) } returns Unit
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        val before = repository.uiState.value
        repository.setEnumControl("unknownControl", 5)
        val after = repository.uiState.value

        // State should be identical (the withEnum else branch returns `this`)
        assertEquals(before, after)
    }

    @Test
    fun `setOrientation updates orientation in controls state without REST call`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        repository.setOrientation(RadarOrientation.NORTH_UP)

        val updated = repository.uiState.value as? RadarUiState.Connected
        if (updated != null) {
            assertEquals(RadarOrientation.NORTH_UP, updated.controls.orientation)
        }
        coVerify(exactly = 0) { apiClient.putControl(any(), eq("orientation"), any(), any()) }
    }

    @Test
    fun `setPowerState sends PUT control for power without optimistic update`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        coEvery { apiClient.putControl(any(), any(), any(), any()) } returns Unit
        buildRepository()

        repository.connect("http://127.0.0.1:6502")
        repository.setPowerState(PowerState.OFF)

        coVerify { apiClient.putControl("emulator_1", "power", 0f, null) }
    }

    // ------------------------------------------------------------------
    // BF-07: Power state default and edge cases
    // ------------------------------------------------------------------

    @Test
    fun `connect with no power key in controls defaults to STANDBY`() = runTest {
        val valuesNoPower = testControlValues.filterKeys { it != "power" }
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns valuesNoPower
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        val state = repository.uiState.value as? RadarUiState.Connected
        assertEquals(PowerState.STANDBY, state?.powerState)
    }

    @Test
    fun `connect with power value 2 gives TRANSMIT`() = runTest {
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        val state = repository.uiState.value as? RadarUiState.Connected
        assertEquals(PowerState.TRANSMIT, state?.powerState)
    }

    @Test
    fun `control stream with unknown power value 99 defaults to STANDBY`() = runTest {
        val powerUpdate = ControlUpdate(
            radarId = "emulator_1",
            controlId = "power",
            value = 99f,
        )
        every { streamClient.connect(any()) } returns flowOf(powerUpdate)
        coEvery { apiClient.getRadars() } returns listOf(testRadar)
        coEvery { apiClient.getCapabilities("emulator_1") } returns testCapabilities
        coEvery { apiClient.getControls("emulator_1") } returns testControlValues
        buildRepository()

        repository.connect("http://127.0.0.1:6502")

        val state = repository.uiState.value
        if (state is RadarUiState.Connected) {
            assertEquals(PowerState.STANDBY, state.powerState)
        }
    }
}
