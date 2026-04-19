package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.data.model.DistanceUnit

@Composable
fun RangeControls(
    ranges: List<Int>,
    currentIndex: Int,
    onRangeUp: () -> Unit,
    onRangeDown: () -> Unit,
    distanceUnit: DistanceUnit = DistanceUnit.NM,
    modifier: Modifier = Modifier,
) {
    val currentRangeMetres = ranges.getOrNull(currentIndex)
    val displayText = currentRangeMetres?.let { RangeFormatter.format(it, distanceUnit) } ?: "--"

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        modifier = modifier.padding(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick = onRangeUp,
                enabled = currentIndex > 0,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Range in", modifier = Modifier.size(20.dp))
            }

            Text(
                text = displayText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp),
            )

            IconButton(
                onClick = onRangeDown,
                enabled = currentIndex < ranges.lastIndex,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Range out", modifier = Modifier.size(20.dp))
            }
        }
    }
}


internal fun formatRange(metres: Int): String = RangeFormatter.format(metres, DistanceUnit.NM)
