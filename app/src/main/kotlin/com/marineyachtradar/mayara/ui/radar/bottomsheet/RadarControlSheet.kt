package com.marineyachtradar.mayara.ui.radar.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.ControlsState
import com.marineyachtradar.mayara.data.model.RadarOrientation

/**
 * Bottom sheet exposing advanced radar controls (spec §3.4).
 *
 * Opened by swiping up from the bottom edge or tapping the Settings (Tune) icon.
 * All sections are populated dynamically from [ControlsState] — controls not
 * supported by the hardware (`null`) are hidden per spec §2.
 *
 * - **Gain / Sea Clutter**: slider + Auto toggle. Slider disabled when Auto active.
 * - **Rain Clutter**: slider only; hidden when [ControlsState.rainClutter] is null.
 * - **Interference Rejection**: chip row; hidden when [ControlsState.interferenceRejection] is null.
 * - **Color Palette**: always shown.
 * - **Orientation**: always shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarControlSheet(
    controls: ControlsState,
    onGainChange: (value: Float, isAuto: Boolean) -> Unit,
    onSeaChange: (value: Float, isAuto: Boolean) -> Unit,
    onRainChange: (value: Float) -> Unit,
    onInterferenceChange: (index: Int) -> Unit,
    onPaletteChange: (ColorPalette) -> Unit,
    onOrientationChange: (RadarOrientation) -> Unit,
    onSpokeGapFillChange: (Boolean) -> Unit,
    onClearAllTargets: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // ---- Gain --------------------------------------------------
            SheetSectionLabel("Gain")
            SliderRow(
                value = controls.gain.value,
                isAuto = controls.gain.isAuto,
                supportsAuto = true,
                onValueChange = { v, auto -> onGainChange(v, auto) },
            )

            SheetDivider()

            // ---- Sea Clutter -------------------------------------------
            SheetSectionLabel("Sea Clutter")
            SliderRow(
                value = controls.seaClutter.value,
                isAuto = controls.seaClutter.isAuto,
                supportsAuto = true,
                onValueChange = { v, auto -> onSeaChange(v, auto) },
            )

            // ---- Rain Clutter (optional) --------------------------------
            controls.rainClutter?.let { rain ->
                SheetDivider()
                SheetSectionLabel("Rain Clutter")
                SliderRow(
                    value = rain.value,
                    isAuto = false,
                    supportsAuto = false,
                    onValueChange = { v, _ -> onRainChange(v) },
                )
            }

            // ---- Interference Rejection (optional) ----------------------
            controls.interferenceRejection?.let { ir ->
                SheetDivider()
                SheetSectionLabel("Interference Rejection")
                Spacer(modifier = Modifier.height(8.dp))
                ChipRow(
                    options = ir.options,
                    selectedIndex = ir.selectedIndex,
                    onSelect = onInterferenceChange,
                )
            }

            SheetDivider()

            // ---- Color Palette -----------------------------------------
            SheetSectionLabel("Color Palette")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorPalette.entries.forEach { palette ->
                    FilterChip(
                        selected = palette == controls.palette,
                        onClick = { onPaletteChange(palette) },
                        label = { Text(paletteDisplayName(palette)) },
                    )
                }
            }

            SheetDivider()

            // ---- Orientation -------------------------------------------
            SheetSectionLabel("Orientation")
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadarOrientation.entries.forEach { orientation ->
                    FilterChip(
                        selected = orientation == controls.orientation,
                        onClick = { onOrientationChange(orientation) },
                        label = { Text(orientationDisplayName(orientation)) },
                    )
                }
            }

            SheetDivider()

            // ---- View Settings -----------------------------------------
            SheetSectionLabel("View Settings")
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Spoke gap fill",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Repeats spokes to fill angular gaps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = controls.spokeGapFill,
                    onCheckedChange = onSpokeGapFillChange,
                )
            }

            SheetDivider()

            // ---- Targets -----------------------------------------------
            SheetSectionLabel("Targets")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onClearAllTargets()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("Clear all ARPA targets")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Private section composables
// ---------------------------------------------------------------------------

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/**
 * A slider with an optional "Auto" chip to its right.
 *
 * When [isAuto] is true the slider is disabled (grayed out per spec §3.4).
 * The [onValueChange] callback receives both the new value and the new auto flag,
 * so callers can handle Auto-toggle and manual-drag in a single lambda.
 */
@Composable
private fun SliderRow(
    value: Float,
    isAuto: Boolean,
    supportsAuto: Boolean,
    onValueChange: (Float, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Slider(
            value = value,
            onValueChange = { onValueChange(it, isAuto) },
            valueRange = 0f..100f,
            enabled = !isAuto,
            modifier = Modifier.weight(1f),
        )
        if (supportsAuto) {
            FilterChip(
                selected = isAuto,
                onClick = { onValueChange(value, !isAuto) },
                label = { Text("Auto") },
            )
        }
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(option) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Display-name helpers (internal = testable from the same module)
// ---------------------------------------------------------------------------

internal fun paletteDisplayName(palette: ColorPalette): String = when (palette) {
    ColorPalette.GREEN -> "Green"
    ColorPalette.YELLOW -> "Yellow"
    ColorPalette.MULTI_COLOR -> "Multi"
    ColorPalette.NIGHT_RED -> "Night"
}

internal fun orientationDisplayName(orientation: RadarOrientation): String = when (orientation) {
    RadarOrientation.HEAD_UP -> "Head Up"
    RadarOrientation.NORTH_UP -> "North Up"
    RadarOrientation.COURSE_UP -> "Course Up"
}
