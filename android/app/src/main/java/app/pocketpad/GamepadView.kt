package app.pocketpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The on-screen controller. Draws whatever the [Preset] says (Xbox or PlayStation look, positions,
 * sizes, remapped actions), handles multi-touch play, and has an edit mode to drag, resize and remap controls.
 */
class GamepadView(
    context: Context,
    private val state: PadState,
    private val preset: Preset,
) : View(context) {

    /** Round-trip time to the laptop in ms; -2 = connecting, -1 = no answer. */
    @Volatile
    var pingMs = -2

    var onClose: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null

    /** When true, "Done" in edit mode leaves the screen instead of returning to play. */
    var exitOnDone = false

    var editMode = false
        private set

    /** A control placed on screen right now (pixels). */
    private class Item(val ctl: Ctl, val cfg: CC) {
        var cx = 0f
        var cy = 0f
        var a = 0f
        var b = 0f

        /** 0 at the centre, 1 at the edge; used to pick the nearest control. */
        fun distance(x: Float, y: Float): Float =
            if (ctl.kind == Ctl.RECT) maxOf(abs(x - cx) / a, abs(y - cy) / b)
            else hypot(x - cx, y - cy) / a
    }

    /** Touch state of one analog stick. */
    private class StickRt {
        var pid = -1
        var dx = 0f
        var dy = 0f
    }

    private class Chip(val id: String, val label: String, val rect: RectF, val accent: Boolean = false)

    private val items = ArrayList<Item>()
    private val sticks = HashMap<String, StickRt>()
    private var pressedMask = 0
    private var ltOn = false
    private var rtOn = false
    private var closePid = -1
    private var editPid = -1
    private val hud = RectF()

    // edit mode
    private var selected: String? = null
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var chipDown: String? = null

    private val dens = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = mono
    }
    private val rect = RectF()
    private val path = Path()
    private val vibrator: Vibrator? = try {
        context.getSystemService(Vibrator::class.java)
    } catch (_: Exception) {
        null
    }

    init {
        for (c in Layout.all) if (c.kind == Ctl.STICK) sticks[c.id] = StickRt()
    }

    fun startEditing() {
        releaseAll()
        editMode = true
        selected = null
        invalidate()
    }

    private fun stopEditing() {
        editMode = false
        selected = null
        dragging = false
        onSave?.invoke()
        if (exitOnDone) onClose?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        val hh = 0.1f * h
        hud.set(w * 0.5f - 0.13f * w, 0.03f * h, w * 0.5f + 0.13f * w, 0.03f * h + hh)
        // Keep the system back-swipe from stealing touches near the screen edges.
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    /** Recompute pixel positions from the preset. Cheap enough to run on every drag step. */
    private fun rebuild() {
        items.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        for (ctl in Layout.all) {
            val cfg = preset.cc[ctl.id] ?: continue
            val it = Item(ctl, cfg)
            it.cx = cfg.x * w
            it.cy = cfg.y * h
            it.a = ctl.a * h * cfg.scale
            it.b = ctl.b * h * cfg.scale
            items.add(it)
        }
    }

    private fun releaseAll() {
        closePid = -1
        editPid = -1
        for (s in sticks.values) {
            s.pid = -1
            s.dx = 0f
            s.dy = 0f
        }
        pushSticks()
        publish(0, 0, 0)
    }

    // ---------- sticks ----------

    private fun stickItem(id: String) = items.firstOrNull { it.ctl.id == id }

    private fun moveStick(id: String, x: Float, y: Float) {
        val it = stickItem(id) ?: return
        val s = sticks[id] ?: return
        var vx = (x - it.cx) / it.a
        var vy = (y - it.cy) / it.a
        val m = hypot(vx, vy)
        if (m > 1f) {
            vx /= m
            vy /= m
        }
        s.dx = vx
        s.dy = vy
        pushSticks()
    }

    private fun releaseStick(id: String) {
        val s = sticks[id] ?: return
        s.pid = -1
        s.dx = 0f
        s.dy = 0f
        pushSticks()
    }

    /** Apply the preset's stick speed (gain) and a small dead zone, then hand the values to the sender. */
    private fun pushSticks() {
        fun out(id: String, gain: Float): IntArray {
            val s = sticks[id] ?: return intArrayOf(0, 0)
            val m = hypot(s.dx, s.dy)
            if (m < 0.05f) return intArrayOf(0, 0)
            val k = minOf(1f, m * gain) / m
            return intArrayOf((s.dx * k * 32767f).toInt(), (-s.dy * k * 32767f).toInt()) // Xbox: up is positive
        }
        val l = out("LS", preset.leftGain)
        val r = out("RS", preset.rightGain)
        state.lx = l[0]
        state.ly = l[1]
        state.rx = r[0]
        state.ry = r[1]
        state.changed()
        invalidate()
    }

    // ---------- touch ----------

    private fun closeHit(x: Float, y: Float) = hypot(x - (hud.right - hud.height() * 0.5f), y - hud.centerY()) < hud.height() * 0.7f

    private fun editHit(x: Float, y: Float) = hypot(x - (hud.right - hud.height() * 1.45f), y - hud.centerY()) < hud.height() * 0.7f

    private fun hit(x: Float, y: Float): Item? {
        var best: Item? = null
        var bestD = 1.15f
        for (it in items) {
            if (it.ctl.kind == Ctl.STICK) continue
            val d = it.distance(x, y)
            if (d <= bestD) {
                bestD = d
                best = it
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean =
        if (editMode) editTouch(e) else playTouch(e)

    private fun playTouch(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll()
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

            if (id == closePid || id == editPid) {
                if (lifting && id == actId) {
                    val wasClose = id == closePid
                    closePid = -1
                    editPid = -1
                    if (wasClose && closeHit(x, y)) onClose?.invoke()
                    else if (!wasClose && editHit(x, y)) startEditing()
                }
                continue
            }
            if (downing && id == actId) {
                if (closeHit(x, y)) {
                    closePid = id
                    continue
                }
                if (editHit(x, y)) {
                    editPid = id
                    continue
                }
            }
            if (lifting && id == actId) {
                for ((sid, s) in sticks) if (s.pid == id) releaseStick(sid)
                continue
            }
            val stickId = sticks.entries.firstOrNull { it.value.pid == id }?.key
            if (stickId != null) {
                moveStick(stickId, x, y)
                continue
            }
            val item = hit(x, y)
            if (item != null) {
                val a = item.cfg.action
                if (a in 0..13) mask = mask or (1 shl a)
                if (a == Act.LT) lt = 255
                if (a == Act.RT) rt = 255
                continue
            }
            if (downing && id == actId) {
                for (it in items) {
                    if (it.ctl.kind != Ctl.STICK) continue
                    val s = sticks[it.ctl.id] ?: continue
                    if (s.pid == -1 && hypot(x - it.cx, y - it.cy) <= it.a * 1.2f) {
                        s.pid = id
                        moveStick(it.ctl.id, x, y)
                        break
                    }
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
        state.changed()
        if (newlyPressed) tick()
        invalidate()
    }

    private fun tick() {
        try {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {
        }
    }

    // ---------- edit mode ----------

    private fun chips(): List<Chip> {
        val w = width.toFloat()
        val h = height.toFloat()
        val out = ArrayList<Chip>()
        val ch = 0.08f * h
        val gap = 0.012f * w

        // row 1: Done, Reset, Skin
        var x = w * 0.5f - (0.09f + 0.09f + 0.15f) * w / 2 - gap
        val y1 = 0.02f * h
        fun add1(id: String, label: String, wFrac: Float, accent: Boolean = false) {
            out.add(Chip(id, label, RectF(x, y1, x + wFrac * w, y1 + ch), accent))
            x += wFrac * w + gap
        }
        add1("done", "DONE", 0.09f, true)
        add1("reset", "RESET", 0.09f)
        add1("skin", if (preset.playstation) "LOOK  PS5" else "LOOK  XBOX", 0.15f)

        // row 2: action and size of the selected control
        val sel = selected?.let { id -> items.firstOrNull { it.ctl.id == id } }
        if (sel != null) {
            val y2 = y1 + ch + 0.016f * h
            val hasAction = sel.ctl.natural >= 0
            val total = (if (hasAction) 0.04f + 0.13f + 0.04f + 0.02f else 0f) + 0.04f + 0.075f + 0.04f
            var x2 = w * 0.5f - total * w / 2
            fun add2(id: String, label: String, wFrac: Float) {
                out.add(Chip(id, label, RectF(x2, y2, x2 + wFrac * w, y2 + ch)))
                x2 += wFrac * w
            }
            if (hasAction) {
                add2("act-", "<", 0.04f)
                add2("actname", Act.name(sel.cfg.action, preset.playstation), 0.13f)
                add2("act+", ">", 0.04f)
                x2 += 0.02f * w
            }
            add2("size-", "-", 0.04f)
            add2("size", "${(sel.cfg.scale * 100).toInt()}%", 0.075f)
            add2("size+", "+", 0.04f)
        }
        return out
    }

    private fun chipAt(x: Float, y: Float): Chip? = chips().firstOrNull { it.rect.contains(x, y) }

    private fun runChip(id: String) {
        val sel = selected?.let { s -> items.firstOrNull { it.ctl.id == s } }
        when (id) {
            "done" -> stopEditing()
            "reset" -> {
                preset.resetLayout()
                selected = null
                rebuild()
            }
            "skin" -> {
                preset.applySkin(!preset.playstation)
                rebuild()
            }
            "act+", "act-" -> if (sel != null) {
                val i = Act.cycle.indexOf(sel.cfg.action).coerceAtLeast(0)
                val n = Act.cycle.size
                sel.cfg.action = Act.cycle[(i + if (id == "act+") 1 else n - 1) % n]
            }
            "size+", "size-" -> if (sel != null) {
                sel.cfg.scale = (sel.cfg.scale + if (id == "size+") 0.1f else -0.1f).coerceIn(0.5f, 2.0f)
                rebuild()
            }
        }
        invalidate()
    }

    private fun editTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = chipAt(x, y)
                if (c != null) {
                    chipDown = c.id
                    return true
                }
                chipDown = null
                // nearest control, sticks included
                var best: Item? = null
                var bestD = 1.15f
                for (it in items) {
                    val d = it.distance(x, y)
                    if (d <= bestD) {
                        bestD = d
                        best = it
                    }
                }
                selected = best?.ctl?.id
                if (best != null) {
                    dragging = true
                    dragOffX = best.cx - x
                    dragOffY = best.cy - y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = selected
                if (dragging && id != null) {
                    val cfg = preset.cc[id] ?: return true
                    cfg.x = ((x + dragOffX) / width).coerceIn(0.03f, 0.97f)
                    cfg.y = ((y + dragOffY) / height).coerceIn(0.03f, 0.97f)
                    rebuild()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val d = chipDown
                chipDown = null
                if (d != null && chipAt(x, y)?.id == d) runChip(d)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                chipDown = null
            }
        }
        return true
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.bg)
        val sw = dens * 1.2f
        val ps = preset.playstation

        for (it in items) {
            if (it.ctl.kind == Ctl.STICK) drawStick(canvas, it, sw)
        }
        for (it in items) {
            if (it.ctl.kind != Ctl.STICK) drawButton(canvas, it, sw, ps)
        }

        if (editMode) {
            drawSelection(canvas, sw)
            drawChips(canvas, sw)
            textPaint.textSize = dens * 10f
            textPaint.color = Theme.sub
            canvas.drawText("DRAG TO MOVE  -  TAP A CONTROL TO CHANGE IT", width / 2f, height - dens * 14f, textPaint)
        } else {
            drawHud(canvas, sw)
        }
    }

    private fun drawStick(canvas: Canvas, it: Item, sw: Float) {
        val st = sticks[it.ctl.id] ?: return
        paint.style = Paint.Style.FILL
        paint.color = Theme.surface
        canvas.drawCircle(it.cx, it.cy, it.a, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Theme.line
        canvas.drawCircle(it.cx, it.cy, it.a, paint)
        canvas.drawCircle(it.cx, it.cy, it.a * 0.55f, paint)

        val active = st.pid != -1
        val kx = it.cx + st.dx * it.a * 0.55f
        val ky = it.cy + st.dy * it.a * 0.55f
        paint.style = Paint.Style.FILL
        paint.color = if (active) Theme.ink else Theme.surface2
        canvas.drawCircle(kx, ky, it.a * 0.42f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = if (active) Theme.ink else Theme.line
        canvas.drawCircle(kx, ky, it.a * 0.42f, paint)
    }

    private fun drawButton(canvas: Canvas, it: Item, sw: Float, ps: Boolean) {
        val act = it.cfg.action
        val pressed = (act in 0..13 && (pressedMask and (1 shl act)) != 0) ||
            (act == Act.LT && ltOn) || (act == Act.RT && rtOn)
        val tint = Act.color(act, ps)
        val shown = if (tint != 0) tint else Theme.sub
        val fill = if (pressed) (if (tint != 0) tint else Theme.ink) else Theme.surface
        val line = if (pressed) fill else if (tint != 0) withAlpha(tint, 150) else Theme.line
        val round = it.ctl.kind == Ctl.CIRCLE
        if (!round) rect.set(it.cx - it.a, it.cy - it.b, it.cx + it.a, it.cy + it.b)

        paint.style = Paint.Style.FILL
        paint.color = fill
        if (round) canvas.drawCircle(it.cx, it.cy, it.a, paint) else canvas.drawRoundRect(rect, it.b, it.b, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = line
        if (round) canvas.drawCircle(it.cx, it.cy, it.a, paint) else canvas.drawRoundRect(rect, it.b, it.b, paint)

        val ink = if (pressed) Theme.bg else shown
        val label = Act.label(act, ps)
        if (label.isEmpty()) {
            drawPsGlyph(canvas, act, it.cx, it.cy, it.a * 0.42f, ink, sw * 1.6f)
        } else {
            textPaint.textSize = (if (round) it.a else it.b) * (if (label.length > 2) 0.62f else 0.85f)
            textPaint.color = ink
            canvas.drawText(label, it.cx, it.cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    /** PlayStation face symbols, drawn as shapes so they never depend on fonts. */
    private fun drawPsGlyph(canvas: Canvas, act: Int, cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        when (act) {
            Act.A -> { // cross
                canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
                canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
            }
            Act.B -> canvas.drawCircle(cx, cy, r, paint) // circle
            Act.X -> canvas.drawRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, paint) // square
            else -> { // triangle
                path.reset()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r * 1.05f, cy + r * 0.8f)
                path.lineTo(cx - r * 1.05f, cy + r * 0.8f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun drawSelection(canvas: Canvas, sw: Float) {
        val sel = selected?.let { id -> items.firstOrNull { it.ctl.id == id } } ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 1.6f
        paint.color = Theme.accent
        paint.pathEffect = DashPathEffect(floatArrayOf(dens * 6f, dens * 5f), 0f)
        if (sel.ctl.kind == Ctl.RECT) {
            val g = dens * 6f
            rect.set(sel.cx - sel.a - g, sel.cy - sel.b - g, sel.cx + sel.a + g, sel.cy + sel.b + g)
            canvas.drawRoundRect(rect, sel.b + g, sel.b + g, paint)
        } else {
            canvas.drawCircle(sel.cx, sel.cy, sel.a + dens * 6f, paint)
        }
        paint.pathEffect = null
    }

    private fun drawChips(canvas: Canvas, sw: Float) {
        for (c in chips()) {
            val r = c.rect.height() / 2
            paint.style = Paint.Style.FILL
            paint.color = if (c.accent) Theme.ink else Theme.surface
            canvas.drawRoundRect(c.rect, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = if (c.accent) Theme.ink else Theme.line
            canvas.drawRoundRect(c.rect, r, r, paint)
            textPaint.textSize = c.rect.height() * 0.34f
            textPaint.color = if (c.accent) Theme.bg else Theme.ink
            canvas.drawText(c.label, c.rect.centerX(), c.rect.centerY() + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawHud(canvas: Canvas, sw: Float) {
        val h = hud.height()
        paint.style = Paint.Style.FILL
        paint.color = Theme.surface
        canvas.drawRoundRect(hud, h / 2, h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Theme.line
        canvas.drawRoundRect(hud, h / 2, h / 2, paint)

        val ms = pingMs
        paint.style = Paint.Style.FILL
        paint.color = if (ms >= 0) Theme.accent else Theme.sub
        canvas.drawCircle(hud.left + h * 0.55f, hud.centerY(), h * 0.12f, paint)

        val label = when {
            ms >= 0 -> "$ms MS"
            ms == -1 -> "NO LAPTOP"
            else -> "CONNECTING"
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.36f
        textPaint.color = Theme.ink
        canvas.drawText(label, hud.left + h * 0.85f, hud.centerY() + textPaint.textSize * 0.35f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Theme.sub
        val k = h * 0.14f

        // customise icon: three sliders
        val ex = hud.right - h * 1.45f
        val ey = hud.centerY()
        for (i in -1..1) {
            canvas.drawLine(ex - k * 1.2f, ey + i * k * 0.95f, ex + k * 1.2f, ey + i * k * 0.95f, paint)
        }

        // close icon
        val cx = hud.right - h * 0.5f
        canvas.drawLine(cx - k, ey - k, cx + k, ey + k, paint)
        canvas.drawLine(cx - k, ey + k, cx + k, ey - k, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        android.graphics.Color.argb(alpha, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
}
