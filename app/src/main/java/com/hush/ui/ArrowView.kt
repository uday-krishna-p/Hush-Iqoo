package com.hush.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A big arrow that rotates on screen. [angleDeg] is clockwise from the top of the phone and is the angle the
 * arrow is ASKED to show; the drawn arrow glides towards it the shortest way round (27 Sep, team: "a lazy
 * turning effect"), closing a fraction of the gap every frame (time constant [TAU_S]) and never faster than
 * [MAX_DEG_PER_S], so a new target is reached in about half a second without jumps.
 */
class ArrowView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var angleDeg: Float = 0f
        set(value) { field = value; startAnimating() }
    var label: String = ""
        set(value) { field = value; invalidate() }
    var active: Boolean = false
        set(value) { field = value; invalidate() }
    /** A second, faint arrow: the mirror candidate while the two-mic direction is not yet resolved (unused since 27 Sep 03:30: one arrow only). */
    var twinAngleDeg: Float? = null
        set(value) { field = value; startAnimating() }
    /** 0..1: how sure the estimate is; the arrow goes from pale to full green with it. */
    var confidence: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    companion object {
        /** Seconds for the gap to shrink to 37 %: about 0.6 s to settle within a degree or two. */
        const val TAU_S = 0.22f
        const val MAX_DEG_PER_S = 480f
    }

    // What is drawn now (the animation state), as opposed to what was asked for.
    private var shownDeg = 0f
    private var shownTwinDeg: Float? = null
    private var animating = false
    private var lastFrameNs = 0L
    private val frame = Runnable { step() }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 122, 72) }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 206, 213) }
    private val twin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 27, 122, 72) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(221, 226, 232); style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(243, 245, 248) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 33, 43); textSize = 20f * sp; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val labelSize = 20f * sp

    private fun startAnimating() {
        if (animating) return
        animating = true
        lastFrameNs = 0L
        postOnAnimation(frame)
    }

    /** One animation frame: move the drawn angles towards the asked ones, keep going until they agree. */
    private fun step() {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNs = now
        shownDeg = approach(shownDeg, angleDeg, dt)
        val t = twinAngleDeg
        shownTwinDeg = if (t == null) null else approach(shownTwinDeg ?: t, t, dt)   // a new twin appears in place, no sweep from 0
        val done = angDiff(shownDeg, angleDeg) < 0.3f && (t == null || angDiff(shownTwinDeg ?: t, t) < 0.3f)
        if (done) { shownDeg = angleDeg; shownTwinDeg = t; animating = false }
        invalidate()
        if (!done) postOnAnimation(frame)
    }

    private fun approach(from: Float, to: Float, dt: Float): Float {
        var diff = ((to - from) % 360f + 360f) % 360f
        if (diff > 180f) diff -= 360f                    // the shortest way round
        val k = (dt / TAU_S).coerceAtMost(1f)
        val maxStep = MAX_DEG_PER_S * dt
        val move = (diff * k).coerceIn(-maxStep, maxStep)
        return ((from + move) % 360f + 360f) % 360f
    }

    private fun angDiff(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 360f) % 360f); return if (d > 180f) 360f - d else d }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelBand = labelSize * 1.8f
        val cx = width / 2f
        val cy = (height - labelBand) / 2f
        val r = min(width.toFloat(), height - labelBand) * 0.44f
        canvas.drawCircle(cx, cy, r, ringFill)
        canvas.drawCircle(cx, cy, r, ring)
        shownTwinDeg?.let { drawArrow(canvas, cx, cy, r, it, twin) }
        if (active) fill.alpha = (90 + 165 * confidence).toInt()
        drawArrow(canvas, cx, cy, r, shownDeg, if (active) fill else dim)
        // One line: shrink a long label to fit the width rather than cut it off.
        text.textSize = labelSize
        val maxW = width - 16f * dp
        val w = text.measureText(label)
        if (w > maxW) text.textSize = (labelSize * maxW / w).coerceAtLeast(12f * sp)
        text.color = if (active) Color.rgb(24, 33, 43) else Color.rgb(91, 103, 118)
        canvas.drawText(label, cx, height - labelSize * 0.45f, text)
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
