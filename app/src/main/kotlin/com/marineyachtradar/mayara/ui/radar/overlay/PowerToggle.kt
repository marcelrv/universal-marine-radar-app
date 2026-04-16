package com.marineyachtradar.mayara.ui.radar.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *   - WARMUP → no action (warming up, can not be interrupted)
 *
 * Colour coding:
 *   - OFF:      grey
 *   - WARMUP:   amber (with countdown — TODO Phase 4: add countdown timer)
 *   - STANDBY:  green (dim)
 *   - TRANSMIT: green (bright / pulsing)
 *
 * TODO Phase 4: add countdown timer display during WARMUP state.
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

    Button(
        onClick = {
            val next = when (powerState) {
                PowerState.OFF -> PowerState.STANDBY
                PowerState.STANDBY -> PowerState.TRANSMIT
                PowerState.TRANSMIT -> PowerState.STANDBY
                PowerState.WARMUP -> return@Button
            }
            onPowerAction(next)
        },
        enabled = isEnabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = modifier.padding(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}
