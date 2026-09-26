package com.hush.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A big arrow that rotates on screen. [angleDeg] is clockwise from the top of the phone. */
class ArrowView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var angleDeg: Float = 0f
        set(value) { field = value; invalidate() }
    var label: String = ""
        set(value) { field = value; invalidate() }
    var active: Boolean = false
        set(value) { field = value; invalidate() }
    /** A second, faint arrow: the mirror candidate while the two-mic direction is not yet resolved. */
    var twinAngleDeg: Float? = null
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 138, 58) }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 190, 190) }
    private val twin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 27, 138, 58) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 210, 210); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); textSize = 44f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.6f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.45f
        val r = min(width, height) * 0.38f
        canvas.drawCircle(cx, cy, r, ring)
        twinAngleDeg?.let { drawArrow(canvas, cx, cy, r, it, twin) }
        drawArrow(canvas, cx, cy, r, angleDeg, if (active) fill else dim)
        canvas.drawText(label, cx, height - 12f, text)
    }

    /** One arrow from the centre towards [deg] (clockwise from the top). */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, deg: Float, paint: Paint) {
        val a = Math.toRadians(deg.toDouble() - 90)
        val tipX = cx + cos(a).toFloat() * r; val tipY = cy + sin(a).toFloat() * r
        val tailX = cx - cos(a).toFloat() * r * 0.55f; val tailY = cy - sin(a).toFloat() * r * 0.55f
        val p = Path()
        val w = r * 0.28f
        val px = -sin(a).toFloat(); val py = cos(a).toFloat()
        p.moveTo(tipX, tipY)
        p.lineTo(cx + px * w, cy + py * w)
        p.lineTo(tailX + px * w * 0.35f, tailY + py * w * 0.35f)
        p.lineTo(tailX - px * w * 0.35f, tailY - py * w * 0.35f)
        p.lineTo(cx - px * w, cy - py * w)
        p.close()
        canvas.drawPath(p, paint)
    }
}
