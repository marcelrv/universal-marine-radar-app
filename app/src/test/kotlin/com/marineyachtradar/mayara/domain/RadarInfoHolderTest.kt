package com.marineyachtradar.mayara.domain

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadarInfoHolderTest {

    @AfterEach
    fun tearDown() {
        RadarInfoHolder.clear()
    }

    @Test
    fun `initial state is null`() {
        assertNull(RadarInfoHolder.radarInfo.value)
    }

    @Test
    fun `update stores snapshot`() {
        val snapshot = RadarInfoSnapshot(
            radarName = "Halo 24",
            brand = "Navico",
            spokesPerRevolution = 2048,
            maxSpokeLength = 512,
            infoItems = listOf(
                RadarInfoItem(name = "Model name", value = "Halo 24"),
                RadarInfoItem(name = "Operating time", value = "100.0 h"),
            ),
        )

        RadarInfoHolder.update(snapshot)

        assertNotNull(RadarInfoHolder.radarInfo.value)
        assertEquals("Halo 24", RadarInfoHolder.radarInfo.value!!.radarName)
        assertEquals("Navico", RadarInfoHolder.radarInfo.value!!.brand)
        assertEquals(2, RadarInfoHolder.radarInfo.value!!.infoItems.size)
    }

    @Test
    fun `clear resets to null`() {
        RadarInfoHolder.update(
            RadarInfoSnapshot(
                radarName = "Test",
                brand = "Test",
                spokesPerRevolution = 1024,
                maxSpokeLength = 256,
            )
        )

        RadarInfoHolder.clear()

        assertNull(RadarInfoHolder.radarInfo.value)
    }
}
