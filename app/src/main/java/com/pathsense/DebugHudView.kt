package com.pathsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * High-performance HUD overlay drawn directly to Canvas at 60 FPS.
 * Renders spatial zones, optical flow vectors, anomaly markers, and telemetry.
 */
class DebugHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detectionResult: DetectionResult? = null

    // Paints
    private val telemetryBgPaint = Paint().apply {
        color = Color.parseColor("#CC0B132B")
        style = Paint.Style.FILL
    }

    private val telemetryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 34f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val anchorZonePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val lookAheadGridPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }

    private val inlierVectorPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val obstacleAnomalyPaint = Paint().apply {
        color = Color.parseColor("#FFAB00") // Amber for raised obstacle
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val potholeAnomalyPaint = Paint().apply {
        color = Color.parseColor("#FF1744") // Crimson for pothole/drop
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val anomalyCirclePaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val alertBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FF1744")
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun updateResult(result: DetectionResult) {
        this.detectionResult = result
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = detectionResult ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val frameW = result.frameWidth.toFloat()
        val frameH = result.frameHeight.toFloat()

        if (frameW <= 0f || frameH <= 0f) return

        val scaleX = viewW / frameW
        val scaleY = viewH / frameH

        // 1. Draw Safe Anchor Zone (Green rectangle at bottom: 0.70 to 0.95 H)
        val anchorTop = 0.70f * viewH
        val anchorBottom = 0.95f * viewH
        canvas.drawRect(0f, anchorTop, viewW, anchorBottom, anchorZonePaint)

        // 2. Draw Look-Ahead Zone (Cyan dashed grid: 0.30 to 0.70 H)
        val lookAheadTop = 0.30f * viewH
        val lookAheadBottom = 0.70f * viewH
        val colWidth = viewW / 3.0f

        canvas.drawRect(0f, lookAheadTop, viewW, lookAheadBottom, lookAheadGridPaint)
        // Vertical divider lines for 3 buckets
        canvas.drawLine(colWidth, lookAheadTop, colWidth, lookAheadBottom, lookAheadGridPaint)
        canvas.drawLine(2 * colWidth, lookAheadTop, 2 * colWidth, lookAheadBottom, lookAheadGridPaint)

        // 3. Draw Tracked Optical Flow Vectors
        for (vec in result.vectors) {
            val startX = vec.prevX * scaleX
            val startY = vec.prevY * scaleY
            val endX = vec.currX * scaleX
            val endY = vec.currY * scaleY

            if (vec.isAnomaly) {
                val paint = if (vec.hazardType == HazardType.ELEVATED_OBSTACLE) {
                    obstacleAnomalyPaint
                } else {
                    potholeAnomalyPaint
                }
                // Draw extended flow line
                canvas.drawLine(startX, startY, endX, endY, paint)
                anomalyCirclePaint.color = paint.color
                canvas.drawCircle(endX, endY, 8f, anomalyCirclePaint)
            } else if (vec.isAnchor && vec.isAnchorInlier) {
                canvas.drawLine(startX, startY, endX, endY, inlierVectorPaint)
            }
        }

        // 4. Draw Active Alerts (if any)
        if (result.alerts.isNotEmpty()) {
            val alert = result.alerts.first()
            val hazardStr = when (alert.hazardType) {
                HazardType.ELEVATED_OBSTACLE -> "OBSTACLE DETECTED"
                HazardType.POTHOLE_DROP -> "POTHOLE / DROP AHEAD"
                else -> "HAZARD"
            }
            val alertText = "⚠️ [${alert.bucket.name}] $hazardStr"
            canvas.drawText(alertText, viewW / 2.0f, 180f, alertBannerPaint)
        }

        // 5. Draw Top Telemetry HUD Box
        val hudBg = RectF(20f, 30f, viewW - 20f, 130f)
        canvas.drawRoundRect(hudBg, 16f, 16f, telemetryBgPaint)

        val telemetryText = String.format(
            "FPS: %.1f | %d ms | Tracks: %d | Inliers: %.0f%% | %s",
            result.fps,
            result.frameLatencyMs,
            result.validTracks,
            result.inlierRatio * 100f,
            result.state.name.removePrefix("STATE_")
        )
        canvas.drawText(telemetryText, 35f, 90f, telemetryTextPaint)
    }
}
