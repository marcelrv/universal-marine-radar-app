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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.marineyachtradar.mayara.data.model.ColorPalette
import com.marineyachtradar.mayara.data.model.PowerState
import com.marineyachtradar.mayara.data.model.RadarLegend
import com.marineyachtradar.mayara.data.model.SpokeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Holds the current pan offset and zoom level so that both the GL renderer
 * and the Compose overlay can track the radar view state.
 */
class RadarPanState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    /** Reset pan and zoom to defaults (called on range change). */
    fun reset() {
        x = 0f
        y = 0f
        zoom = 1f
    }
}

@Composable
fun RadarGLView(
    spokeFlow: Flow<SpokeData>,
    spokesPerRevolution: Int,
    maxSpokeLength: Int = RadarGLRenderer.DEFAULT_TEXTURE_RANGE_SIZE,
    palette: ColorPalette,
    legend: RadarLegend? = null,
    powerState: PowerState? = null,
    revolutionCount: Long = 0L,
    currentRangeIndex: Int = 0,
    spokeGapFill: Boolean = false,
    headingRotationRad: Float = 0f,
    panState: RadarPanState? = null,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { RadarGLRenderer() }
    // Holds the GLSurfaceView once created by AndroidView.factory so the coroutine can post to it.
    val glSurfaceViewRef = remember { mutableStateOf<GLSurfaceView?>(null) }

    // Configure texture dimensions to match radar's capabilities.
    LaunchedEffect(spokesPerRevolution, maxSpokeLength) {
        renderer.configureForRadar(spokesPerRevolution, maxSpokeLength)
    }

    LaunchedEffect(powerState) {
        if (powerState != null && powerState != PowerState.TRANSMIT) {
            renderer.clearAll()
        }
    }

    // Reset pan and zoom when range changes so the full new range is visible.
    // Clear old spoke data — it was captured at the previous range and is now invalid.
    LaunchedEffect(currentRangeIndex) {
        renderer.clearAll()
        renderer.resetCenter()
        renderer.resetZoom()
        panState?.reset()
    }

    LaunchedEffect(legend) {
        renderer.setLegendPalette(legend)
    }

    LaunchedEffect(spokeGapFill) {
        renderer.fillGapsEnabled = spokeGapFill
    }

    // Update heading rotation whenever orientation mode or navigation data changes.
    // headingRotationRad is @Volatile, so just assigning it is safe from any thread.
    LaunchedEffect(headingRotationRad) {
        renderer.headingRotationRad = headingRotationRad
    }

    // Collect every spoke on a background thread and post each to the GL thread via
    // queueEvent.  This bypasses Compose state entirely, so:
    //   1. No spokes are dropped (SharedFlow is non-conflating).
    //   2. The main thread is never blocked by texture writes.
    //   3. Gap fill sees the true inter-spoke gaps (usually 1–2 angles).
    LaunchedEffect(spokeFlow, spokesPerRevolution) {
        withContext(Dispatchers.Default) {
            spokeFlow.collect { spoke ->
                val glSv = glSurfaceViewRef.value ?: return@collect
                glSv.queueEvent { renderer.updateSpoke(spoke, spokesPerRevolution) }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            createGLSurfaceView(context, renderer, panState).also { glSurfaceViewRef.value = it }
        },
        update = { _ ->
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
            renderer.resetZoom()
            panState?.reset()
            return true
        }
    })

    // Pinch-to-zoom: magnifies the radar view around the current center.
    // Zoom resets to 1× on range change (handled by LaunchedEffect in RadarGLView).
    val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.applyZoom(detector.scaleFactor)
            panState?.let { it.zoom = renderer.zoomLevel }
            return true
        }
    })

    glView.setOnTouchListener { _, event ->
        // Feed both detectors; both consume the event.
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        true
    }

    return glView
}
