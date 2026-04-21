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
import kotlin.math.abs
import kotlin.math.floor

/**
 * HUD overlay displaying connection label, position, heading, SOG, and COG.
 *
 * The navigation rows are absent when [navigationData] is null.
 * The connection label is shown when non-blank regardless of navigation data.
 *
 * Monospace font is used for the values to prevent layout jitter as numbers change.
 */
@Composable
fun HudOverlay(
    navigationData: NavigationData?,
    connectionLabel: String = "",
    modifier: Modifier = Modifier,
) {
    if (connectionLabel.isBlank() && navigationData == null) return

    Column(modifier = modifier.padding(12.dp)) {
        if (connectionLabel.isNotBlank()) {
            Text(
                text = connectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        navigationData?.latDeg?.let { lat ->
            HudRow(label = "LAT", value = formatDms(lat, isLat = true))
        }
        navigationData?.lonDeg?.let { lon ->
            HudRow(label = "LON", value = formatDms(lon, isLat = false))
        }
        navigationData?.headingDeg?.let { heading ->
            HudRow(label = "HDG", value = "%.1f°".format(heading))
        }
        navigationData?.sogKnots?.let { sog ->
            HudRow(label = "SOG", value = "%.1f kt".format(sog))
        }
        navigationData?.cogDeg?.let { cog ->
            HudRow(label = "COG", value = "%.1f°".format(cog))
        }
    }
}

/** Format decimal degrees to D°MM'SS.S" N/S or E/W. */
private fun formatDms(deg: Double, isLat: Boolean): String {
    val abs = abs(deg)
    val d = floor(abs).toInt()
    val mFull = (abs - d) * 60.0
    val m = floor(mFull).toInt()
    val s = (mFull - m) * 60.0
    val dir = if (isLat) (if (deg >= 0) "N" else "S") else (if (deg >= 0) "E" else "W")
    return "%d°%02d'%04.1f\" %s".format(d, m, s, dir)
}

@Composable
private fun HudRow(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
