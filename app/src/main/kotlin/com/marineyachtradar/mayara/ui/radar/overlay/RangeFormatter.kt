package com.marineyachtradar.mayara.ui.radar.overlay

import com.marineyachtradar.mayara.data.model.DistanceUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats radar range values (in metres) for display, respecting the user's distance unit
 * preference and using marine-convention fraction notation for sub-1 NM ranges.
 *
 * Fraction notation (e.g. "1/8 NM", "1/4 NM") is the standard on Navico, Furuno,
 * Garmin and Raymarine radar displays for short ranges.
 */
object RangeFormatter {

    /** Denominators used for marine-style fraction notation under 1 NM. */
    private val NM_FRACTION_DENOMINATORS = listOf(2, 4, 8, 16, 32)

    private const val METRES_PER_NM = 1852.0
    private const val METRES_PER_SM = 1609.344

    /**
     * Format a range in metres to a human-readable string using the given [unit].
     *
     * - **NM**: Uses fraction notation (1/8, 1/4, 3/8, 1/2, 3/4) for sub-1 NM ranges
     *   when within ±5 % tolerance. Falls back to decimal notation otherwise.
     * - **KM**: Metres → kilometres with appropriate decimal places.
     * - **SM**: Metres → statute miles with appropriate decimal places.
     */
    fun format(metres: Int, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.NM -> formatNm(metres)
        DistanceUnit.KM -> formatKm(metres)
        DistanceUnit.SM -> formatSm(metres)
    }

    private fun formatNm(metres: Int): String {
        val nm = metres / METRES_PER_NM

        // Prefer marine-style fractions for sub-1 NM values when close enough.
        if (nm < 1.0) {
            val fraction = nmFractionLabel(metres)
            if (fraction != null) {
                return "$fraction NM"
            }

            // Sub-1 NM but not a known fraction — use 2 decimal places.
            return "%.2f NM".format(nm)
        }

        // 1 NM – 10 NM: 1 decimal place.
        if (nm < 10.0) return "%.1f NM".format(nm)

        // ≥ 10 NM: no decimals.
        return "%.0f NM".format(nm)
    }

    private fun formatKm(metres: Int): String {
        val km = metres / 1000.0
        return if (km < 10.0) "%.1f km".format(km) else "%.0f km".format(km)
    }

    private fun formatSm(metres: Int): String {
        val sm = metres / METRES_PER_SM
        return when {
            sm < 1.0 -> "%.2f SM".format(sm)
            sm < 10.0 -> "%.1f SM".format(sm)
            else -> "%.0f SM".format(sm)
        }
    }

    private fun nmFractionLabel(metres: Int): String? {
        var best: Pair<Int, Int>? = null
        var bestError = Double.MAX_VALUE

        for (denominator in NM_FRACTION_DENOMINATORS) {
            val numerator = ((metres * denominator) / METRES_PER_NM).roundToInt()
            if (numerator <= 0 || numerator >= denominator) continue

            val candidateMetres = METRES_PER_NM * numerator / denominator
            val tolerance = candidateMetres * 0.05
            val error = abs(metres - candidateMetres)
            if (error <= tolerance && error < bestError) {
                best = reduceFraction(numerator, denominator)
                bestError = error
            }
        }

        return best?.let { (n, d) -> "$n/$d" }
    }

    private fun reduceFraction(numerator: Int, denominator: Int): Pair<Int, Int> {
        val divisor = gcd(numerator, denominator)
        return (numerator / divisor) to (denominator / divisor)
    }

    private tailrec fun gcd(a: Int, b: Int): Int {
        if (b == 0) return a
        return gcd(b, a % b)
    }
}
