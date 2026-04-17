package com.marineyachtradar.mayara.ui.radar

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.ui.connection.ConnectionPickerDialog
import com.marineyachtradar.mayara.ui.radar.bottomsheet.RadarControlSheet
import com.marineyachtradar.mayara.ui.radar.overlay.HudOverlay
import com.marineyachtradar.mayara.ui.radar.overlay.PowerToggle
import com.marineyachtradar.mayara.ui.radar.overlay.RangeControls
import com.marineyachtradar.mayara.ui.settings.SettingsActivity

/**
 * Root composable for the radar display.
 *
 * Layout (all layers stacked in a [Box]):
 * - Layer 0 (background): [RadarGLView] — OpenGL ES polar radar sweep
 * - Layer 1 (top-left):   [HudOverlay] — Heading / SOG / COG
 * - Layer 2 (top-right):  [PowerToggle] — OFF/WARMUP/STANDBY/TRANSMIT pill
 * - Layer 3 (bottom-right): [RangeControls] — +/- range FABs
 * - Layer 4 (bottom-center): [FloatingActionButton] — opens [RadarControlSheet]
 * - Layer 5 (overlay): [RadarControlSheet] — swipe-up bottom sheet (spec §3.4)
 * - Layer 6 (overlay): [ConnectionPickerDialog] — on first launch or mode switch (spec §2)
 *
 * Gesture rules:
 *  - Single-finger drag → pan radar center
 *  - Double-tap → reset pan to center
 *  - Pinch gesture → **discarded** (zoom via range buttons only; see spec §3.2)
 */
@Composable
fun RadarScreen(
    viewModel: RadarViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val latestSpoke by viewModel.spokeFlow.collectAsState()
    val showControlSheet by viewModel.showControlSheet.collectAsState()
    val showConnectionPicker by viewModel.showConnectionPicker.collectAsState()

    val spokesPerRevolution = (uiState as? RadarUiState.Connected)
        ?.capabilities?.spokesPerRevolution ?: 2048
    val palette = (uiState as? RadarUiState.Connected)
        ?.controls?.palette ?: ColorPalette.GREEN

    val navigationData = (uiState as? RadarUiState.Connected)?.navigationData
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {

        // Layer 0: OpenGL radar canvas (Phase 3)
        RadarGLView(
            latestSpoke = latestSpoke,
            spokesPerRevolution = spokesPerRevolution,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 1: Settings gear icon + HUD (top-left, always visible)
        Column(modifier = Modifier.align(Alignment.TopStart)) {
            IconButton(
                onClick = {
                    context.startActivity(
                        Intent(context, SettingsActivity::class.java)
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            HudOverlay(
                navigationData = navigationData,
            )
        }

        when (uiState) {
            is RadarUiState.Connected -> {
                val connected = uiState as RadarUiState.Connected


                // Layer 2: Power toggle (top-right)
                PowerToggle(
                    powerState = connected.powerState,
                    onPowerAction = { viewModel.onPowerAction(it) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )

                // Layer 3: Range controls (bottom-right)
                RangeControls(
                    ranges = connected.capabilities.ranges,
                    currentIndex = connected.currentRangeIndex,
                    onRangeUp = { viewModel.onRangeUp() },
                    onRangeDown = { viewModel.onRangeDown() },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )

                // Layer 4: Radar settings FAB (bottom-center, spec §3.4)
                FloatingActionButton(
                    onClick = { viewModel.onShowControlSheet() },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Radar Settings",
                    )
                }
            }

            is RadarUiState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to radar…",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is RadarUiState.Error -> {
                val error = uiState as RadarUiState.Error
                Text(
                    text = "Error: ${error.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // Layer 5: Advanced radar control bottom sheet (spec §3.4)
        if (showControlSheet) {
            val connected = uiState as? RadarUiState.Connected
            if (connected != null) {
                RadarControlSheet(
                    controls = connected.controls,
                    onGainChange = { v, auto -> viewModel.onGainChange(v, auto) },
                    onSeaChange = { v, auto -> viewModel.onSeaChange(v, auto) },
                    onRainChange = { v -> viewModel.onRainChange(v) },
                    onInterferenceChange = { idx -> viewModel.onInterferenceChange(idx) },
                    onPaletteChange = { viewModel.onPaletteChange(it) },
                    onOrientationChange = { viewModel.onOrientationChange(it) },
                    onDismiss = { viewModel.onDismissControlSheet() },
                )
            }
        }

        // Layer 6: Connection picker dialog (spec §2)
        if (showConnectionPicker) {
            ConnectionPickerDialog(
                discoveredServers = emptyList(), // Phase 5: wire MdnsScanner
                onConnect = { mode, remember ->
                    viewModel.onConnect(mode, remember)
                },
                onDismiss = { viewModel.onDismissConnectionPicker() },
            )
        }
    }
}
