package com.marineyachtradar.mayara.ui.radar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marineyachtradar.mayara.data.model.ArpaTarget
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ── Colour constants matching webapp ppi.js ─────────────────────────────────
private val COLOR_DANGEROUS  = Color(0xFFFF4444.toInt())   // red   — cpa < 926m & tcpa < 600s
private val COLOR_TRACKING   = Color(0xFFFFFFFF.toInt())   // white — confirmed tracking
private val COLOR_ACQUIRING  = Color(0xFFFFFF00.toInt())   // yellow — not yet confirmed
private val COLOR_LOST       = Color(0xFFFFA500.toInt())   // orange — lost contact

private fun ArpaTarget.displayColor(): Color = when {
    isDangerous          -> COLOR_DANGEROUS
    status == "tracking" -> COLOR_TRACKING
    status == "lost"     -> COLOR_LOST
    else                 -> COLOR_ACQUIRING   // "acquiring" or unknown
}

/**
 * Compose [Canvas] overlay that draws ARPA targets on top of the radar.
 *
 * Coordinate formula mirrors webapp ppi.js:
 * ```
 * cx = width/2 + panX * diameter
 * cy = height/2 - panY * diameter
 * radius = min(width, height) / 2
 * pixelsPerMeter = radius / rangeMeters
 * adjustedBearing = targetBearingRad - headingRotationRad   (heading-up: rotation=0)
 * x = cx + dist_px * sin(adjusted)
 * y = cy - dist_px * cos(adjusted)
 * ```
 *
 * @param targets           Map of target ID → [ArpaTarget].
 * @param currentRangeMeters Current radar range in metres.
 * @param headingRotationRad Heading rotation applied to the GL layer (radians). 0 in HEAD_UP.
 * @param headingRad        Actual ship heading in radians (true). Always the compass heading
 *                          regardless of orientation mode. Used to convert target true bearings
 *                          to screen angles: screenAngle = bearing - headingRad + headingRotationRad
 * @param panX              Horizontal pan offset in fractions of radar diameter.
 * @param panY              Vertical pan offset in fractions of radar diameter.
 * @param zoomLevel         Current zoom factor (1.0 = no zoom).
 * @param onTargetLongPress Called with the target ID when the user long-presses on a target.
 * @param onRadarLongPress  Called with screen (x, y) when a long-press hits empty space.
 */
@Composable
fun TargetOverlayCanvas(
    targets: Map<Long, ArpaTarget>,
    currentRangeMeters: Int,
    headingRotationRad: Float,
    headingRad: Float,
    panX: Float,
    panY: Float,
    zoomLevel: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (targets.isEmpty() || currentRangeMeters <= 0) return@Canvas

        val w = size.width
        val h = size.height
        val diameter = min(w, h)
        val radarRadius = diameter / 2f * zoomLevel
        val cx = w / 2f + panX * diameter
        val cy = h / 2f - panY * diameter
        val pixelsPerMeter = radarRadius / currentRangeMeters

        targets.values.forEach { target ->
            drawTarget(target, cx, cy, pixelsPerMeter, headingRad, headingRotationRad)
        }
    }
}

private fun DrawScope.drawTarget(
    target: ArpaTarget,
    cx: Float,
    cy: Float,
    pixelsPerMeter: Float,
    headingRad: Float,
    headingRotationRad: Float,
) {
    val color = target.displayColor()
    // Convert true bearing → screen angle.
    // The GL shader maps screen angle θ to spoke via: u = (θ - headingRotationRad) / 2π.
    // Spokes are bow-relative, so bowRelative = trueBearing - heading.
    // Therefore: θ = (trueBearing - heading) + headingRotationRad.
    val adjustedBearing = target.bearingRad - headingRad + headingRotationRad
    val distPx = target.distanceMeters * pixelsPerMeter

    val tx = cx + distPx * sin(adjustedBearing).toFloat()
    val ty = cy - distPx * cos(adjustedBearing).toFloat()
    val center = Offset(tx, ty)

    val circleRadius = 8.dp.toPx()
    val strokeWidth = 2.dp.toPx()

    // Filled circle (30% alpha)
    drawCircle(
        color = color.copy(alpha = 0.3f),
        radius = circleRadius,
        center = center,
    )
    // Stroke circle
    drawCircle(
        color = color,
        radius = circleRadius,
        center = center,
        style = Stroke(width = strokeWidth),
    )

    // Course vector (tracking + motion data only)
    if (target.status == "tracking" && target.courseRad != null && target.speedMs != null) {
        val maxVectorPx = 80.dp.toPx()
        val vectorPx = (target.speedMs * 60.0 * pixelsPerMeter).toFloat().coerceAtMost(maxVectorPx)
        val adjustedCourse = target.courseRad - headingRad + headingRotationRad
        val vx = tx + vectorPx * sin(adjustedCourse).toFloat()
        val vy = ty - vectorPx * cos(adjustedCourse).toFloat()

        drawLine(
            color = color,
            start = center,
            end = Offset(vx, vy),
            strokeWidth = strokeWidth,
        )

        // Arrowhead at tip
        val arrowLen = 8.dp.toPx()
        val arrowAngle = 0.4f   // radians half-angle
        val baseBearing = adjustedCourse.toFloat() + PI.toFloat()
        drawLine(
            color = color,
            start = Offset(vx, vy),
            end = Offset(
                vx + arrowLen * sin(baseBearing - arrowAngle),
                vy - arrowLen * cos(baseBearing - arrowAngle),
            ),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(vx, vy),
            end = Offset(
                vx + arrowLen * sin(baseBearing + arrowAngle),
                vy - arrowLen * cos(baseBearing + arrowAngle),
            ),
            strokeWidth = strokeWidth,
        )
    }

    // Label: T{id} to the right of circle
    val labelOffset = circleRadius + 6.dp.toPx()
    val paint = android.graphics.Paint().apply {
        this.color = color.hashCode()
        textSize = 11.sp.toPx()
        typeface = android.graphics.Typeface.MONOSPACE
        isAntiAlias = true
        setARGB(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        "T${target.id}",
        tx + labelOffset,
        ty + 4.dp.toPx(),
        paint,
    )

    // CPA/TCPA for dangerous targets
    if (target.isDangerous && target.cpaMm != null && target.tcpaSec != null) {
        val smallPaint = android.graphics.Paint().apply {
            textSize = 9.sp.toPx()
            isAntiAlias = true
            setARGB(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt(),
            )
        }
        val cpaText = "CPA:${target.cpaMm.toInt()}m T:${target.tcpaSec.toInt()}s"
        drawContext.canvas.nativeCanvas.drawText(
            cpaText,
            tx + labelOffset,
            ty + 4.dp.toPx() + 12.sp.toPx(),
            smallPaint,
        )
    }
}
