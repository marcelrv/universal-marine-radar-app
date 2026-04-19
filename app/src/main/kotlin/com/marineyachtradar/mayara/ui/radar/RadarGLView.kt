package com.marineyachtradar.mayara.ui.radar

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarLegend
import com.marineyachtradar.mayara.data.model.SpokeData

/**
 * Holds the current pan offset so that both the GL renderer and the Compose overlay
 * can track the radar centre position.
 */
class RadarPanState {
    var x by mutableFloatStateOf(0f)
        internal set
    var y by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun RadarGLView(
    latestSpoke: SpokeData?,
    spokesPerRevolution: Int,
    palette: ColorPalette,
    legend: RadarLegend? = null,
    powerState: PowerState? = null,
    revolutionCount: Long = 0L,
    panState: RadarPanState? = null,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { RadarGLRenderer() }

    LaunchedEffect(powerState) {
        if (powerState != null && powerState != PowerState.TRANSMIT) {
            renderer.clearAll()
        }
    }

    // Revolution count is tracked but no longer triggers clearAll().
    // Each spoke naturally overwrites the previous data at the same angle,
    // producing the classic radar sweep appearance.

    LaunchedEffect(legend) {
        renderer.setLegendPalette(legend)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            createGLSurfaceView(context, renderer, panState)
        },
        update = { _ ->
            latestSpoke?.let { renderer.updateSpoke(it, spokesPerRevolution) }
            renderer.setPalette(palette)
        }
    )
}

internal fun createGLSurfaceView(
    context: Context,
    renderer: RadarGLRenderer,
    panState: RadarPanState? = null,
): GLSurfaceView {
    val glView = GLSurfaceView(context)
    glView.setEGLContextClientVersion(2)
    glView.setRenderer(renderer)
    glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            val normX = distanceX / glView.width * -1f
            val normY = distanceY / glView.height
            renderer.setCenterOffset(normX, normY)
            panState?.let {
                it.x = renderer.centerX
                it.y = renderer.centerY
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetCenter()
            panState?.let {
                it.x = 0f
                it.y = 0f
            }
            return true
        }
    })

    // Pinch zoom is DISCARDED per spec §3.2. The DisabledPinchZoom listener
    // consumes the scale events but never applies the scale factor to the renderer.
    val scaleDetector = ScaleGestureDetector(context, DisabledPinchZoom())

    glView.setOnTouchListener { _, event ->
        // Feed both detectors; both consume the event.
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        true
    }

    return glView
}

// ---------------------------------------------------------------------------
// Pinch zoom policy (named class — directly unit-testable)
// ---------------------------------------------------------------------------

/**
 * Scale gesture listener that **discards all pinch events**.
 *
 * This is a safety-critical requirement (spec §3.2): zooming must only be
 * possible via the hardware-stepped [+/-] buttons to ensure the on-screen scale
 * exactly reflects the radar's active range.
 *
 * The listener is registered so that pinch events are properly consumed
 * (returning `true` from [onScale]) and do not leak through to other handlers.
 * The scale factor is intentionally never read or applied.
 */
class DisabledPinchZoom : ScaleGestureDetector.SimpleOnScaleGestureListener() {

    /**
     * Returns `true` to consume the scale event. The [detector]'s scale factor
     * is never applied — pinch-to-zoom is unconditionally disabled.
     */
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        // Intentionally do nothing with detector.scaleFactor.
        return true  // consumed — must not propagate to other handlers
    }

    /**
     * Returns `false` to confirm we do NOT want zoom to be applied.
     * Exposed for unit testing via [shouldApplyScale].
     */
    fun shouldApplyScale(@Suppress("UNUSED_PARAMETER") factor: Float): Boolean = false
}
