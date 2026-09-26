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

    var onPlaced: ((String) -> Unit)? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 90, 160) }
    private val strongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58) }
    private val commanderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 90, 90) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 120); textSize = 34f; textAlign = Paint.Align.CENTER }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58); style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58); style = Paint.Style.FILL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)   // always square
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val letter = placing ?: return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            dots[letter] = (event.x / width).coerceIn(0f, 1f) to (event.y / height).coerceIn(0f, 1f)
            placing = null
            onPlaced?.invoke(letter)
            invalidate()
            return true
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        canvas.drawColor(Color.rgb(245, 245, 245))
        canvas.drawRect(2f, 2f, w - 2f, w - 2f, framePaint)
        val r = w * 0.045f

        placing?.let { canvas.drawText("Tap where Sensor $it sits", w / 2, w * 0.08f, hintPaint) }
        if (dots.isEmpty() && placing == null) canvas.drawText("Tap a sensor row, then tap its spot here", w / 2, w / 2, hintPaint)

        // Arrow from A toward the strongest, if both are placed.
        val from = dots["A"]
        val to = strongest?.let { dots[it] }
        if (from != null && to != null && strongest != "A") {
            val x1 = from.first * w; val y1 = from.second * w
            val x2 = to.first * w; val y2 = to.second * w
            val len = hypot(x2 - x1, y2 - y1)
            if (len > r * 2) {
                val ang = atan2(y2 - y1, x2 - x1)
                val ex = x2 - cos(ang) * r * 1.6f; val ey = y2 - sin(ang) * r * 1.6f
                canvas.drawLine(x1, y1, ex, ey, arrowPaint)
                val head = Path()
                val hs = r * 1.2f
                head.moveTo(ex + cos(ang) * hs * 0.6f, ey + sin(ang) * hs * 0.6f)
                head.lineTo(ex + cos(ang + 2.5f) * hs, ey + sin(ang + 2.5f) * hs)
                head.lineTo(ex + cos(ang - 2.5f) * hs, ey + sin(ang - 2.5f) * hs)
                head.close()
                canvas.drawPath(head, arrowHead)
            }
        }

        for ((letter, p) in dots) {
            val cx = p.first * w; val cy = p.second * w
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
