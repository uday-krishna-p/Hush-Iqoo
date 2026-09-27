package com.hush.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Square canvas. The commander picks a sensor letter, taps where that phone sits, and a dot appears.
 * After a Hush window, an arrow is drawn from the commander's dot (A) toward the strongest sensor.
 * Positions are fractions 0..1 of the canvas so they survive rotation and resizing.
 */
class MapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** letter → (x, y) as fractions of the square. */
    val dots = LinkedHashMap<String, Pair<Float, Float>>()

    /** The letter the next tap will place, or null when taps do nothing. */
    var placing: String? = null
        set(value) { field = value; invalidate() }

    /** Strongest sensor letter after the last window, or null. */
    var strongest: String? = null
        set(value) { field = value; invalidate() }

    /** Where the sound comes from (fractions, may be outside the square), or null. Drawn as a red target. */
    var source: Pair<Float, Float>? = null
        set(value) { field = value; invalidate() }
    /** Uncertainty of [source] as a fraction of the square. */
    var sourceRadius: Float = 0f
        set(value) { field = value; invalidate() }
    /** True when the source is beyond the map: drawn hollow at the edge, direction only. */
    var sourceFar: Boolean = false
        set(value) { field = value; invalidate() }
    /** letter → (map angle clockwise from up, mirror twin or null): the direction each phone hears the knocking (27 Sep). */
    val bearings = LinkedHashMap<String, Pair<Float, Float?>>()

    var onPlaced: ((String) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    init { clipToOutline = true }   // rounded corners from the background shape

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 90, 160) }
    private val strongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58) }
    private val commanderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 90) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * sp; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(91, 103, 118); textSize = 14f * sp; textAlign = Paint.Align.CENTER }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58); style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58); style = Paint.Style.FILL }
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 30); style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
    private val sourceArea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 200, 30, 30); style = Paint.Style.FILL }
    private val sourceArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 30); style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
    private val sourceArrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 30, 30); style = Paint.Style.FILL }
    private val bearingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 20, 120, 140); style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
    private val bearingTwinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 20, 120, 140); style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)   // always square
    }

    // ---- Zoom (27 Sep, team: "make the phones on site minimap zoom in zoom out able") ----
    // Pinch zooms about the fingers (1x-6x), one finger drags while zoomed in, double-tap goes back to 1x.
    // At 1x a one-finger swipe still scrolls the page (the map only keeps the touch once zoomed or pinched).
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private fun px(f: Float) = (f * width - width / 2f) * zoom + width / 2f + panX
    private fun py(f: Float) = (f * width - width / 2f) * zoom + width / 2f + panY
    /** Screen position back to map fractions (for placing a phone by tapping). */
    private fun fx(x: Float) = ((x - panX - width / 2f) / zoom + width / 2f) / width
    private fun fy(y: Float) = ((y - panY - width / 2f) / zoom + width / 2f) / width

    private fun clampPan() {
        val m = width * (zoom - 1f) / 2f   // the zoomed map never leaves the frame
        panX = panX.coerceIn(-m, m); panY = panY.coerceIn(-m, m)
    }

    private val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val newZoom = (zoom * d.scaleFactor).coerceIn(1f, 6f)
            val k = newZoom / zoom
            // Keep the point under the fingers where it is.
            panX = (d.focusX - width / 2f) * (1 - k) + panX * k
            panY = (d.focusY - width / 2f) * (1 - k) + panY * k
            zoom = newZoom
            clampPan(); invalidate()
            return true
        }
    })

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1f) return false
            panX -= dx; panY -= dy
            clampPan(); invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = 1f; panX = 0f; panY = 0f; invalidate()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val letter = placing ?: return false
            dots[letter] = fx(e.x).coerceIn(0f, 1f) to fy(e.y).coerceIn(0f, 1f)
            placing = null
            onPlaced?.invoke(letter)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep the page from scrolling while pinching or dragging a zoomed map.
        if (event.pointerCount > 1 || zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val r = w * 0.045f

        placing?.let { canvas.drawText("Tap where Sensor $it sits", w / 2, w * 0.08f, hintPaint) }
        if (dots.isEmpty() && placing == null) canvas.drawText("Phones appear here once their positions are measured", w / 2, w / 2, hintPaint)
        if (zoom > 1.01f) canvas.drawText("%.1fx · double-tap to reset".format(zoom), w / 2, w - hintPaint.textSize * 0.8f, hintPaint)

        // Arrow from A: toward the located SOURCE when there is one (red), else toward the strongest sensor (green).
        val from = dots["A"]
        val src = source?.let { px(it.first.coerceIn(0.03f, 0.97f)) to py(it.second.coerceIn(0.03f, 0.97f)) }
        val to = if (src == null) strongest?.let { dots[it] }?.let { px(it.first) to py(it.second) } else src
        if (from != null && to != null && (src != null || strongest != "A")) {
            val x1 = px(from.first); val y1 = py(from.second)
            val x2 = to.first; val y2 = to.second
            val len = hypot(x2 - x1, y2 - y1)
            if (len > r * 2) {
                val ang = atan2(y2 - y1, x2 - x1)
                val ex = x2 - cos(ang) * r * 1.6f; val ey = y2 - sin(ang) * r * 1.6f
                canvas.drawLine(x1, y1, ex, ey, if (src != null) sourceArrow else arrowPaint)
                val head = Path()
                val hs = r * 1.2f
                head.moveTo(ex + cos(ang) * hs * 0.6f, ey + sin(ang) * hs * 0.6f)
                head.lineTo(ex + cos(ang + 2.5f) * hs, ey + sin(ang + 2.5f) * hs)
                head.lineTo(ex + cos(ang - 2.5f) * hs, ey + sin(ang - 2.5f) * hs)
                head.close()
                canvas.drawPath(head, if (src != null) sourceArrowHead else arrowHead)
            }
        }
        // The source itself: a translucent disc of its uncertainty and a red cross-hair.
        if (src != null) {
            val sx = src.first; val sy = src.second
            if (!sourceFar) canvas.drawCircle(sx, sy, (sourceRadius * w * zoom).coerceIn(r * 0.8f, w * 0.45f * zoom), sourceArea)
            val k = r * 1.1f
            canvas.drawCircle(sx, sy, k, sourcePaint)
            canvas.drawLine(sx - k * 1.5f, sy, sx + k * 1.5f, sy, sourcePaint)
            canvas.drawLine(sx, sy - k * 1.5f, sx, sy + k * 1.5f, sourcePaint)
            if (sourceFar) canvas.drawText("beyond the map", sx, sy - k * 1.9f, hintPaint)
        }

        // Each phone's own two-mic bearing: a line from its dot (faint twin while left/right is unresolved).
        for ((letter, b) in bearings) {
            val p = dots[letter] ?: continue
            val cx = px(p.first); val cy = py(p.second)
            val len = w * 0.35f * zoom
            b.second?.let { t ->
                val a = Math.toRadians(t.toDouble())
                canvas.drawLine(cx, cy, cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), bearingTwinPaint)
            }
            val a = Math.toRadians(b.first.toDouble())
            canvas.drawLine(cx, cy, cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), bearingPaint)
        }
        for ((letter, p) in dots) {
            val cx = px(p.first); val cy = py(p.second)
            val paint = when {
                letter == strongest -> strongPaint
                letter == "A" -> commanderPaint
                else -> dotPaint
            }
            canvas.drawCircle(cx, cy, if (letter == strongest) r * 1.4f else r, paint)
            canvas.drawText(letter, cx, cy + textPaint.textSize * 0.35f, textPaint)
        }
    }
}
