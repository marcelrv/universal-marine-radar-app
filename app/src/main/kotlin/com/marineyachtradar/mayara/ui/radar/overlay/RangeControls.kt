package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RangeButton(
            icon = Icons.Rounded.Add,
            contentDescription = "Range in",
            enabled = currentIndex > 0,
            onClick = onRangeUp,
        )

        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White.copy(alpha = 0.9f),
        )

        RangeButton(
            icon = Icons.Rounded.Remove,
            contentDescription = "Range out",
            enabled = currentIndex < ranges.lastIndex,
            onClick = onRangeDown,
        )
    }
}

@Composable
private fun RangeButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.35f else 0.15f)),
        modifier = Modifier.size(48.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = 0.3f),
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

internal fun formatRange(metres: Int): String = RangeFormatter.format(metres, DistanceUnit.NM)
