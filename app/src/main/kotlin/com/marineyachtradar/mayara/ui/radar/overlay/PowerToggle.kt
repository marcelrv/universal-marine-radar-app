package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.data.model.PowerState

/**
 * Pill-shaped power / state control button.
 *
 * State machine (spec §3.3):
 *   OFF → WARMUP (with countdown timer) → STANDBY → TRANSMIT
 *
 * Tapping the button advances the state:
 *   - OFF → requests STANDBY (server transitions through WARMUP automatically)
 *   - STANDBY → requests TRANSMIT
 *   - TRANSMIT → requests STANDBY
 *   - WARMUP → no action (warming up, cannot be interrupted)
 *
 * Colour coding:
 *   - OFF:      grey
 *   - WARMUP:   amber
 *   - STANDBY:  green (dim)
 *   - TRANSMIT: green (bright)
 */
@Composable
fun PowerToggle(
    powerState: PowerState,
    onPowerAction: (PowerState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (powerState) {
        PowerState.OFF -> "OFF"
        PowerState.WARMUP -> "WARMING UP"
        PowerState.STANDBY -> "STANDBY"
        PowerState.TRANSMIT -> "TRANSMIT"
    }

    val containerColor = when (powerState) {
        PowerState.OFF -> Color(0xFF3A3A3A)
        PowerState.WARMUP -> Color(0xFFB8860B)
        PowerState.STANDBY -> Color(0xFF2E7D32)
        PowerState.TRANSMIT -> Color(0xFF43A047)
    }

    val isEnabled = powerState != PowerState.WARMUP
    val next = nextPowerTarget(powerState)

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor.copy(alpha = if (isEnabled) 1f else 0.7f),
        modifier = modifier
            .padding(end = 8.dp)
            .clickable(enabled = isEnabled) {
                next?.let { onPowerAction(it) }
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Pure function: returns the next [PowerState] target when the user taps the button,
 * or `null` if no transition is allowed (WARMUP).
 *
 * Internal visibility for unit testing.
 */
internal fun nextPowerTarget(current: PowerState): PowerState? = when (current) {
    PowerState.OFF -> PowerState.STANDBY
    PowerState.STANDBY -> PowerState.TRANSMIT
    PowerState.TRANSMIT -> PowerState.STANDBY
    PowerState.WARMUP -> null
}
