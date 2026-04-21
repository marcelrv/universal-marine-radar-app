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
import com.marineyachtradar.mayara.data.model.SpokeData
import com.marineyachtradar.mayara.ui.connection.ConnectionPickerDialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.marineyachtradar.mayara.ui.radar.RadarGLRenderer.Companion.DEFAULT_TEXTURE_ANGLE_SIZE
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
    val revolutionCount by viewModel.revolutionCount.collectAsState()
    val showControlSheet by viewModel.showControlSheet.collectAsState()
    val showConnectionPicker by viewModel.showConnectionPicker.collectAsState()
    val showRadarPicker by viewModel.showRadarPicker.collectAsState()
    val availableRadars by viewModel.availableRadars.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()

    val spokesPerRevolution = (uiState as? RadarUiState.Connected)
        ?.capabilities?.spokesPerRevolution ?: RadarGLRenderer.DEFAULT_TEXTURE_ANGLE_SIZE
    val maxSpokeLength = (uiState as? RadarUiState.Connected)
        ?.capabilities?.maxSpokeLength ?: RadarGLRenderer.DEFAULT_TEXTURE_RANGE_SIZE
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
    val spokeGapFill = (uiState as? RadarUiState.Connected)?.controls?.spokeGapFill ?: false
    val targets by viewModel.targets.collectAsState()
    val context = LocalContext.current
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val panState = remember { RadarPanState() }

    // Actual ship heading in radians — always compass heading regardless of orientation mode.
    // Used by ARPA target rendering to convert true bearings to screen angles.
    val headingRad = Math.toRadians((navigationData?.headingDeg ?: 0f).toDouble()).toFloat()

    // Compute GL shader rotation (radians) and compass-rose rotation input (degrees).
    // northUp / courseUp: shader rotates the sweep so North/Course is at screen top.
    // headingUp: no shader rotation; the compass rose labels rotate instead.
    val headingRotationRad: Float
    val compassHeadingDeg: Float?
    when (orientation) {
        RadarOrientation.NORTH_UP -> {
            val h = navigationData?.headingDeg ?: 0f
            headingRotationRad = Math.toRadians(h.toDouble()).toFloat()
            compassHeadingDeg = null   // rose is static (N at top)
        }
        RadarOrientation.COURSE_UP -> {
            val c = navigationData?.cogDeg ?: navigationData?.headingDeg ?: 0f
            headingRotationRad = Math.toRadians(c.toDouble()).toFloat()
            compassHeadingDeg = null   // rose is static (course at top)
        }
        RadarOrientation.HEAD_UP -> {
            headingRotationRad = 0f
            compassHeadingDeg = navigationData?.headingDeg  // rose rotates by -heading
        }
    }

    if (isPortrait) {
        PortraitRadarLayout(
            viewModel = viewModel,
            uiState = uiState,
            spokeFlow = viewModel.spokeFlow,
            revolutionCount = revolutionCount,
            spokesPerRevolution = spokesPerRevolution,
            maxSpokeLength = maxSpokeLength,
            palette = palette,
            legend = legend,
            navigationData = navigationData,
            connectionLabel = connectionLabel,
            radarName = radarName,
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            headingRotationRad = headingRotationRad,
            headingRad = headingRad,
            compassHeadingDeg = compassHeadingDeg,
            spokeGapFill = spokeGapFill,
            panState = panState,
            context = context,
            targets = targets,
        )
    } else {
        LandscapeRadarLayout(
            viewModel = viewModel,
            uiState = uiState,
            spokeFlow = viewModel.spokeFlow,
            revolutionCount = revolutionCount,
            spokesPerRevolution = spokesPerRevolution,
            maxSpokeLength = maxSpokeLength,
            palette = palette,
            legend = legend,
            navigationData = navigationData,
            connectionLabel = connectionLabel,
            radarName = radarName,
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            headingRotationRad = headingRotationRad,
            headingRad = headingRad,
            compassHeadingDeg = compassHeadingDeg,
            spokeGapFill = spokeGapFill,
            panState = panState,
            context = context,
            targets = targets,
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
                onSpokeGapFillChange = { viewModel.onSpokeGapFillChange(it) },
                onClearAllTargets = { viewModel.onClearAllTargets() },
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
    spokeFlow: kotlinx.coroutines.flow.Flow<SpokeData>,
    revolutionCount: Long,
    spokesPerRevolution: Int,
    maxSpokeLength: Int,
    palette: ColorPalette,
    legend: com.marineyachtradar.mayara.data.model.RadarLegend?,
    navigationData: com.marineyachtradar.mayara.data.model.NavigationData?,
    connectionLabel: String,
    radarName: String,
    ranges: List<Int>,
    currentRangeIndex: Int,
    distanceUnit: DistanceUnit,
    orientation: RadarOrientation,
    headingRotationRad: Float,
    headingRad: Float,
    compassHeadingDeg: Float?,
    spokeGapFill: Boolean,
    panState: RadarPanState,
    context: android.content.Context,
    targets: Map<Long, com.marineyachtradar.mayara.data.model.ArpaTarget> = emptyMap(),
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RadarGLView(
            spokeFlow = spokeFlow,
            spokesPerRevolution = spokesPerRevolution,
            maxSpokeLength = maxSpokeLength,
            palette = palette,
            legend = legend,
            powerState = (uiState as? RadarUiState.Connected)?.powerState,
            revolutionCount = revolutionCount,
            currentRangeIndex = currentRangeIndex,
            spokeGapFill = spokeGapFill,
            headingRotationRad = headingRotationRad,
            panState = panState,
            modifier = Modifier.fillMaxSize(),
        )
        RadarOverlayCanvas(
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            headingDeg = compassHeadingDeg,
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

        // Target overlay (ARPA/MARPA) — pure drawing; long-press handled by GLSurfaceView.
        val currentRangeMetersLandscape = ranges.getOrNull(currentRangeIndex) ?: 0
        panState.onLongPress = { x, y ->
            val w = panState.viewWidth
            val h = panState.viewHeight
            val diameter = min(w, h)
            val radarRadius = diameter / 2f * panState.zoom
            val cx = w / 2f + panState.x * diameter
            val cy = h / 2f - panState.y * diameter
            val pixelsPerMeter = if (currentRangeMetersLandscape > 0) radarRadius / currentRangeMetersLandscape else 1f
            val hitTarget = targets.values.firstOrNull { target ->
                val ab = (target.bearingRad - headingRad + headingRotationRad).toFloat()
                val distPx = target.distanceMeters * pixelsPerMeter
                val tx = cx + distPx * sin(ab)
                val ty = cy - distPx * cos(ab)
                sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty)) <= 24f
            }
            if (hitTarget != null) {
                viewModel.onDeleteTarget(hitTarget.id)
            } else {
                val dx = x - cx
                val dy = y - cy
                val distMeters = sqrt(dx * dx + dy * dy) / pixelsPerMeter
                val screenAngle = atan2(dx, -dy)
                val twoPi = (2.0 * Math.PI).toFloat()
                val bearingRad = ((screenAngle + headingRad - headingRotationRad) % twoPi + twoPi) % twoPi
                viewModel.onLongPress(bearingRad.toDouble(), distMeters.toDouble())
            }
        }
        TargetOverlayCanvas(
            targets = targets,
            currentRangeMeters = currentRangeMetersLandscape,
            headingRotationRad = headingRotationRad,
            headingRad = headingRad,
            panX = panState.x,
            panY = panState.y,
            zoomLevel = panState.zoom,
            modifier = Modifier.fillMaxSize(),
        )

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
    spokeFlow: kotlinx.coroutines.flow.Flow<SpokeData>,
    revolutionCount: Long,
    spokesPerRevolution: Int,
    maxSpokeLength: Int,
    palette: ColorPalette,
    legend: com.marineyachtradar.mayara.data.model.RadarLegend?,
    navigationData: com.marineyachtradar.mayara.data.model.NavigationData?,
    connectionLabel: String,
    radarName: String,
    ranges: List<Int>,
    currentRangeIndex: Int,
    distanceUnit: DistanceUnit,
    orientation: RadarOrientation,
    headingRotationRad: Float,
    headingRad: Float,
    compassHeadingDeg: Float?,
    spokeGapFill: Boolean,
    panState: RadarPanState,
    context: android.content.Context,
    targets: Map<Long, com.marineyachtradar.mayara.data.model.ArpaTarget> = emptyMap(),
) {
    Box(modifier = Modifier.fillMaxSize()) {
        RadarGLView(
            spokeFlow = spokeFlow,
            spokesPerRevolution = spokesPerRevolution,
            maxSpokeLength = maxSpokeLength,
            palette = palette,
            legend = legend,
            powerState = (uiState as? RadarUiState.Connected)?.powerState,
            revolutionCount = revolutionCount,
            currentRangeIndex = currentRangeIndex,
            spokeGapFill = spokeGapFill,
            headingRotationRad = headingRotationRad,
            panState = panState,
            modifier = Modifier.fillMaxSize(),
        )
        RadarOverlayCanvas(
            ranges = ranges,
            currentRangeIndex = currentRangeIndex,
            distanceUnit = distanceUnit,
            orientation = orientation,
            headingDeg = compassHeadingDeg,
            panX = panState.x,
            panY = panState.y,
            zoomLevel = panState.zoom,
            modifier = Modifier.fillMaxSize(),
        )

        // Target overlay (ARPA/MARPA) — pure drawing; long-press handled by GLSurfaceView.
        val currentRangeMetersPortrait = ranges.getOrNull(currentRangeIndex) ?: 0
        panState.onLongPress = { x, y ->
            val w = panState.viewWidth
            val h = panState.viewHeight
            val diameter = min(w, h)
            val radarRadius = diameter / 2f * panState.zoom
            val cx = w / 2f + panState.x * diameter
            val cy = h / 2f - panState.y * diameter
            val pixelsPerMeter = if (currentRangeMetersPortrait > 0) radarRadius / currentRangeMetersPortrait else 1f
            val hitTarget = targets.values.firstOrNull { target ->
                val ab = (target.bearingRad - headingRad + headingRotationRad).toFloat()
                val distPx = target.distanceMeters * pixelsPerMeter
                val tx = cx + distPx * sin(ab)
                val ty = cy - distPx * cos(ab)
                sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty)) <= 24f
            }
            if (hitTarget != null) {
                viewModel.onDeleteTarget(hitTarget.id)
            } else {
                val dx = x - cx
                val dy = y - cy
                val distMeters = sqrt(dx * dx + dy * dy) / pixelsPerMeter
                val screenAngle = atan2(dx, -dy)
                val twoPi = (2.0 * Math.PI).toFloat()
                val bearingRad = ((screenAngle + headingRad - headingRotationRad) % twoPi + twoPi) % twoPi
                viewModel.onLongPress(bearingRad.toDouble(), distMeters.toDouble())
            }
        }
        TargetOverlayCanvas(
            targets = targets,
            currentRangeMeters = currentRangeMetersPortrait,
            headingRotationRad = headingRotationRad,
            headingRad = headingRad,
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
