package com.marineyachtradar.mayara.ui.radar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import com.marineyachtradar.mayara.data.model.DistanceUnit
import com.marineyachtradar.mayara.ui.radar.overlay.RangeFormatter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val TICK_COLOR = Color(0xBBCCCCCC)      // light grey, semi-transparent
private val LABEL_COLOR = Color(0xDDCCCCCC)     // slightly brighter for labels
private val NORTH_COLOR = Color(0xFFFF4444.toInt())   // red for N marker
private val RING_LABEL_COLOR = Color(0xBBCCCCCC)

/**
 * Compose Canvas overlay drawn on top of the GL radar.
 *
 * Draws:
 * - **Compass rose**: tick marks every 10°, labels every 30° (N, 30, 60, …, 330)
 * - **Range ring labels**: range value at 45° on each ring (25 %, 50 %, 75 %)
 */
@Composable
fun RadarOverlayCanvas(
    ranges: List<Int>,
    currentRangeIndex: Int,
    distanceUnit: DistanceUnit,
    panX: Float = 0f,
    panY: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val currentRange = ranges.getOrNull(currentRangeIndex) ?: 0

    Canvas(modifier = modifier) {
        val radarDiameter = min(size.width, size.height)
        val cx = size.width / 2f + panX * radarDiameter
        val cy = size.height / 2f - panY * radarDiameter
        val radius = radarDiameter / 2f

        drawCompassRose(cx, cy, radius)
        if (currentRange > 0) {
            drawRangeLabels(cx, cy, radius, currentRange, distanceUnit)
        }
    }
}

/**
 * Draw compass rose: ticks every 10°, labels every 30°.
 */
private fun DrawScope.drawCompassRose(cx: Float, cy: Float, radius: Float) {
    val tickOuterRadius = radius * 0.99f
    val majorTickLength = radius * 0.06f    // 30° ticks
    val minorTickLength = radius * 0.03f    // 10° ticks
    val labelRadius = radius * 0.90f        // where labels sit

    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(0xDD, 0xCC, 0xCC, 0xCC)
        textSize = 12.sp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    val northPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(0xFF, 0xFF, 0x44, 0x44)
        textSize = 14.sp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    for (deg in 0 until 360 step 10) {
        val radians = Math.toRadians(deg.toDouble()).toFloat()
        val sinA = sin(radians)
        val cosA = cos(radians)

        val isMajor = deg % 30 == 0
        val tickLen = if (isMajor) majorTickLength else minorTickLength
        val tickWidth = if (isMajor) 2f else 1f

        // Ticks point inward from the outer edge
        val outerX = cx + tickOuterRadius * sinA
        val outerY = cy - tickOuterRadius * cosA
        val innerX = cx + (tickOuterRadius - tickLen) * sinA
        val innerY = cy - (tickOuterRadius - tickLen) * cosA

        drawLine(
            color = if (deg == 0) NORTH_COLOR else TICK_COLOR,
            start = Offset(outerX, outerY),
            end = Offset(innerX, innerY),
            strokeWidth = tickWidth,
        )

        // Labels every 30°
        if (isMajor) {
            val lx = cx + labelRadius * sinA
            val ly = cy - labelRadius * cosA
            val label = if (deg == 0) "N" else deg.toString()
            val paint = if (deg == 0) northPaint else labelPaint

            // Vertically center the text
            val textOffset = -(paint.ascent() + paint.descent()) / 2f
            drawContext.canvas.nativeCanvas.drawText(label, lx, ly + textOffset, paint)
        }
    }
}

/**
 * Draw range labels at the 45° position on each range ring (25 %, 50 %, 75 %).
 */
private fun DrawScope.drawRangeLabels(
    cx: Float,
    cy: Float,
    radius: Float,
    currentRange: Int,
    distanceUnit: DistanceUnit,
) {
    val ringFractions = floatArrayOf(0.25f, 0.50f, 0.75f)
    val labelAngle = Math.toRadians(45.0).toFloat()

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(0xBB, 0xCC, 0xCC, 0xCC)
        textSize = 11.sp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    for (frac in ringFractions) {
        val ringRadius = radius * frac
        val rangeMetres = (currentRange * frac).toInt()
        val label = RangeFormatter.format(rangeMetres, distanceUnit)

        val lx = cx + ringRadius * sin(labelAngle)
        val ly = cy - ringRadius * cos(labelAngle)

        val textOffset = -(paint.ascent() + paint.descent()) / 2f
        drawContext.canvas.nativeCanvas.drawText(label, lx, ly + textOffset, paint)
    }
}
