package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.data.model.NavigationData

/**
 * HUD overlay displaying heading, SOG, and COG.
 *
 * The composable is entirely absent from the layout when [navigationData] is null
 * (i.e., no NMEA/SignalK navigation source is connected).
 *
 * Monospace font is used for the values to prevent layout jitter as numbers change.
 */
@Composable
fun HudOverlay(
    navigationData: NavigationData?,
    modifier: Modifier = Modifier,
) {
    navigationData ?: return   // Spec §3.3: hidden when no navigation data

    Column(modifier = modifier.padding(12.dp)) {
        navigationData.headingDeg?.let { heading ->
            HudRow(label = "HDG", value = "%.1f°".format(heading))
        }
        navigationData.sogKnots?.let { sog ->
            HudRow(label = "SOG", value = "%.1f kt".format(sog))
        }
        navigationData.cogDeg?.let { cog ->
            HudRow(label = "COG", value = "%.1f°".format(cog))
        }
    }
}

@Composable
private fun HudRow(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
