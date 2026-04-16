package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Range controls: large + / − buttons with current range value in the centre.
 *
 * Rules (from spec §3.3 and §2):
 * - Steps ONLY through [ranges] from the capabilities handshake — never hardcoded values.
 * - `+` is disabled when [currentIndex] is at the last (maximum) range.
 * - `−` is disabled when [currentIndex] is 0 (minimum range).
 * - Range value is displayed in a monospace font to prevent layout jitter.
 * - Distance unit conversion (NM / KM / SM) is handled upstream; this composable
 *   receives values already formatted as strings by the domain layer.
 *
 * TODO Phase 2: pass formatted range string from domain layer (unit-aware).
 */
@Composable
fun RangeControls(
    ranges: List<Int>,
    currentIndex: Int,
    onRangeUp: () -> Unit,
    onRangeDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentRangeMetres = ranges.getOrNull(currentIndex)
    val displayText = currentRangeMetres?.let { formatRange(it) } ?: "--"

    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // + button (zoom in = smaller range)
        FilledIconButton(
            onClick = onRangeUp,
            enabled = currentIndex > 0,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Range in")
        }

        // Current range display (monospace to prevent text width jitter)
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        // - button (zoom out = larger range)
        FilledIconButton(
            onClick = onRangeDown,
            enabled = currentIndex < ranges.lastIndex,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Range out")
        }
    }
}

/** Format a range in metres to a human-readable string (NM with one decimal). */
private fun formatRange(metres: Int): String {
    val nm = metres / 1852.0
    return "%.1f NM".format(nm)
}
