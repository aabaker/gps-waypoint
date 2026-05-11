package com.example.gpswaypoint.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * DirectionArrowView.kt
 *
 * Custom [View] that draws a directional arrow indicating the bearing to the
 * next waypoint relative to the device's current heading.
 *
 * When [arrowRotationDeg] = 0 the arrow points straight up (towards the target
 * is directly ahead).  Positive values rotate the arrow clockwise.
 *
 * The arrow is drawn as a filled polygon with a contrasting outline, centred in
 * the view and scaled to fill 80 % of the smaller dimension.
 */
class DirectionArrowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // -------------------------------------------------------------------------
    // Public properties
    // -------------------------------------------------------------------------

    /**
     * Rotation of the arrow in degrees clockwise from screen-up.
     * Setting this property triggers a redraw.
     */
    var arrowRotationDeg: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether the GPS fix is valid.  When false, the arrow is rendered grey
     * to signal "no position data".
     */
    var hasValidFix: Boolean = false
        set(value) {
            field = value
            invalidate()
            arrowPaint.color = if (value) COLOR_ACTIVE else COLOR_NO_FIX
            invalidate()
        }

    // -------------------------------------------------------------------------
    // Drawing resources
    // -------------------------------------------------------------------------

    companion object {
        private val COLOR_ACTIVE = Color.parseColor("#FF6200EE")   // purple accent
        private val COLOR_NO_FIX = Color.parseColor("#FF9E9E9E")   // grey
        private val COLOR_OUTLINE = Color.parseColor("#FFFFFFFF")
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_NO_FIX
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_OUTLINE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val arrowPath = Path()

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    /**
     * Draws the arrow centred in the view, rotated by [arrowRotationDeg].
     *
     * Input:  @param canvas The [Canvas] provided by the framework.
     * Output: Arrow rendered onto [canvas].
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.80f

        buildArrowPath(radius)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(arrowRotationDeg)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.drawPath(arrowPath, outlinePaint)
        canvas.restore()
    }

    /**
     * Constructs the arrow [Path] centred at (0, 0) for the given [radius].
     *
     * The arrow shape consists of:
     *   - A pointed tip at the top (north, 0°)
     *   - A wide base at the bottom
     *   - Two small notches cut inward at the base corners for a chevron look
     *
     * Input:  @param radius Half-size in pixels; the tip of the arrow is at (0, -radius).
     * Output: [arrowPath] is reset and re-built.
     */
    private fun buildArrowPath(radius: Float) {
        val tip     = PointF(0f, -radius)                   // top point
        val bodyW   = radius * 0.40f                        // half-width of body
        val baseY   = radius * 0.55f                        // y of the base corners
        val notchY  = radius * 0.30f                        // y of inner notch
        val tailW   = radius * 0.15f                        // half-width of tail stub

        arrowPath.reset()
        // Tip
        arrowPath.moveTo(tip.x, tip.y)
        // Right shoulder
        arrowPath.lineTo(bodyW, baseY)
        // Right notch in
        arrowPath.lineTo(tailW, notchY)
        // Right tail
        arrowPath.lineTo(tailW, radius * 0.90f)
        // Left tail
        arrowPath.lineTo(-tailW, radius * 0.90f)
        // Left notch in
        arrowPath.lineTo(-tailW, notchY)
        // Left shoulder
        arrowPath.lineTo(-bodyW, baseY)
        arrowPath.close()
    }

    /**
     * Suggest a square aspect ratio so the arrow is never clipped.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }
}
