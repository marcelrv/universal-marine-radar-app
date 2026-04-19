package com.marineyachtradar.mayara.ui.radar

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.DistanceUnit
import com.marineyachtradar.mayara.data.model.RadarOrientation
import com.marineyachtradar.mayara.data.model.RadarUiState
import com.marineyachtradar.mayara.ui.connection.ConnectionPickerDialog
import com.marineyachtradar.mayara.ui.radar.bottomsheet.RadarControlSheet
import com.marineyachtradar.mayara.ui.radar.overlay.HudOverlay
import com.marineyachtradar.mayara.ui.radar.overlay.PowerToggle
import com.marineyachtradar.mayara.ui.radar.overlay.RadarNamePill
import com.marineyachtradar.mayara.ui.radar.overlay.RadarPickerDialog
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
 *  - Double-tap → reset pan and zoom to center
 *  - Pinch gesture → visual zoom (resets on range change)
 */
@Composable
fun RadarScreen(
    viewModel: RadarViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val latestSpoke by viewModel.spokeFlow.collectAsState()
    val revolutionCount by viewModel.revolutionCount.collectAsState()
    val showControlSheet by viewModel.showControlSheet.collectAsState()
    val showConnectionPicker by viewModel.showConnectionPicker.collectAsState()
    val showRadarPicker by viewModel.showRadarPicker.collectAsState()
    val availableRadars by viewModel.availableRadars.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()

    val spokesPerRevolution = (uiState as? RadarUiState.Connected)
        ?.capabilities?.spokesPerRevolution ?: 2048
    val palette = (uiState as? RadarUiState.Connected)
        ?.controls?.palette ?: ColorPalette.GREEN

    val navigationData = (uiState as? RadarUiState.Connected)?.navigationData
    val connectionLabel = (uiState as? RadarUiState.Connected)?.connectionLabel ?: ""
    val radarName = (uiState as? RadarUiState.Connected)?.radar?.name ?: ""
    val currentRadarId = (uiState as? RadarUiState.Connected)?.radar?.id ?: ""
    val legend = (uiState as? RadarUiState.Connected)?.capabilities?.legend
    val ranges = (uiState as? RadarUiState.Connected)?.capabilities?.ranges ?: emptyList()
    val currentRangeIndex = (uiState as? RadarUiState.Connected)?.currentRangeIndex ?: 0
    val orientation = (uiState as? RadarUiState.Connected)?.controls?.orientation ?: RadarOrientation.HEAD_UP
    val context = LocalContext.current
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val panState = remember { RadarPanState() }

    if (isPortrait) {
        PortraitRadarLayout(
            viewModel = viewModel,
            uiState = uiState,
            latestSpoke = latestSpoke,
            revolutionCount = revolutionCount,
            spokesPerRevolution = spokesPerRevolution,
            palette = palette,
            legend = legend,
            navigationData = navigationData,
            connectionLabel = connectionLabel,
            radarName = radarName,
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            panState = panState,
            context = context,
        )
    } else {
        LandscapeRadarLayout(
            viewModel = viewModel,
            uiState = uiState,
            latestSpoke = latestSpoke,
            revolutionCount = revolutionCount,
            spokesPerRevolution = spokesPerRevolution,
            palette = palette,
            legend = legend,
            navigationData = navigationData,
            connectionLabel = connectionLabel,
            radarName = radarName,
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            panState = panState,
            context = context,
        )
    }

    // Overlay dialogs (orientation-independent)
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
        val lastHost by viewModel.lastNetworkHost.collectAsState()
        val lastPort by viewModel.lastNetworkPort.collectAsState()
        val discoveredServers by viewModel.discoveredServers.collectAsState()

        ConnectionPickerDialog(
            discoveredServers = discoveredServers,
            initialHost = lastHost,
            initialPort = lastPort,
            onConnect = { mode, remember ->
                viewModel.onConnect(mode, remember)
            },
            onDismiss = { viewModel.onDismissConnectionPicker() },
        )
    }

    // Layer 7: Radar picker dialog (BF-05)
    if (showRadarPicker) {
        RadarPickerDialog(
            radars = availableRadars,
            currentRadarId = currentRadarId,
            onSelect = { viewModel.onSwitchRadar(it) },
            onDismiss = { viewModel.onDismissRadarPicker() },
        )
    }
}

// ── Landscape layout: overlaid controls on top of full-screen radar ──

@Composable
private fun LandscapeRadarLayout(
    viewModel: RadarViewModel,
    uiState: RadarUiState,
    latestSpoke: com.marineyachtradar.mayara.data.model.SpokeData?,
    revolutionCount: Long,
    spokesPerRevolution: Int,
    palette: ColorPalette,
    legend: com.marineyachtradar.mayara.data.model.RadarLegend?,
    navigationData: com.marineyachtradar.mayara.data.model.NavigationData?,
    connectionLabel: String,
    radarName: String,
    ranges: List<Int>,
    currentRangeIndex: Int,
    distanceUnit: DistanceUnit,
    orientation: RadarOrientation,
    panState: RadarPanState,
    context: android.content.Context,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RadarGLView(
            latestSpoke = latestSpoke,
            spokesPerRevolution = spokesPerRevolution,
            palette = palette,
            legend = legend,
            powerState = (uiState as? RadarUiState.Connected)?.powerState,
            revolutionCount = revolutionCount,
            currentRangeIndex = currentRangeIndex,
            panState = panState,
            modifier = Modifier.fillMaxSize(),
        )
        RadarOverlayCanvas(
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            panX = panState.x,
            panY = panState.y,
            zoomLevel = panState.zoom,
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar: gear (left) + name pill (center) + power toggle (right) — all vertically centred
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (radarName.isNotBlank()) {
                RadarNamePill(
                    radarName = radarName,
                    onTapped = { viewModel.onRadarNameTapped() },
                )
            }
            if (uiState is RadarUiState.Connected) {
                val connected = uiState as RadarUiState.Connected
                PowerToggle(
                    powerState = connected.powerState,
                    onPowerAction = { viewModel.onPowerAction(it) },
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // Top-left (below top bar): HUD overlay
        HudOverlay(
            navigationData = navigationData,
            connectionLabel = connectionLabel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 56.dp),
        )

        // Bottom-left: Tune FAB + orientation label (mirrors RangeControls at bottom-right)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (uiState is RadarUiState.Connected) {
                FloatingActionButton(
                    onClick = { viewModel.onShowControlSheet() },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = "Radar Settings")
                }
            }
            Text(
                text = orientationAbbreviation(orientation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        when (uiState) {
            is RadarUiState.Connected -> {
                RangeControls(
                    ranges = uiState.capabilities.ranges,
                    currentIndex = uiState.currentRangeIndex,
                    onRangeUp = { viewModel.onRangeUp() },
                    onRangeDown = { viewModel.onRangeDown() },
                    distanceUnit = distanceUnit,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
            is RadarUiState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Connecting to radar…", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is RadarUiState.Error -> {
                Text(
                    text = "Error: ${(uiState as RadarUiState.Error).message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

// ── Portrait layout: radar square + controls below ──

@Composable
private fun PortraitRadarLayout(
    viewModel: RadarViewModel,
    uiState: RadarUiState,
    latestSpoke: com.marineyachtradar.mayara.data.model.SpokeData?,
    revolutionCount: Long,
    spokesPerRevolution: Int,
    palette: ColorPalette,
    legend: com.marineyachtradar.mayara.data.model.RadarLegend?,
    navigationData: com.marineyachtradar.mayara.data.model.NavigationData?,
    connectionLabel: String,
    radarName: String,
    ranges: List<Int>,
    currentRangeIndex: Int,
    distanceUnit: DistanceUnit,
    orientation: RadarOrientation,
    panState: RadarPanState,
    context: android.content.Context,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RadarGLView(
            latestSpoke = latestSpoke,
            spokesPerRevolution = spokesPerRevolution,
            palette = palette,
            legend = legend,
            powerState = (uiState as? RadarUiState.Connected)?.powerState,
            revolutionCount = revolutionCount,
            currentRangeIndex = currentRangeIndex,
            panState = panState,
            modifier = Modifier.fillMaxSize(),
        )
        RadarOverlayCanvas(
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            panX = panState.x,
            panY = panState.y,
            zoomLevel = panState.zoom,
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar: gear (left) + name pill (center) + power toggle (right) — all vertically centred
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (radarName.isNotBlank()) {
                RadarNamePill(
                    radarName = radarName,
                    onTapped = { viewModel.onRadarNameTapped() },
                )
            }
            if (uiState is RadarUiState.Connected) {
                PowerToggle(
                    powerState = uiState.powerState,
                    onPowerAction = { viewModel.onPowerAction(it) },
                )
            } else {
                // Placeholder to keep name pill centred
                Spacer(Modifier.size(48.dp))
            }
        }

        // Bottom-left: Tune FAB + orientation label (mirrors RangeControls at bottom-right)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (uiState is RadarUiState.Connected) {
                FloatingActionButton(
                    onClick = { viewModel.onShowControlSheet() },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = "Radar Settings")
                }
            }
            Text(
                text = orientationAbbreviation(orientation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        when (uiState) {
            is RadarUiState.Connected -> {
                RangeControls(
                    ranges = uiState.capabilities.ranges,
                    currentIndex = uiState.currentRangeIndex,
                    onRangeUp = { viewModel.onRangeUp() },
                    onRangeDown = { viewModel.onRangeDown() },
                    distanceUnit = distanceUnit,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
                HudOverlay(
                    navigationData = navigationData,
                    connectionLabel = connectionLabel,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 8.dp),
                )
            }
            is RadarUiState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Connecting to radar…", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is RadarUiState.Error -> {
                Text(
                    text = "Error: ${(uiState as RadarUiState.Error).message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** Abbreviated orientation label matching mayara web UI conventions. */
private fun orientationAbbreviation(orientation: RadarOrientation): String = when (orientation) {
    RadarOrientation.HEAD_UP -> "H Up"
    RadarOrientation.NORTH_UP -> "N Up"
    RadarOrientation.COURSE_UP -> "C Up"
}