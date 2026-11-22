package app.pocketpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Full-screen on-screen controller (Xbox layout) with multi-touch, sliding, haptics and a small status bar. */
class GamepadView(context: Context, private val state: PadState) : View(context) {

    /** Round-trip time to the laptop in ms; -2 = connecting, -1 = no answer. */
    @Volatile
    var pingMs = -2

    var onClose: (() -> Unit)? = null

    private class Shape(
        val round: Boolean,
        val cx: Float,
        val cy: Float,
        val a: Float,      // radius, or half-width
        val b: Float,      // half-height (rect only)
        val bit: Int,
        val trigger: Int,  // 0 none, 1 LT, 2 RT
        val label: String,
        val color: Int,    // 0 = neutral
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
    private var closePid = -1
    private val hud = RectF()

    private val dens = resources.displayMetrics.density
    private val cBg = Color.rgb(10, 10, 12)
    private val cSurface = Color.rgb(20, 20, 23)
    private val cStroke = Color.rgb(52, 52, 60)
    private val cText = Color.rgb(154, 154, 164)
    private val cLight = Color.rgb(236, 236, 240)
    private val cAccent = Color.rgb(53, 224, 161)
    private val cBad = Color.rgb(255, 107, 99)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private val vibrator: Vibrator? = try {
        context.getSystemService(Vibrator::class.java)
    } catch (_: Exception) {
        null
    }

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
        shapes += Shape(true, fx, fy + off, fr, fr, Bit.A, 0, "A", 0xFF4CD08A.toInt())
        shapes += Shape(true, fx - off, fy, fr, fr, Bit.X, 0, "X", 0xFF5B9BFF.toInt())
        shapes += Shape(true, fx + off, fy, fr, fr, Bit.B, 0, "B", 0xFFFF5F66.toInt())

        // D-pad
        val dx = 0.35f * W
        val dy = 0.74f * H
        val doff = 0.14f * u
        val dr = 0.075f * u
        shapes += Shape(true, dx, dy - doff, dr, dr, Bit.UP, 0, "↑", 0)
        shapes += Shape(true, dx, dy + doff, dr, dr, Bit.DOWN, 0, "↓", 0)
        shapes += Shape(true, dx - doff, dy, dr, dr, Bit.LEFT, 0, "←", 0)
        shapes += Shape(true, dx + doff, dy, dr, dr, Bit.RIGHT, 0, "→", 0)

        // Shoulders and triggers
        val sa = 0.06f * W
        val sb = 0.06f * H
        shapes += Shape(false, 0.08f * W, 0.08f * H, sa, sb, 0, 1, "LT", 0)
        shapes += Shape(false, 0.08f * W, 0.22f * H, sa, sb, Bit.LB, 0, "LB", 0)
        shapes += Shape(false, 0.92f * W, 0.08f * H, sa, sb, 0, 2, "RT", 0)
        shapes += Shape(false, 0.92f * W, 0.22f * H, sa, sb, Bit.RB, 0, "RB", 0)

        // Back / Start sit under the status bar
        shapes += Shape(false, 0.455f * W, 0.27f * H, 0.04f * W, 0.055f * H, Bit.BACK, 0, "Back", 0)
        shapes += Shape(false, 0.545f * W, 0.27f * H, 0.04f * W, 0.055f * H, Bit.START, 0, "Start", 0)

        // Stick clicks
        shapes += Shape(true, 0.26f * W, 0.40f * H, 0.05f * u, 0.05f * u, Bit.L3, 0, "L3", 0)
        shapes += Shape(true, 0.60f * W, 0.46f * H, 0.05f * u, 0.05f * u, Bit.R3, 0, "R3", 0)

        // Sticks
        sticks += Stick(0.14f * W, 0.62f * H, 0.21f * u, true)
        sticks += Stick(0.64f * W, 0.76f * H, 0.20f * u, false)

        // Status bar (top centre)
        hud.set(W * 0.5f - 0.1f * W, 0.03f * H, W * 0.5f + 0.1f * W, 0.03f * H + 0.1f * H)

        // Keep the system back-swipe from stealing touches near the screen edges.
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    private fun closeHit(x: Float, y: Float): Boolean {
        val s = hud.height() * 1.4f
        return x >= hud.right - s && x <= hud.right + s * 0.2f && y >= hud.top - s * 0.2f && y <= hud.bottom + s * 0.4f
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
            closePid = -1
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
            val x = e.getX(i)
            val y = e.getY(i)

            if (id == closePid) {
                if (lifting && id == actId) {
                    closePid = -1
                    if (closeHit(x, y)) onClose?.invoke()
                }
                continue
            }
            if (downing && id == actId && closeHit(x, y)) {
                closePid = id
                continue
            }
            if (lifting && id == actId) {
                sticks.firstOrNull { it.pid == id }?.release()
                continue
            }
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
        if (newlyPressed) tick()
        invalidate()
    }

    private fun tick() {
        try {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(cBg)
        val sw = dens * 1.5f

        for (st in sticks) {
            paint.style = Paint.Style.FILL
            paint.color = cSurface
            canvas.drawCircle(st.cx, st.cy, st.r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = cStroke
            canvas.drawCircle(st.cx, st.cy, st.r, paint)
            canvas.drawCircle(st.cx, st.cy, st.r * 0.55f, paint)

            val active = st.pid != -1
            val kx = st.cx + st.dx * st.r * 0.55f
            val ky = st.cy + st.dy * st.r * 0.55f
            paint.style = Paint.Style.FILL
            paint.color = if (active) cLight else Color.rgb(44, 44, 51)
            canvas.drawCircle(kx, ky, st.r * 0.42f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = if (active) cLight else Color.rgb(74, 74, 84)
            canvas.drawCircle(kx, ky, st.r * 0.42f, paint)
        }

        for (s in shapes) {
            val pressed = (s.bit != 0 && (pressedMask and s.bit) != 0) ||
                (s.trigger == 1 && ltOn) || (s.trigger == 2 && rtOn)
            val tint = if (s.color != 0) s.color else cText
            val fill = if (pressed) (if (s.color != 0) s.color else cLight) else cSurface
            val line = when {
                pressed -> fill
                s.color != 0 -> withAlpha(s.color, 150)
                else -> cStroke
            }
            if (!s.round) rect.set(s.cx - s.a, s.cy - s.b, s.cx + s.a, s.cy + s.b)

            paint.style = Paint.Style.FILL
            paint.color = fill
            if (s.round) canvas.drawCircle(s.cx, s.cy, s.a, paint)
            else canvas.drawRoundRect(rect, s.b * 0.5f, s.b * 0.5f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = line
            if (s.round) canvas.drawCircle(s.cx, s.cy, s.a, paint)
            else canvas.drawRoundRect(rect, s.b * 0.5f, s.b * 0.5f, paint)

            textPaint.textSize = (if (s.round) s.a else s.b) * (if (s.label.length > 2) 0.7f else 0.9f)
            textPaint.color = if (pressed) cBg else tint
            canvas.drawText(s.label, s.cx, s.cy + textPaint.textSize * 0.35f, textPaint)
        }

        drawHud(canvas, sw)
    }

    private fun drawHud(canvas: Canvas, sw: Float) {
        val h = hud.height()
        paint.style = Paint.Style.FILL
        paint.color = cSurface
        canvas.drawRoundRect(hud, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = cStroke
        canvas.drawRoundRect(hud, h / 2, h / 2, paint)

        val ms = pingMs
        val dotColor = when {
            ms >= 0 -> cAccent
            ms == -1 -> cBad
            else -> cText
        }
        paint.style = Paint.Style.FILL
        paint.color = dotColor
        canvas.drawCircle(hud.left + h * 0.5f, hud.centerY(), h * 0.12f, paint)

        val label = when {
            ms >= 0 -> "$ms ms"
            ms == -1 -> "No laptop"
            else -> "Connecting"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.4f
        textPaint.color = cLight
        canvas.drawText(label, hud.left + h * 0.8f, hud.centerY() + textPaint.textSize * 0.35f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // close (x)
        val cx = hud.right - h * 0.55f
        val cy = hud.centerY()
        val k = h * 0.14f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = cText
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, paint)
        canvas.drawLine(cx - k, cy + k, cx + k, cy - k, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
