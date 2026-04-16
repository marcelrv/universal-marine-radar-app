package com.marineyachtradar.mayara.ui.radar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.ui.radar.overlay.HudOverlay
import com.marineyachtradar.mayara.ui.radar.overlay.PowerToggle
import com.marineyachtradar.mayara.ui.radar.overlay.RangeControls

/**
 * Root composable for the radar display.
 *
 * Layout (all layers stacked in a [Box]):
 * - Layer 0 (background): [RadarGLView] — OpenGL ES polar radar sweep
 * - Layer 1 (top-left):   [HudOverlay] — Heading / SOG / COG
 * - Layer 2 (top-right):  [PowerToggle] — OFF/WARMUP/STANDBY/TRANSMIT pill
 * - Layer 3 (bottom-right): [RangeControls] — +/- range FABs
 * - Layer 4 (bottom):     Bottom sheet handle + [RadarControlSheet] (swipe-up)
 *
 * Gesture rules:
 *  - Single-finger drag → pan radar center
 *  - Double-tap → reset pan to center
 *  - Pinch gesture → **discarded** (zoom via range buttons only; see spec §3.2)
 *
 * TODO Phase 3: wire [RadarGLView] and gesture handling.
 * TODO Phase 4: add [RadarControlSheet] bottom sheet.
 */
@Composable
fun RadarScreen(
    // TODO: inject ViewModel when RadarViewModel is implemented (Phase 2)
) {
    // Placeholder state until ViewModel + Repository are wired in Phase 2.
    val uiState: RadarUiState = RadarUiState.Loading

    Box(modifier = Modifier.fillMaxSize()) {

        // Layer 0: OpenGL radar canvas (TODO Phase 3)
        // RadarGLView(modifier = Modifier.fillMaxSize())

        when (uiState) {
            is RadarUiState.Connected -> {
                // Layer 1: HUD (top-left)
                HudOverlay(
                    navigationData = uiState.navigationData,
                    modifier = Modifier.align(Alignment.TopStart),
                )

                // Layer 2: Power toggle (top-right)
                PowerToggle(
                    powerState = uiState.powerState,
                    onPowerAction = { /* TODO: ViewModel.onPowerAction(it) */ },
                    modifier = Modifier.align(Alignment.TopEnd),
                )

                // Layer 3: Range controls (bottom-right)
                RangeControls(
                    ranges = uiState.capabilities.ranges,
                    currentIndex = uiState.currentRangeIndex,
                    onRangeUp = { /* TODO: ViewModel.onRangeUp() */ },
                    onRangeDown = { /* TODO: ViewModel.onRangeDown() */ },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }

            is RadarUiState.Loading -> {
                // TODO Phase 4: Show a loading/connecting overlay
            }

            is RadarUiState.Error -> {
                // TODO Phase 4: Show an error snackbar or dialog
            }
        }
    }
}
