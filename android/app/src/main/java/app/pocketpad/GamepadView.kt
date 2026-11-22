package app.pocketpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Full-screen on-screen controller (Xbox layout) with multi-touch, sliding and haptics. */
class GamepadView(context: Context, private val state: PadState) : View(context) {

    private class Shape(
        val round: Boolean,
        val cx: Float,
        val cy: Float,
        val a: Float,      // radius, or half-width
        val b: Float,      // half-height (rect only)
        val bit: Int,
        val trigger: Int,  // 0 none, 1 LT, 2 RT
        val label: String,
        val color: Int,
    ) {
        /** 0 at the centre, 1 at the edge; used to pick the nearest control. */
        fun distance(x: Float, y: Float): Float =
            if (round) hypot(x - cx, y - cy) / a
            else maxOf(abs(x - cx) / a, abs(y - cy) / b)
    }

    private inner class Stick(val cx: Float, val cy: Float, val r: Float, val left: Boolean) {
        var pid = -1
        var dx = 0f
        var dy = 0f

        fun near(x: Float, y: Float) = hypot(x - cx, y - cy) <= r * 1.2f

        fun move(x: Float, y: Float) {
            var vx = (x - cx) / r
            var vy = (y - cy) / r
            val m = hypot(vx, vy)
            if (m > 1f) {
                vx /= m
                vy /= m
            }
            dx = vx
            dy = vy
            push()
        }

        fun release() {
            pid = -1
            dx = 0f
            dy = 0f
            push()
        }

        private fun push() {
            val dead = hypot(dx, dy) < 0.08f
            val ox = if (dead) 0 else (dx * 32767f).toInt()
            val oy = if (dead) 0 else (-dy * 32767f).toInt() // Xbox: up is positive
            if (left) {
                state.lx = ox
                state.ly = oy
            } else {
                state.rx = ox
                state.ry = oy
            }
            invalidate()
        }
    }

    private val shapes = ArrayList<Shape>()
    private val sticks = ArrayList<Stick>()
    private var pressedMask = 0
    private var ltOn = false
    private var rtOn = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shapes.clear()
        sticks.clear()
        val W = w.toFloat()
        val H = h.toFloat()
        val u = H

        // Face buttons
        val fx = 0.88f * W
        val fy = 0.52f * H
        val off = 0.145f * u
        val fr = 0.085f * u
        shapes += Shape(true, fx, fy - off, fr, fr, Bit.Y, 0, "Y", 0xFFF5C542.toInt())
        shapes += Shape(true, fx, fy + off, fr, fr, Bit.A, 0, "A", 0xFF4CAF50.toInt())
        shapes += Shape(true, fx - off, fy, fr, fr, Bit.X, 0, "X", 0xFF4C8DF6.toInt())
        shapes += Shape(true, fx + off, fy, fr, fr, Bit.B, 0, "B", 0xFFE5484D.toInt())

        // D-pad
        val dx = 0.35f * W
        val dy = 0.74f * H
        val doff = 0.14f * u
        val dr = 0.075f * u
        val grey = 0xFF8A8A93.toInt()
        shapes += Shape(true, dx, dy - doff, dr, dr, Bit.UP, 0, "↑", grey)
        shapes += Shape(true, dx, dy + doff, dr, dr, Bit.DOWN, 0, "↓", grey)
        shapes += Shape(true, dx - doff, dy, dr, dr, Bit.LEFT, 0, "←", grey)
        shapes += Shape(true, dx + doff, dy, dr, dr, Bit.RIGHT, 0, "→", grey)

        // Shoulders and triggers
        val sa = 0.06f * W
        val sb = 0.06f * H
        shapes += Shape(false, 0.08f * W, 0.08f * H, sa, sb, 0, 1, "LT", grey)
        shapes += Shape(false, 0.08f * W, 0.22f * H, sa, sb, Bit.LB, 0, "LB", grey)
        shapes += Shape(false, 0.92f * W, 0.08f * H, sa, sb, 0, 2, "RT", grey)
        shapes += Shape(false, 0.92f * W, 0.22f * H, sa, sb, Bit.RB, 0, "RB", grey)

        // Centre buttons
        shapes += Shape(false, 0.44f * W, 0.10f * H, 0.045f * W, 0.05f * H, Bit.BACK, 0, "Back", grey)
        shapes += Shape(false, 0.56f * W, 0.10f * H, 0.045f * W, 0.05f * H, Bit.START, 0, "Start", grey)

        // Stick clicks
        shapes += Shape(true, 0.26f * W, 0.40f * H, 0.05f * u, 0.05f * u, Bit.L3, 0, "L3", grey)
        shapes += Shape(true, 0.60f * W, 0.46f * H, 0.05f * u, 0.05f * u, Bit.R3, 0, "R3", grey)

        // Sticks
        sticks += Stick(0.14f * W, 0.62f * H, 0.21f * u, true)
        sticks += Stick(0.64f * W, 0.76f * H, 0.20f * u, false)

        // Keep the system back-swipe from stealing touches near the screen edges.
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    private fun hit(x: Float, y: Float): Shape? {
        var best: Shape? = null
        var bestD = 1.15f
        for (s in shapes) {
            val d = s.distance(x, y)
            if (d <= bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            for (s in sticks) s.release()
            publish(0, 0, 0)
            return true
        }
        val actIdx = e.actionIndex
        val actId = e.getPointerId(actIdx)
        val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val downing = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN

        var mask = 0
        var lt = 0
        var rt = 0
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (lifting && id == actId) {
                sticks.firstOrNull { it.pid == id }?.release()
                continue
            }
            val x = e.getX(i)
            val y = e.getY(i)
            val stick = sticks.firstOrNull { it.pid == id }
            if (stick != null) {
                stick.move(x, y)
                continue
            }
            val shape = hit(x, y)
            if (shape != null) {
                mask = mask or shape.bit
                if (shape.trigger == 1) lt = 255
                if (shape.trigger == 2) rt = 255
                continue
            }
            if (downing && id == actId) {
                val s = sticks.firstOrNull { it.pid == -1 && it.near(x, y) }
                if (s != null) {
                    s.pid = id
                    s.move(x, y)
                }
            }
        }
        publish(mask, lt, rt)
        return true
    }

    private fun publish(mask: Int, lt: Int, rt: Int) {
        val newlyPressed = (mask and pressedMask.inv()) != 0 || (lt == 255 && !ltOn) || (rt == 255 && !rtOn)
        pressedMask = mask
        ltOn = lt == 255
        rtOn = rt == 255
        state.buttons = mask
        state.lt = lt
        state.rt = rt
        if (newlyPressed) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(14, 14, 16))

        for (st in sticks) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(40, 255, 255, 255)
            canvas.drawCircle(st.cx, st.cy, st.r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = st.r * 0.04f
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawCircle(st.cx, st.cy, st.r, paint)

            val active = st.pid != -1
            paint.style = Paint.Style.FILL
            paint.color = if (active) Color.argb(230, 255, 255, 255) else Color.argb(150, 255, 255, 255)
            canvas.drawCircle(st.cx + st.dx * st.r * 0.6f, st.cy + st.dy * st.r * 0.6f, st.r * 0.42f, paint)
        }

        for (s in shapes) {
            val pressed = (s.bit != 0 && (pressedMask and s.bit) != 0) ||
                (s.trigger == 1 && ltOn) || (s.trigger == 2 && rtOn)
            val base = s.color
            paint.style = Paint.Style.FILL
            paint.color = if (pressed) withAlpha(base, 255) else withAlpha(base, 70)
            if (s.round) {
                canvas.drawCircle(s.cx, s.cy, s.a, paint)
            } else {
                rect.set(s.cx - s.a, s.cy - s.b, s.cx + s.a, s.cy + s.b)
                canvas.drawRoundRect(rect, s.b * 0.5f, s.b * 0.5f, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = withAlpha(base, 200)
            if (s.round) {
                canvas.drawCircle(s.cx, s.cy, s.a, paint)
            } else {
                canvas.drawRoundRect(rect, s.b * 0.5f, s.b * 0.5f, paint)
            }
            textPaint.textSize = (if (s.round) s.a else s.b) * 0.85f
            textPaint.color = if (pressed) Color.BLACK else Color.WHITE
            canvas.drawText(s.label, s.cx, s.cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
