package com.marineyachtradar.mayara.ui.radar.overlay

import com.marineyachtradar.mayara.data.model.DistanceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Parameterised tests for [RangeFormatter].
 *
 * Covers fraction notation for sub-1 NM ranges, decimal formatting for larger
 * ranges, and conversions for KM and SM distance units.
 */
class RangeFormatterTest {

    // ------------------------------------------------------------------
    // NM fraction cases
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} m → \"{1}\"")
    @CsvSource(
        "58, 1/32 NM",    // 1852/32 ≈ 57.875, rounded
        "57, 1/32 NM",    // RangeStepper.NAUTICAL_METRES value
        "116, 1/16 NM",   // 1852/16 ≈ 115.75, rounded
        "115, 1/16 NM",   // RangeStepper.NAUTICAL_METRES value
        "173, 3/32 NM",   // 1852*3/32 ≈ 173.625
        "347, 3/16 NM",   // 1852*3/16 ≈ 347.25
        "231, 1/8 NM",    // 1852/8 ≈ 231.5
        "232, 1/8 NM",    // exact
        "240, 1/8 NM",    // within 5% of 231.5
        "463, 1/4 NM",    // 1852/4
        "460, 1/4 NM",    // within 5%
        "694, 3/8 NM",    // 1852*3/8
        "700, 3/8 NM",    // within 5%
        "926, 1/2 NM",    // 1852/2
        "920, 1/2 NM",    // within 5%
        "1389, 3/4 NM",   // 1852*3/4
        "1400, 3/4 NM",   // within 5%
    )
    fun `NM fraction ranges`(metres: Int, expected: String) {
        assertEquals(expected, RangeFormatter.format(metres, DistanceUnit.NM))
    }

    // ------------------------------------------------------------------
    // NM decimal cases
    // ------------------------------------------------------------------

    @Test
    fun `sub-1 NM non-fraction uses 2 decimal places`() {
        // 185 m = 0.10 NM — not within 5% of any fraction
        val result = RangeFormatter.format(185, DistanceUnit.NM)
        assertEquals("0.10 NM", result)
    }

    @Test
    fun `1852 metres formats as 1_0 NM`() {
        assertEquals("1.0 NM", RangeFormatter.format(1852, DistanceUnit.NM))
    }

    @Test
    fun `2778 metres formats as 1_5 NM`() {
        assertEquals("1.5 NM", RangeFormatter.format(2778, DistanceUnit.NM))
    }

    @Test
    fun `3704 metres formats as 2_0 NM`() {
        assertEquals("2.0 NM", RangeFormatter.format(3704, DistanceUnit.NM))
    }

    @Test
    fun `18520 metres formats as 10 NM`() {
        assertEquals("10 NM", RangeFormatter.format(18520, DistanceUnit.NM))
    }

    @Test
    fun `37040 metres formats as 20 NM`() {
        assertEquals("20 NM", RangeFormatter.format(37040, DistanceUnit.NM))
    }

    @Test
    fun `192000 metres (max HALO range) formats without throwing`() {
        val result = RangeFormatter.format(192000, DistanceUnit.NM)
        assertTrue(result.endsWith("NM"), "expected NM suffix but got: $result")
    }

    // ------------------------------------------------------------------
    // KM cases
    // ------------------------------------------------------------------

    @Test
    fun `1852 m in KM formats as 1_9 km`() {
        assertEquals("1.9 km", RangeFormatter.format(1852, DistanceUnit.KM))
    }

    @Test
    fun `500 m in KM formats as 0_5 km`() {
        assertEquals("0.5 km", RangeFormatter.format(500, DistanceUnit.KM))
    }

    @Test
    fun `18520 m in KM formats as 19 km`() {
        assertEquals("19 km", RangeFormatter.format(18520, DistanceUnit.KM))
    }

    // ------------------------------------------------------------------
    // SM cases
    // ------------------------------------------------------------------

    @Test
    fun `1852 m in SM formats as 1_2 SM`() {
        assertEquals("1.2 SM", RangeFormatter.format(1852, DistanceUnit.SM))
    }

    @Test
    fun `500 m in SM formats as 0_31 SM`() {
        assertEquals("0.31 SM", RangeFormatter.format(500, DistanceUnit.SM))
    }

    @Test
    fun `18520 m in SM formats as 12 SM`() {
        // 18520 / 1609.344 ≈ 11.507 → ≥ 10 so 0 decimal places → rounds to 12
        assertEquals("12 SM", RangeFormatter.format(18520, DistanceUnit.SM))
    }

    @Test
    fun `32000 m in SM formats as 20 SM`() {
        // 32000 / 1609.344 ≈ 19.88
        assertEquals("20 SM", RangeFormatter.format(32000, DistanceUnit.SM))
    }

    // ------------------------------------------------------------------
    // Legacy formatRange compatibility
    // ------------------------------------------------------------------

    @Test
    fun `legacy formatRange delegates to NM formatting`() {
        // 926 m should now show "1/2 NM" via the legacy function
        assertEquals("1/2 NM", formatRange(926))
    }
}
